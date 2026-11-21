#!/usr/bin/env python3
"""Local fake LLM endpoint for the s15 agent-team experiment.

这一篇要测的不是模型，是「一个团队靠什么不散」。所以端点扮演所有人：
它靠请求里 system 的身份段分辨这是 lead 还是哪个 teammate，然后各自回各自的剧本。

两个场景：
    SCENARIO=main     # 起 3 个成员、广播、认领冲突、结论回传，15 项断言
    SCENARIO=naive    # 配 TEAM_MODE=naive 用：不查认领冲突，看最后 owner 是谁

断言全在端点这一侧：它读 .tasks/ 和 .team/ 下的文件、读每次请求的 messages 和 tools、
读 JVM 输出。不睡眠等状态、不等随机数，同样的剧本每次跑出来的顺序完全一样。
"""
import json
import os
import re
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8915"))
SCENARIO = os.environ.get("SCENARIO", "main")
LOG = os.environ.get("ASSERT_LOG",
                     "/tmp/s15_assert.txt" if SCENARIO == "main" else "/tmp/s15_naive.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s15_stdout.log")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")
TEAM_DIR = os.environ.get("TEAM_DIR", ".team")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"
LEAD_MARK = "You are the lead of a small agent team"
MATE_MARK = "You are a teammate named "
MATE_RE = re.compile(r"You are a teammate named ([A-Za-z0-9_\-]+)")

# lead 第 3 轮投给 researcher 的那句话。整篇最关键的一条断言查它什么时候出现
TEXT_TO_RESEARCHER = "T1 归你。读完把时间戳格式写清楚，别只贴日志。"

checks = []
state = {
    "posts": 0,
    "lead_idx": 0,
    "mate_idx": {},
    "roles": [],              # 每次 POST 的角色，按时间顺序
    "posts_log": [],          # 每次 POST 的记录
    "systems": {},            # 角色 -> 第一次见到的 system
    "messages_by_role": {},   # 角色 -> 所有请求的 messages（拼起来查隔离）
    "tools_by_role": {},      # 角色 -> 工具表
}

APP_LOG = """2026-11-21T09:14:03.512+08:00 INFO  order_service created order_id=88213
2026-11-21T09:14:04.007+08:00 WARN  order_service retry payment gateway
2026-11-21T09:14:05.881+08:00 ERROR order_service payment timeout order_id=88213
2026-11-21T09:15:11.204+08:00 INFO  order_service cancelled order_id=88213
2026-11-21T09:15:12.930+08:00 INFO  audit_service logged cancel reason=timeout
"""

SCHEMA_SQL = """CREATE TABLE orders (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
"""


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print("   [assert] %s = %s %s" % (name, "YES" if ok else "NO", detail), flush=True)


def call(*pairs):
    """一条 assistant 消息里可以带多个 tool_call，按顺序执行"""
    return {
        "choices": [{
            "message": {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_%s" % name,
                    "type": "function",
                    "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)},
                } for name, args in pairs],
            },
            "finish_reason": "tool_calls",
        }]
    }


def text(msg):
    return {"choices": [{"message": {"role": "assistant", "content": msg}, "finish_reason": "stop"}]}


