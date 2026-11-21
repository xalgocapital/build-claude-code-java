#!/usr/bin/env python3
"""Local fake LLM endpoint for the s17 autonomous-driver experiment.

s16 的端点扮演「一个讲协议的团队」；这一篇扮演「一个被驱动起来的人手」。
端点靠 system 分辨两种角色：
    lead    —— 主循环。只做两件事：把目标交出去，然后看结果
    worker  —— 自主驱动每一轮开出来的独立会话。它只知道一条任务

四个场景，各验一件事：
    SCENARIO=main     # 挑活按 priority、自检打回、连续 3 轮无进展停在 R7，16 项断言
    SCENARIO=rounds   # 配 AUTONOMY_MAX_ROUNDS=5，看回合预算先到
    SCENARIO=tokens   # 配 AUTONOMY_MAX_TOKENS=700，看 token 预算先到
    SCENARIO=naive    # 配 AUTONOMY_MODE=naive，去掉停滞判据，只靠回合上限兜底

断言只读三样东西：每次请求的 messages、JVM stdout、.tasks/ 和 notes/ 下的文件。
naive 那遍还要读 main 那遍留下的 /tmp/s17_run_main.json 做对比——所以先跑 main。
"""
import json
import os
import re
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8917"))
SCENARIO = os.environ.get("SCENARIO", "main")
LOG = os.environ.get("ASSERT_LOG", "/tmp/s17_assert%s.txt" % ("" if SCENARIO == "main" else "_" + SCENARIO))
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s17_stdout.log")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")
TEAM_DIR = os.environ.get("TEAM_DIR", ".team")
WORK_DIR = os.environ.get("AUTONOMY_WORKSPACE", "notes")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"
LEAD_MARK = "You are the lead of a small agent team"
EXEC_MARK = "You are the executor for one task"
USER_TASK_RE = re.compile(r"任务 (T\d+)（p(\d+)）")

DONE_TEXT = "驱动停了，原因和轮次看上面那行 status。任务板我没动，还是驱动留下的样子。"

checks = []
state = {
    "posts": 0,
    "lead_idx": 0,
    "roles": [],
    "worker_steps": {},      # (task, step) → 第几次
    "worker_first": {},      # task → 第一次 POST 的 messages 条数
    "systems": {},
    "posts_log": [],
}

TASKS = [
    # id, subject, priority
    ("T1", "补 T1 的记录：日志时间戳格式说明", 2),
    ("T2", "补 T2 的记录：orders 表索引变更", 5),
    ("T3", "补 T3 的记录：回滚步骤", 5),
    ("T4", "写收尾总结（T4.md）", 1),
]


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


# ---------- lead 的剧本：交目标、看结果 ----------
LEAD = [
    lambda: call(("autonomy_start", {
        "goal": "把 notes/ 下每条任务的记录补齐，每条都要过自检"})),
    lambda: call(("autonomy_status", {}), ("autonomy_stop", {})),
    lambda: text(DONE_TEXT),
]

# ---------- worker 的剧本：一条任务一份 ----------
WORKERS = {
    # 两条高优先级的活：写文件 → 收工。写完自检就过
    "T2": [
        lambda: call(("write_file", {
            "path": "notes/T2.md",
            "content": "T2 orders 表索引变更\n- 新增 idx_orders_status\n- 影响：按状态查的列表页\n"})),
        lambda: text("建了 notes/T2.md，写了 T2 的索引变更和影响面。"),
    ],
    "T3": [
        lambda: call(("write_file", {
            "path": "notes/T3.md",
            "content": "T3 回滚步骤\n1. drop index idx_orders_status\n2. 重新部署上一版\n"})),
        lambda: text("建了 notes/T3.md，写了两步回滚。"),
    ],
    # 卡住的那条：只看别人怎么写的，自己一个字没落盘
    "T1": [
        lambda: call(("read_file", {"path": "notes/T2.md"})),
        lambda: text("看过 T2 的写法了，T1 这条我想不出写什么，没落盘。"),
    ],
    "T4": [
        lambda: call(("read_file", {"path": "notes/T2.md"})),
        lambda: text("收尾总结要等前面的都写完，我先不动。"),
    ],
}

WORKER_TEXT = {
    "T1": "T1 没东西可写，我什么都没改。",
}


