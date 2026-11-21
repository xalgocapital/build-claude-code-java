#!/usr/bin/env python3
"""Local fake LLM endpoint for the s16 team-protocol experiment.

s15 的端点扮演「一个团队」；这一篇扮演「一个讲协议的团队」。
它靠 system 身份段分辨 lead 和各 teammate，各自回各自的剧本。
成员答复 request 时，replyTo 不是写死的：端点从它刚收到的 messages 里
把信封 id 抽出来填进去——配对成不成立，由 Java 那边的 Protocol 说了算。

两个场景：
    SCENARIO=main     # request/response 配对、超时死信、handoff、成员直聊、孤儿拒收，15 项断言
    SCENARIO=naive    # 配 PROTOCOL_MODE=naive 用：不查 replyTo、不清超时，看死信和孤儿应答的下场

断言全在端点这一侧：读 .tasks/ 和 .team/ 下的文件、读每次请求的 messages、
读 JVM 输出。超时那一条靠 slowhand 的第三步睡 950ms（> PROTOCOL_TIMEOUT_MS=800）
把「过期」压进一次可测的 tick 边界。
"""
import json
import os
import re
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8916"))
SCENARIO = os.environ.get("SCENARIO", "main")
LOG = os.environ.get("ASSERT_LOG",
                     "/tmp/s16_assert.txt" if SCENARIO == "main" else "/tmp/s16_assert_naive.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s16_stdout.log")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")
TEAM_DIR = os.environ.get("TEAM_DIR", ".team")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"
LEAD_MARK = "You are the lead of a small agent team"
MATE_RE = re.compile(r"You are a teammate named ([A-Za-z0-9_\-]+)")
# 从成员刚收到的 messages 里抽 request 信封 id（render 出来的样子）
REQ_RE = re.compile(r'envelope id="(R\d+)"[^>]*type="request"')

checks = []
state = {
    "posts": 0,
    "lead_idx": 0,
    "mate_idx": {},
    "roles": [],
    "posts_log": [],
    "systems": {},
    "messages_by_role": {},
    "t2_owner_at": {},      # 每次 POST 时刻 T2 的 owner——handoff 前后各拍一张
    "cur_req_id": "",       # 这一 POST 的 messages 里最后一条 request 信封的 id
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

BROADCAST_TEXT = "T1 完成：日志时间戳是 ISO 8601 带毫秒，全队知悉，别再各查各的。"
DIRECT_REQ = "帮我看 orders 表有没有联合索引，一句话回我。"
DIRECT_RESP = "只有单列索引 idx_orders_user_id、idx_orders_status，没有联合索引。"


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print("   [assert] %s = %s %s" % (name, "YES" if ok else "NO", detail), flush=True)


def call(*pairs):
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
    # 1) 起人、摊任务
    lambda: call(
        ("team_spawn", {"name": "researcher", "role": "查清 data/app.log 的时间戳格式",
                        "prompt": "读 data/app.log，确认时间戳格式。查完给我一句话结论。"}),
        ("team_spawn", {"name": "indexer", "role": "查清 db/schema.sql 的索引情况",
                        "prompt": "读 db/schema.sql，看索引情况。查完给我一句话结论。"}),
        ("team_spawn", {"name": "slowhand", "role": "备份人力，响应慢",
                        "prompt": "待命。有人问再答。"}),
        ("task_create", {"subject": "核对日志时间戳格式"}),
        ("task_create", {"subject": "检查 orders 表的索引"}),
    ),
    # 2) 两封 request + 一封 handoff。给 slowhand 那封是故意没人答的——它要进死信
    lambda: call(
        ("send_message", {"to": "researcher", "type": "request",
                          "text": "日志最后一条 ERROR 的时间戳是什么格式？一句话答复。"}),
        ("send_message", {"to": "slowhand", "type": "request",
                          "text": "orders 表上现在有几个索引？报个数就行。"}),
        ("task_handoff", {"taskId": "T2", "to": "indexer"}),
    ),
    # 3) 看队形：R1 已配对、R2 还在等
    lambda: call(("team_status", {}), ("task_list", {})),
    # 4) 移交一个不存在的任务，必须被拒
    lambda: call(("task_handoff", {"taskId": "T9", "to": "researcher"})),
    # 5) R2 该进死信了，再看一次队形
    lambda: call(("team_status", {}), ("task_list", {})),
    # 6) 汇总
    lambda: text("R1 有问有答，R2 超时进死信（1 条），T2 已移交 indexer，"
                 "孤儿应答 1 条被拒。T1 完成。"),
]