# ---------- lead 的剧本：6 轮 ----------
LEAD = [
    # 1) 起三个人，第四个应该被 TEAM_MAX 挡住
    lambda: call(
        ("team_spawn", {"name": "researcher", "role": "查清 data 目录里日志的时间戳格式",
                        "prompt": "读 data/app.log，确认时间戳是什么格式。查完给我一句话结论。"}),
        ("team_spawn", {"name": "indexer", "role": "查清 db/schema.sql 里有没有联合索引",
                        "prompt": "读 db/schema.sql，看有没有联合索引。查完给我一句话结论。"}),
        ("team_spawn", {"name": "auditor", "role": "复核另外两个人的结论有没有矛盾",
                        "prompt": "等另外两个人的结论出来，复核一遍，别自己下结论。"}),
        ("team_spawn", {"name": "reviewer", "role": "第四个成员，应该被上限挡住",
                        "prompt": "这条 spawn 必须失败，用来验证 TEAM_MAX。"}),
    ),
    # 2) 把活摊到盘上
    lambda: call(
        ("task_create", {"subject": "核对日志时间戳格式"}),
        ("task_create", {"subject": "检查 orders 表的联合索引"}),
        ("task_create", {"subject": "复核两条结论"}),
    ),
    # 3) 给 researcher 一句话。它这一轮读不到
    lambda: call(("send_message", {"to": "researcher", "text": TEXT_TO_RESEARCHER})),
    # 4) 广播，然后立刻问队形：三份收件箱这时都该有 1 条未读
    lambda: call(
        ("send_message", {"to": "all", "text": "还差谁的结论没交？"}),
        ("team_status", {}),
    ),
    # 5) 收尾前看一眼盘上的状态
    lambda: call(("task_list", {}), ("team_status", {})),
    # 6) 汇总
    lambda: text("两件事都查清了：日志是 ISO 8601 带毫秒，schema 里没有联合索引。"
                 "T1、T2 已完成，T3 还在 auditor 手里没标完成，我先不关。"),
]

# ---------- 成员的剧本 ----------
MEMBERS = {
    "researcher": [
        lambda: call(("read_file", {"path": "data/app.log", "limit": 3})),
        lambda: call(("task_update", {"id": "T1", "status": "IN_PROGRESS", "owner": "researcher"})),
        lambda: call(("read_file", {"path": "data/app.log", "limit": 5})),
        lambda: call(("task_update", {"id": "T1", "status": "COMPLETED"})),
        lambda: text("data/app.log 的时间戳是 ISO 8601，带毫秒和 +08:00 时区，"
                     "例如 2026-11-21T09:14:03.512+08:00。T1 我标完成了。"),
    ],
    "indexer": [
        lambda: call(("read_file", {"path": "db/schema.sql"})),
        # 和 researcher 抢同一条。它注册在后，所以必然被拒
        lambda: call(("task_update", {"id": "T1", "status": "IN_PROGRESS", "owner": "indexer"})),
        lambda: call(("task_update", {"id": "T2", "status": "IN_PROGRESS", "owner": "indexer"}),
                     ("task_update", {"id": "T2", "status": "COMPLETED"})),
        lambda: text("db/schema.sql 里只有单列索引 idx_orders_user_id 和 idx_orders_status，没有联合索引。"
                     "T1 被 researcher 占了，我改做 T2，已经标完成。"),
    ],
    "auditor": [
        lambda: call(("read_file", {"path": "data/app.log"})),
        lambda: call(("task_update", {"id": "T3", "status": "IN_PROGRESS", "owner": "auditor"})),
        lambda: text("复核完了：日志格式和索引结论不矛盾。T3 我没标完成，等 lead 确认再关。"),
    ],
}