def reset_state():
    for d in (TASK_DIR, TEAM_DIR, WORK_DIR):
        shutil.rmtree(d, ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    os.makedirs(TASK_DIR, exist_ok=True)
    os.makedirs(WORK_DIR, exist_ok=True)
    for tid, subject, priority in TASKS:
        task = {"id": tid, "subject": subject, "priority": priority, "status": "PENDING",
                "blockedBy": [], "owner": "", "createdAt": "12-08 09:00:00",
                "updatedAt": "12-08 09:00:00", "note": ""}
        with open(os.path.join(TASK_DIR, tid + ".json"), "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False, indent=2)
    with open(os.path.join(TASK_DIR, "seq"), "w") as f:
        f.write(str(len(TASKS)))


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


def contents_of(msgs):
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
    if EXEC_MARK in system:
        return "worker"
    return "unknown"


def worker_step(msgs):
    """一次 worker 会话里走到第几步：数它已经吐过几条 assistant 消息。
    跨多次尝试也成立——每次尝试都从 [system, user] 重新开始数。"""
    return sum(1 for m in msgs if m.get("role") == "assistant")


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
            self.reply(200, text("摘要：自主驱动在按优先级补记录。"))
            return

        state["posts"] += 1
        idx = state["posts"]
        state["roles"].append(role)
        state["systems"].setdefault(role, system)

        if role == "lead":
            i = state["lead_idx"]
            state["lead_idx"] += 1
            payload = text("done") if i >= len(LEAD) else LEAD[i]()
        elif role == "worker":
            m = USER_TASK_RE.search(contents_of(msgs))
            task = m.group(1) if m else "?"
            step = worker_step(msgs)
            state["worker_first"].setdefault(task, len(msgs))
            script = WORKERS.get(task, [])
            payload = text("done") if step >= len(script) else script[step]()
            state["worker_steps"][(task, step)] = state["worker_steps"].get((task, step), 0) + 1
            print("   [fake] POST %d worker %s 第 %d 步" % (idx, task, step), flush=True)
        else:
            payload = text("done")

        calls = payload["choices"][0]["message"].get("tool_calls") or []
        names = [c["function"]["name"] for c in calls]
        state["posts_log"].append({"idx": idx, "role": role, "messages": msgs,
                                   "system": system, "returns": names,
                                   "args": json.dumps([c["function"]["arguments"] for c in calls],
                                                      ensure_ascii=False)})
        if role == "lead":
            print("   [fake] POST %d lead 第 %d 步 → %s"
                  % (idx, state["lead_idx"] - 1, ",".join(names) or "text"), flush=True)
        self.reply(200, payload)

        if role == "lead" and state["lead_idx"] >= len(LEAD):
            threading.Thread(target=finish, daemon=True).start()


# ---------- 从 stdout 里读驱动的账 ----------

def parse_rounds(out):
    """每轮一行：R3 T1 记账：状态 PENDING→IN_PROGRESS，快照 ±0，note 变了 → 有进展"""
    rows = []
    for m in re.finditer(r"\[autonomy\] R(\d+) (T\d+) 记账：状态 (\w+)→(\w+)( 未变)?，"
                         r"快照 ([+-]?\d+|±0)，note (变了|没变)(?: → (有进展|无进展（连续 (\d+) 轮）))?", out):
        rows.append({
            "round": int(m.group(1)), "task": m.group(2), "from": m.group(3), "to": m.group(4),
            "delta": m.group(6), "note": m.group(7),
            "progress": m.group(8) == "有进展",
            "stall": int(m.group(9)) if m.group(9) else 0,
        })
    return rows


def parse_picks(out):
    picks = []
    for m in re.finditer(r"\[autonomy\] R(\d+) 候选 (\d+) 条：([^→]*) → 挑 (T\d+)", out):
        picks.append({"round": int(m.group(1)), "count": int(m.group(2)),
                      "order": m.group(3).strip(), "picked": m.group(4)})
    return picks


def parse_summary(out):
    m = re.search(r"\[autonomy\] 停：(\w+)｜.*?跑了 (\d+) 轮，估算 (\d+) tokens，完成 (\d+) 条，"
                  r"自检打回 (\d+) 次", out)
    if not m:
        return None
    return {"reason": m.group(1), "rounds": int(m.group(2)), "tokens": int(m.group(3)),
            "completed": int(m.group(4)), "bounces": int(m.group(5))}


def parse_status(out):
    m = re.search(r"autonomy 停着｜目标「[^」]*」｜轮次 R(\d+)/(\d+)｜tokens (\d+)/(\d+)"
                  r"（还剩 (\d+)）｜连续无进展 (\d+)/(\d+) 轮｜完成 (\d+) 条，打回 (\d+) 次"
                  r"｜停因 (\w+)", out)
    if not m:
        return None
    return {"round": int(m.group(1)), "max_rounds": int(m.group(2)),
            "tokens": int(m.group(3)), "max_tokens": int(m.group(4)), "left": int(m.group(5)),
            "stall": int(m.group(6)), "stall_max": int(m.group(7)),
            "completed": int(m.group(8)), "bounces": int(m.group(9)), "reason": m.group(10)}


def save_run(summary, rounds):
    path = "/tmp/s17_run_%s.json" % SCENARIO
    with open(path, "w") as f:
        json.dump({"scenario": SCENARIO, "summary": summary, "rounds": rounds}, f, ensure_ascii=False)


def finish():
    out = ""
    for _ in range(300):
        out = read_stdout()
        if DONE_TEXT in out and "自主驱动停了" in out:
            break
        time.sleep(0.2)

    if SCENARIO == "naive":
        finish_naive(out)
        write_log()
        return
    if SCENARIO == "rounds":
        finish_rounds(out)
        write_log()
        return
    if SCENARIO == "tokens":
        finish_tokens(out)
        write_log()
        return
    finish_main(out)
    write_log()


def finish_main(out):
    tasks = read_tasks()
    rounds = parse_rounds(out)
    picks = parse_picks(out)
    summary = parse_summary(out)
    status = parse_status(out)
    if summary:
        save_run(summary, rounds)

    check("priority_persisted",
          all(tasks.get(t, {}).get("priority") == p for t, _, p in TASKS),
          "(四个任务文件都带 priority：T2/T3=5、T1=2、T4=1——挑活有据可依，不靠文件名顺序)")

    check("pick_highest_first", bool(picks) and picks[0]["picked"] == "T2",
          "(R1 的候选是 %s，挑走 %s——priority 最大的先做)"
          % (picks[0]["order"] if picks else "?", picks[0]["picked"] if picks else "?"))

    check("tie_broken_by_id",
          len(picks) > 1 and picks[1]["picked"] == "T3",
          "(R2 挑 %s：T2 和 T3 都是 p5，按 id 升序，T3 排在 T4 前面)"
          % (picks[1]["picked"] if len(picks) > 1 else "?"))

    ordered = []
    for token in (picks[0]["order"].split() if picks else []):
        m = re.match(r"T(\d+)\(p(\d+)\)", token)
        if m:
            ordered.append((-int(m.group(2)), int(m.group(1))))
    check("candidate_order_sorted", len(ordered) >= 4 and ordered == sorted(ordered),
          "(R1 候选串 %s 按 priority 降序、id 升序——顺序是代码排的)"
          % (picks[0]["order"] if picks else "?"))

    lead_system = state["systems"].get("lead", "")
    worker_first_msgs = [p for p in state["posts_log"] if p["role"] == "worker"]
    isolated = (bool(worker_first_msgs)
                and worker_first_msgs[0]["system"].startswith("You are the executor")
                and LEAD_MARK not in worker_first_msgs[0]["system"]
                and len(worker_first_msgs[0]["messages"]) == 2)
    check("worker_context_isolated", isolated,
          "(worker 第 1 次 POST 只有 2 条 messages：system + 一条任务，lead 的上下文一条没带过去)")

    check("verify_pass_completes",
          tasks.get("T2", {}).get("status") == "COMPLETED"
          and tasks.get("T3", {}).get("status") == "COMPLETED",
          "(T2/T3 盘上是 COMPLETED，note 写着「自检通过」)")

    t1 = tasks.get("T1", {})
    check("verify_fail_bounces",
          t1.get("status") == "IN_PROGRESS" and t1.get("owner") == "autonomy"
          and "自检失败" in t1.get("note", ""),
          "(T1 被自检打回 IN_PROGRESS，owner=autonomy，note=%r)" % t1.get("note", "")[:40])

    check("bounce_note_has_reason", "T1.md" in t1.get("note", ""),
          "(note 里带着命令自己的输出：%r)" % t1.get("note", "")[:60])

    repicked = [p for p in picks if p["round"] >= 4 and p["picked"] == "T1"]
    check("failed_task_repicked", len(repicked) >= 2,
          "(R4、R5、R6 又挑到 T1——打回的任务还在池子里，下一轮还会被捡起来)")

    r3 = next((r for r in rounds if r["round"] == 3), None)
    check("first_fail_counts_as_progress", bool(r3) and r3["progress"],
          "(R3 是第一次自检失败：状态 PENDING→IN_PROGRESS，算一次有进展——刚开干不会被判停滞)")

    stalled = [r for r in rounds if not r["progress"]]
    check("stall_counts_to_three",
          len(stalled) == 3 and stalled[-1]["stall"] == 3,
          "(无进展的是 R%s，最后一轮 streak=3)"
          % ",".join(str(r["round"]) for r in stalled))

    check("stall_stops_at_r7", "连续 3 轮无进展，停在 R7" in out,
          "(日志原文：连续 3 轮无进展，停在 R7（第 7 轮没有开始）)")

    check("stall_reason_in_summary",
          bool(summary) and summary["reason"] == "stall" and summary["rounds"] == 6,
          "(收尾：%s)" % (json.dumps(summary, ensure_ascii=False) if summary else "没读到"))

    check("status_shows_budget_and_streak",
          bool(status) and status["round"] == 6 and status["max_rounds"] == 12
          and status["max_tokens"] == 60000 and status["stall"] == 3 and status["left"] > 0,
          "(status：R%d/%d，tokens %d/%d 还剩 %d，连续无进展 %d/%d)"
          % (status["round"], status["max_rounds"], status["tokens"], status["max_tokens"],
             status["left"], status["stall"], status["stall_max"]) if status else "没读到 status")

    check("stop_after_finish_says_stopped", "驱动已经停了（stall" in out,
          "(停完再调 autonomy_stop：它说驱动已经停了，不假装还在跑)")

    check("script_finished", True,
          "(%d 次 POST：lead %d 次、worker %d 次)"
          % (state["posts"], state["roles"].count("lead"), state["roles"].count("worker")))


def finish_rounds(out):
    summary = parse_summary(out)
    rounds = parse_rounds(out)
    save_run(summary, rounds)
    check("max_rounds_stops_at_5", "回合上限 MAX_ROUNDS=5 用完，停在 R5" in out,
          "(回合预算先到：日志写「停在 R5」，第 6 轮没有开始)")
    check("rounds_stop_not_stall",
          bool(summary) and summary["reason"] == "rounds" and summary["rounds"] == 5,
          "(收尾：%s——这时候停滞才数到 2，是预算先到的)"
          % (json.dumps(summary, ensure_ascii=False) if summary else "没读到"))


def finish_tokens(out):
    summary = parse_summary(out)
    rounds = parse_rounds(out)
    save_run(summary, rounds)
    m = re.search(r"token 预算先用完：累计估算 (\d+) 越过 MAX_TOKENS=(\d+)，停在 R(\d+)", out)
    check("token_budget_stops_early",
          bool(summary) and summary["reason"] == "tokens" and summary["rounds"] <= 3,
          "(token 预算先到：跑了 %s 轮就停（上限 %s）)"
          % (summary["rounds"] if summary else "?", m.group(2) if m else "?"))
    check("token_reason_names_budget",
          bool(m) and summary is not None and int(m.group(1)) >= int(m.group(2)),
          "(日志把账写清楚：估算 %s 越过上限 %s，停在 R%s)"
          % (m.group(1), m.group(2), m.group(3)) if m else "没读到 token 那行")


def finish_naive(out):
    summary = parse_summary(out)
    rounds = parse_rounds(out)
    save_run(summary, rounds)
    try:
        with open("/tmp/s17_run_main.json", encoding="utf-8") as f:
            main = json.load(f)
    except FileNotFoundError:
        check("naive_needs_main_run", False, "(先跑 SCENARIO=main，naive 要拿它做对比)")
        return

    m, mn = summary or {}, main["summary"]
    spins = [r for r in rounds if not r["progress"]]
    after = min((r["round"] for r in spins), default=0)

    check("naive_runs_to_max_rounds",
          bool(m) and m["reason"] == "rounds" and m["rounds"] == 12,
          "(没有停滞判据，只有回合上限拉得住：跑了 %d 轮，main 那遍是 %d 轮)"
          % (m.get("rounds", -1), mn["rounds"]))

    check("naive_spins_after_round3",
          after == 4 and len(spins) == 9,
          "(R%d 之后 %d 轮全是原地转——同样的任务、同样的失败、同样的 note)"
          % (after - 1, len(spins)))

    check("naive_burns_more_tokens",
          bool(m) and m["tokens"] > mn["tokens"],
          "(token %d vs main %d，多烧 %d；多跑 %d 轮)"
          % (m.get("tokens", -1), mn["tokens"], m.get("tokens", 0) - mn["tokens"],
             m.get("rounds", 0) - mn["rounds"]))

    check("naive_no_stall_line", "无进展，停在" not in out,
          "(日志里没有那句「连续 3 轮无进展，停在 R…」——它一直在跑，没人喊停)")


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