# ---------- 成员的剧本 ----------
MEMBERS = {
    "researcher": [
        lambda: call(("read_file", {"path": "data/app.log", "limit": 3})),
        # 收到 R1：认领 T1，然后按协议带 replyTo 答复。replyTo 用端点从上下文里抽到的真 id
        lambda: call(
            ("task_update", {"id": "T1", "status": "IN_PROGRESS", "owner": "researcher"}),
            ("send_message", {"to": "lead", "type": "response", "replyTo": state["cur_req_id"],
                              "text": "ISO 8601 带毫秒，例如 2026-11-21T09:14:05.881+08:00。"}),
        ),
        # 成员直接点名 indexer：这封信 lead 看不到
        lambda: call(("send_message", {"to": "indexer", "type": "request", "text": DIRECT_REQ})),
        # 读到 indexer 的 response，收尾 + 广播
        lambda: call(
            ("task_update", {"id": "T1", "status": "COMPLETED"}),
            ("send_message", {"to": "all", "type": "broadcast", "text": BROADCAST_TEXT}),
        ),
        lambda: text("日志时间戳是 ISO 8601 带毫秒（+08:00），T1 我标完成了，广播也发了。"),
    ],
    "indexer": [
        lambda: call(("read_file", {"path": "db/schema.sql"})),
        # 收到 H1：ACCEPT 才真正改 owner
        lambda: call(("task_update", {"id": "T2", "status": "ACCEPT"})),
        # 收到 researcher 的 R3：直接回给它，不经过 lead
        lambda: call(("send_message", {"to": "researcher", "type": "response",
                                       "replyTo": state["cur_req_id"], "text": DIRECT_RESP})),
        lambda: text("T2 通过移交接的，schema 的情况我直接回给 researcher 了。"),
    ],
    "slowhand": [
        lambda: call(("read_file", {"path": "data/app.log", "limit": 2})),
        # 收到 R2 但不理它——等下一轮的 sweep 来收尸
        lambda: call(("glob", {"pattern": "db/*.sql"})),
        # 第三步睡 950ms：把 R2 的年龄压过 PROTOCOL_TIMEOUT_MS=800
        lambda: (time.sleep(0.95), call(("read_file", {"path": "data/app.log", "limit": 1})))[1],
        # 孤儿应答：replyTo 指向一条不存在的 request，必须被拒收
        lambda: call(("send_message", {"to": "lead", "type": "response", "replyTo": "R999",
                                       "text": "补交：orders 表有 2 个索引。"})),
        lambda: text("slowhand 报到：忙别的去了，慢了半拍。"),
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


def dead_lines(name):
    p = os.path.join(TEAM_DIR, name, "dead.jsonl")
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def contents_of(msgs):
    """拼出所有 message 的原文（不做 JSON 转义），正则直接对原文抽信封 id"""
    out = []
    for m in msgs:
        c = m.get("content")
        if isinstance(c, str):
            out.append(c)
        elif isinstance(c, list):
            out.extend(str(x.get("text") or "") for x in c if isinstance(x, dict))
    return "\n".join(out)


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

        if role == "summarizer":
            self.reply(200, text("摘要：团队在按协议查日志格式和索引。"))
            return

        state["posts"] += 1
        idx = state["posts"]
        state["roles"].append(role)
        state["systems"].setdefault(role, system)
        state["messages_by_role"].setdefault(role, []).extend(msgs)

        # 这一 POST 的上下文里最后一条 request 信封的 id——成员答复就指它
        hits = REQ_RE.findall(contents_of(msgs))
        state["cur_req_id"] = hits[-1] if hits else ""

        # handoff 前后各拍一张 T2 的 owner
        t2 = read_tasks().get("T2")
        state["t2_owner_at"][idx] = t2.get("owner") if t2 else None

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
        if "孤儿应答 1 条被拒" in out:
            break
        time.sleep(0.2)

    if SCENARIO == "naive":
        finish_naive(out)
        return

    posts = state["posts_log"]
    rese = [p for p in posts if p["role"] == "researcher"]
    inde = [p for p in posts if p["role"] == "indexer"]

    rese_json = json.dumps(state["messages_by_role"].get("researcher", []), ensure_ascii=False)
    slow_json = json.dumps(state["messages_by_role"].get("slowhand", []), ensure_ascii=False)

    # 信封落盘：字段齐全
    lines = inbox_lines("researcher")
    req_line = next((l for l in lines if l.get("type") == "request" and l.get("from") == "lead"), None)
    fields_ok = req_line is not None and all(
        k in req_line for k in ("id", "from", "to", "at", "type", "payload", "replyTo", "deadline"))
    check("envelope_fields_on_disk", fields_ok,
          "(researcher 收件箱里那封 request 带齐 id/from/to/at/type/payload/replyTo/deadline 八个字段)")

    # id 在上下文里可见，收件方才知道拿什么填 replyTo
    req_id = req_line["id"] if req_line else ""
    marker = 'id=\\"%s\\" from=\\"lead\\" type=\\"request\\"' % req_id
    second_rese = json.dumps(rese[1]["messages"], ensure_ascii=False) if len(rese) > 1 else ""
    check("request_id_visible_in_context", marker in second_rese,
          "(researcher 第 2 步的上下文里能看到渲染出来的 envelope id=%s——replyTo 有处可抄)" % req_id)

    # request 发出后，收件方下一 tick 读到：POST 序号 +1
    lead_send = next(p["idx"] for p in posts
                     if p["role"] == "lead" and "日志最后一条 ERROR" in p["args"])
    first_hit = next((p["idx"] for p in rese
                      if req_id in json.dumps(p["messages"], ensure_ascii=False)), None)
    check("request_answered_next_tick", first_hit == lead_send + 1,
          "(第 %d 次 POST 发出 request，researcher 在第 %s 次 POST 读到——正好下一轮第一个请求)"
          % (lead_send, first_hit))

    # response 的 replyTo 指回 request 的 id，盘上对得上
    lead_in = inbox_lines("lead")
    resp_line = next((l for l in lead_in
                      if l.get("type") == "response" and l.get("from") == "researcher"), None)
    check("reply_to_matches_request_id",
          resp_line is not None and resp_line.get("replyTo") == req_id,
          "(lead 收件箱里那封 response 的 replyTo=%s，和原 request 的 id 逐字相同)"
          % (resp_line.get("replyTo") if resp_line else "(无)"))

    # 配对把 pending 清干净：R1 被答掉、R2 进死信，team_status 归零
    check("pairing_closes_request", "待答复 0 条，死信 1 条" in out,
          "(第 5 轮那份 team_status：待答复 0 条，死信 1 条——被答的摘了，超时的收了)")

    # 超时进死信，条数对得上
    dead = dead_lines("lead")
    check("timeout_dead_letter",
          len(dead) == 1 and dead[0].get("type") == "request"
          and "orders 表上现在有几个索引" in dead[0].get("payload", ""),
          "(.team/lead/dead.jsonl 恰好 1 行，就是那封没人答的 request)")
    check("dead_letter_in_status", "死信 1 条" in out,
          "(死信不是只有文件：team_status 的协议行写着 死信 1 条，lead 不用翻盘)")

    # handoff：accept 之前 owner 不变，accept 之后才变
    idx_accept = next(p["idx"] for p in inde if "ACCEPT" in p["args"])
    owner_before = state["t2_owner_at"].get(idx_accept)
    owner_after = state["t2_owner_at"].get(idx_accept + 1)   # 同 tick 里排在 indexer 后面的 slowhand
    check("handoff_owner_unchanged_before_accept", owner_before == "",
          "(indexer 带 ACCEPT 的 POST 那一刻，T2.json 的 owner 是 %r——信封到了，owner 没动)" % owner_before)
    check("handoff_accept_changes_owner", owner_after == "indexer",
          "(accept 执行完，同 tick 更晚的 POST 时 owner=%r——移交生效靠表态，不靠发信)" % owner_after)
    t2 = read_tasks().get("T2", {})
    check("handoff_final_owner", t2.get("owner") == "indexer" and t2.get("status") == "PENDING",
          "(T2 最终 owner=indexer、状态 %s——对方 accept 才接走，lead 没有直接写)" % t2.get("status"))

    # 不存在的任务移交被拒，错误串可见
    check("handoff_nonexistent_rejected", "没有任务 T9" in out,
          "(task_handoff(T9) 被拒：没有任务 T9——模型读得到这句)")

    # 成员直聊：A 发 B、B 答 A，落在各自收件箱，lead 的里没有
    inde_in = inbox_lines("indexer")
    r3 = next((l for l in inde_in if l.get("type") == "request" and l.get("from") == "researcher"), None)
    r3_resp = next((l for l in lines if l.get("type") == "response" and l.get("from") == "indexer"), None)
    check("direct_member_exchange",
          r3 is not None and r3_resp is not None and r3_resp.get("replyTo") == r3.get("id"),
          "(researcher → indexer 的 request 落在 indexer 收件箱，indexer 的 response replyTo=%s 指回它)"
          % (r3_resp.get("replyTo") if r3_resp else "(无)"))

    def body_of(l):
        return l.get("payload", "") + l.get("text", "")

    check("lead_cannot_see_direct",
          all(direct not in "".join(body_of(l) for l in lead_in)
              for direct in (DIRECT_REQ, DIRECT_RESP)),
          "(lead 收件箱里没有那对直聊信封的原文——lead 只看得到 broadcast 和自己的信)")

    # broadcast：除发件人外人人都收到（含 lead）
    def got_broadcast(name):
        return any(BROADCAST_TEXT[:12] in l.get("payload", "")
                   for l in inbox_lines(name) if l.get("type") == "broadcast")

    rese_bc = [l for l in lines if l.get("type") == "broadcast"]
    check("broadcast_reaches_all",
          got_broadcast("lead") and got_broadcast("indexer") and got_broadcast("slowhand")
          and not rese_bc,
          "(broadcast 落进 lead / indexer / slowhand 三份收件箱，发件人 researcher 自己没有)")

    # 孤儿应答：拒收，错误串回到 slowhand 的上下文，lead 的收件箱里没有 R999
    check("orphan_reply_rejected",
          "orphan_reply" in slow_json and "R999" not in json.dumps(lead_in, ensure_ascii=False),
          "(slowhand 的 replyTo=R999 被拒收：错误串回了它的上下文，lead 收件箱里没有这封信)")

    # 轮转顺序逐项对得上（indexer 第 4 步收工，最后一次 tick 只剩两人）
    expected = (["lead"] + ["researcher", "indexer", "slowhand", "lead"] * 4
                + ["researcher", "slowhand", "lead"])
    check("tick_round_robin_order", state["roles"] == expected,
          "(%d 次 POST 的顺序和轮转表逐项相同：6 轮 lead / 5 次 tick)" % len(state["roles"]))

    check("script_finished", True, "(剧情 6 轮跑完，%d 次 POST)" % state["posts"])
    write_log()


def finish_naive(out):
    lead_in = inbox_lines("lead")
    dead = dead_lines("lead")

    check("naive_dead_letter_zero", len(dead) == 0,
          "(没人清超时：.team/lead/dead.jsonl 不存在或 0 行——main 那一遍是 1 行)")
    check("naive_orphan_reply_delivered",
          any(l.get("type") == "response" and l.get("replyTo") == "R999" for l in lead_in),
          "(孤儿应答被当成正常回执投进 lead 收件箱——没人查 replyTo)")
    slow_json = json.dumps(state["messages_by_role"].get("slowhand", []), ensure_ascii=False)
    check("naive_no_rejection_seen", "orphan_reply" not in slow_json,
          "(slowhand 的上下文里没有拒收错误——它根本不知道自己发的是废信)")
    check("naive_unanswered_still_pending", "待答复 3 条，死信 0 条" in out,
          "(第 5 轮 team_status：被答的 + 被无视的全挂在账上——待答复 3 条，死信 0 条)")
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