def reset_state():
    for d in (TASK_DIR, TEAM_DIR):
        shutil.rmtree(d, ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    os.makedirs("data", exist_ok=True)
    os.makedirs("db", exist_ok=True)
    with open("data/app.log", "w", encoding="utf-8") as f:
        f.write(APP_LOG)
    with open("db/schema.sql", "w", encoding="utf-8") as f:
        f.write(SCHEMA_SQL)


def read_stdout():
    try:
        with open(STDOUT_LOG, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return ""


def read_tasks():
    out = {}
    if not os.path.isdir(TASK_DIR):
        return out
    for name in sorted(os.listdir(TASK_DIR)):
        if name.endswith(".json"):
            with open(os.path.join(TASK_DIR, name), encoding="utf-8") as f:
                d = json.load(f)
            out[d["id"]] = d
    return out


def inbox_lines(name):
    p = os.path.join(TEAM_DIR, name, "inbox.jsonl")
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def role_of(system):
    if SUMMARIZER_MARK in system:
        return "summarizer"
    if LEAD_MARK in system:
        return "lead"
    m = MATE_RE.search(system)
    return m.group(1) if m else "unknown"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        msgs = body.get("messages", [])
        system = next((m.get("content") or "" for m in msgs if m.get("role") == "system"), "")
        role = role_of(system)
        state["posts"] += 1
        idx = state["posts"]

        if role == "summarizer":
            # 压缩请求不占剧本，也不进角色序列
            self.reply(200, text("摘要：团队在查日志格式和索引。"))
            return

        state["roles"].append(role)
        state["systems"].setdefault(role, system)
        state["messages_by_role"].setdefault(role, []).extend(msgs)
        if role != "lead":
            state["tools_by_role"].setdefault(role, body.get("tools", []))

        if role == "lead":
            i = state["lead_idx"]
            state["lead_idx"] += 1
            script = LEAD
        else:
            i = state["mate_idx"].get(role, 0)
            state["mate_idx"][role] = i + 1
            script = MEMBERS.get(role, [])

        payload = text("done") if i >= len(script) else script[i]()
        calls = payload["choices"][0]["message"].get("tool_calls") or []
        names = [c["function"]["name"] for c in calls]
        args = json.dumps([c["function"]["arguments"] for c in calls], ensure_ascii=False)
        state["posts_log"].append({"idx": idx, "role": role, "messages": msgs,
                                   "tools": body.get("tools", []),
                                   "returns": names, "args": args})
        print("   [fake] POST %d %s 第 %d 步 → %s" % (idx, role, i, ",".join(names) or "text"),
              flush=True)
        self.reply(200, payload)

        if role == "lead" and state["lead_idx"] >= len(LEAD):
            threading.Thread(target=finish, daemon=True).start()


def finish():
    out = ""
    for _ in range(200):
        out = read_stdout()
        if "我先不关" in out:
            break
        time.sleep(0.2)

    tasks = read_tasks()
    if SCENARIO == "naive":
        finish_naive(out, tasks)
        return

    posts = state["posts_log"]
    rese = [p for p in posts if p["role"] == "researcher"]
    inde = [p for p in posts if p["role"] == "indexer"]
    audi = [p for p in posts if p["role"] == "auditor"]

    check("spawn_creates_member", rese and inde and audi,
          "(三个成员各自发过请求：researcher %d / indexer %d / auditor %d 次)"
          % (len(rese), len(inde), len(audi)))
    check("team_max_enforced", "成员上限 TEAM_MAX=3" in out,
          "(第四个人被挡在 spawn 里，不是被提示词劝住)")

    mates_ok = all(MATE_MARK + n in state["systems"].get(n, "")
                   for n in ("researcher", "indexer", "auditor"))
    check("system_identity_distinct",
          LEAD_MARK in state["systems"].get("lead", "") and mates_ok,
          "(lead 和三个成员的 system 身份段各不相同——模型只能靠这几行分辨自己是谁)")

    rese_text = json.dumps(state["messages_by_role"].get("researcher", []), ensure_ascii=False)
    inde_text = json.dumps(state["messages_by_role"].get("indexer", []), ensure_ascii=False)
    first_rese = len(rese[0]["messages"]) if rese else -1
    check("teammate_context_isolated",
          "order_service" in rese_text and "CREATE TABLE" not in rese_text
          and "CREATE TABLE" in inde_text and "order_service" not in inde_text
          and first_rese == 2,
          "(researcher 的上下文里没有 schema.sql，indexer 的里没有 app.log；"
          "第一个成员的第一条请求只有 system + 一句任务 = %d 条消息)" % first_rese)

    lead3 = next(p["idx"] for p in posts
                 if p["role"] == "lead" and TEXT_TO_RESEARCHER in p["args"])
    first_hit = next((p["idx"] for p in rese if TEXT_TO_RESEARCHER in json.dumps(
        p["messages"], ensure_ascii=False)), None)
    check("message_delivered_next_tick",
          first_hit == lead3 + 1,
          "(第 %d 次 POST 发出，researcher 在第 %s 次 POST 才读到——正好是下一轮的第一个请求)"
          % (lead3, first_hit))

    lines = inbox_lines("researcher")
    check("mailbox_on_disk",
          len(lines) == 2 and any(TEXT_TO_RESEARCHER in l.get("text", "") for l in lines),
          "(.team/researcher/inbox.jsonl 里 %d 行，读过也不删，重启还看得到)" % len(lines))

    check("claim_conflict_rejected", "T1 已经被 researcher 认领" in out,
          "(indexer 晚一步认领 T1，被 tool 拒绝，不是被提示词劝住)")

    t1 = tasks.get("T1", {})
    check("single_owner", t1.get("owner") == "researcher" and t1.get("status") == "COMPLETED",
          "(T1 最后 owner=%s、状态 %s——认领的人就是干活的人)"
          % (t1.get("owner"), t1.get("status")))
    t2 = tasks.get("T2", {})
    check("member_completed_task", t2.get("owner") == "indexer" and t2.get("status") == "COMPLETED",
          "(T2 由 indexer 认领并标完成：%s / %s)" % (t2.get("owner"), t2.get("status")))

    lead_last = json.dumps([p for p in posts if p["role"] == "lead"][-1]["messages"],
                           ensure_ascii=False)
    tagged = ('team-inbox from=\\"researcher\\"' in lead_last
              or 'team-inbox from="researcher"' in lead_last)
    check("conclusion_back_to_lead",
          "ISO 8601" in lead_last and "单列索引" in lead_last and tagged,
          "(两个成员的结论都进了 lead 的上下文，是带 from 标签的 user 消息，不是凭空出现的记忆)")

    blocks = out.split("Team 3/3：")
    first_status = blocks[1].split("   [team")[0] if len(blocks) > 1 else ""
    check("status_shows_busy", "忙（第 3 步）" in first_status and "完成" in first_status,
          "(广播之后那份 team_status：researcher 还在第 3 步，auditor 已经完成)")
    check("status_shows_unread", first_status.count("未读 1 条") == 3,
          "(同一份 team_status 里，三份收件箱各 1 条未读——广播投到了每个人)")

    expected = (["lead"] + ["researcher", "indexer", "auditor", "lead"] * 3
                + ["researcher", "indexer", "lead"] + ["researcher", "lead"])
    check("tick_round_robin_order", state["roles"] == expected,
          "(%d 次 POST 的顺序和轮转表逐项相同)" % len(state["roles"]))

    allowed = {"read_file", "glob", "task_update"}
    got = set()
    for role in ("researcher", "indexer", "auditor"):
        for t in state["tools_by_role"].get(role, []):
            got.add(t["function"]["name"])
    check("teammate_tools_readonly", got == allowed,
          "(成员的工具表只有 %s，没有 bash / write_file / edit_file / team_spawn)"
          % ",".join(sorted(got)))

    check("script_finished", True, "(剧情 6 轮跑完，%d 次 POST)" % state["posts"])
    write_log()


def finish_naive(out, tasks):
    t1 = tasks.get("T1", {})
    check("naive_claim_not_rejected", "已经被 researcher 认领" not in out,
          "(没人拦：indexer 的认领直接盖掉了 researcher 的)")
    check("naive_second_claim_wins", t1.get("owner") == "indexer",
          "(T1 最后 owner=%s——后来的人赢，干活的是 researcher)" % t1.get("owner"))
    check("naive_task_done_by_other", t1.get("status") == "COMPLETED",
          "(T1 是 COMPLETED，但 owner 已经不是做这件事的人)")
    write_log()


def write_log():
    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d\n" % (SCENARIO, state["posts"]))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s)" % (PORT, SCENARIO), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
