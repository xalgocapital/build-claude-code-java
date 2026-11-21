#!/usr/bin/env python3
"""Local fake LLM endpoint for the s08 context-compaction experiment.

固定剧本，验证机制而不是模型质量：
  1. todo_write                     先列计划（s05 的机制，避免提醒噪音）
  2. skill("sql-review")            加载一份技能正文（s07 的机制，正文会一直留在上下文里）
  3-7. read_file(data/slow-N.log)   五份慢查询日志，把上下文顶到预算之上
  8. todo_write                     更新进度（压缩就发生在这前后）
  9. write_file(notes/verdict.txt)  把结论落盘
 10. 纯文本收尾

另外专门接一类请求：压缩器发来的「摘要」调用（没有 tools、system 里带标记），
返回固定摘要文本，不消耗剧本轮次。

每个请求都顺手查一遍 tool_call_id 配对——这是压缩最容易弄断的东西。
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8908"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s08_assert.txt")

SUMMARY_MARK = "你在压缩一段 Agent 会话"
# 上下文里「这是一条压缩摘要」的标记。注意不能拿 <summary> 判断：
# s08 的 system prompt 里也提到了 <summary> 这个词
SUMMARY_BLOCK_MARK = "条消息已压缩移除"
SUMMARY_TEXT = ("S08_SUMMARY: 目标=挑出最慢的三条 SQL 给索引建议；"
                "已完成=读过 slow-1..slow-3，最慢的是 /api/orders 的 4200ms；"
                "下一步=读剩下两份日志，写 notes/verdict.txt")

checks = []
state = {
    "pairing_violations": 0,
    "requests": 0,
    "summary_requests": 0,
    "post_compact_seen": False,
    "max_before": 0,
}


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print("   [assert] %s = %s %s" % (name, "YES" if ok else "NO", detail), flush=True)


def call(name, args):
    return {
        "choices": [{
            "message": {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_%d" % len(checks),
                    "type": "function",
                    "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)},
                }],
            },
            "finish_reason": "tool_calls",
        }]
    }


def text(msg):
    return {"choices": [{"message": {"role": "assistant", "content": msg}, "finish_reason": "stop"}]}


TODO_V1 = {"todos": [
    {"content": "读 data 下五份慢查询日志", "status": "in_progress"},
    {"content": "挑出最慢的三条 SQL", "status": "pending"},
    {"content": "按 sql-review 规范给索引建议", "status": "pending"},
    {"content": "结论写到 notes/verdict.txt", "status": "pending"},
]}

TODO_V2 = {"todos": [
    {"content": "读 data 下五份慢查询日志", "status": "completed"},
    {"content": "挑出最慢的三条 SQL", "status": "in_progress"},
    {"content": "按 sql-review 规范给索引建议", "status": "pending"},
    {"content": "结论写到 notes/verdict.txt", "status": "pending"},
]}

SCRIPT = [
    lambda msgs, sysmsg: call("todo_write", TODO_V1),
    lambda msgs, sysmsg: call("skill", {"name": "sql-review"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/slow-1.log"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/slow-2.log"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/slow-3.log"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/slow-4.log"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/slow-5.log"}),
    lambda msgs, sysmsg: call("todo_write", TODO_V2),
    lambda msgs, sysmsg: call("write_file", {
        "path": "notes/verdict.txt",
        "content": "最慢三条：\n1. /api/orders 4200ms —— 缺 (user_id, created_at) 联合索引\n"
                   "2. /api/feed 2800ms —— LIKE '%kw%' 前缀通配，改全文检索\n"
                   "3. /api/report 1900ms —— 大表 count(*)，上预聚合",
    }),
    lambda msgs, sysmsg: text("日志过完了，最慢的三条 SQL 和索引建议已写到 notes/verdict.txt。"),
]


def last_tool(msgs):
    for m in reversed(msgs):
        if m.get("role") == "tool":
            return m.get("content") or ""
    return ""


def tokens(s):
    """和 Compactor.tokens() 同一套粗估：汉字算 1，英文单词算 1"""
    cjk, words, in_word = 0, 0, False
    for ch in s:
        if "\u4e00" <= ch <= "\u9fff":
            cjk += 1
            in_word = False
        elif ch.isalnum():
            if not in_word:
                words += 1
                in_word = True
        else:
            in_word = False
    return cjk + words


def estimate(msgs):
    total = 0
    for m in msgs:
        total += tokens(m.get("content") or "")
        for c in m.get("tool_calls") or []:
            fn = c.get("function") or {}
            total += tokens(fn.get("name") or "") + tokens(fn.get("arguments") or "")
    return total


def pairing_violations(msgs):
    """OpenAI 协议硬约束：每条 role=tool 的消息，前面必须有对应 id 的 assistant.tool_calls"""
    seen = set()
    bad = 0
    for m in msgs:
        if m.get("role") == "assistant":
            for c in m.get("tool_calls") or []:
                seen.add(c.get("id"))
        elif m.get("role") == "tool":
            if m.get("tool_call_id") not in seen:
                bad += 1
    return bad


def write_data():
    """生成五份慢查询日志。内容和断言标记绑在一起，方便判断「最近几条还在不在」"""
    os.makedirs("data", exist_ok=True)
    for i in range(1, 6):
        rows = []
        for j in range(1, 26):
            ms = 4000 - i * 60 - j * 10
            api = ["/api/orders", "/api/feed", "/api/report", "/api/cart", "/api/search"][j % 5]
            rows.append(
                "2026-11-20T10:%02d:%02dZ %4dms %-12s "
                "SELECT id, user_id, total FROM orders WHERE user_id = %d "
                "AND created_at > '2026-11-01' ORDER BY created_at DESC LIMIT 50;"
                % (i, j, max(ms, 30), api, 10000 + j)
            )
        rows.append("FILE-%d-END 共 25 条慢查询，最慢 %dms" % (i, 3400 - i * 60))
        with open("data/slow-%d.log" % i, "w") as f:
            f.write("\n".join(rows) + "\n")


def judge_summary_request(msgs, sysmsg):
    """压缩器发来的摘要调用：没有 tools，system 里带标记"""
    state["summary_requests"] += 1
    n = state["summary_requests"]
    user = next((m.get("content") or "" for m in msgs if m.get("role") == "user"), "")
    if n == 1:
        check("summary_request_has_goal", "原始目标" in sysmsg and "慢查询日志" in sysmsg,
              "(摘要请求锚定了原始目标，摘要才不会写成流水账)")
        check("summary_request_clipped", "(截断)" in user,
              "(工具结果先截断再送摘要，请求本身不会和原文一样大)")
        check("summary_request_no_tools", True, "(摘要调用不带工具表，模型只能回文本)")
    if n == 2:
        check("summary_recursive", "S08_SUMMARY" in user,
              "(第二次压缩的输入里带着第一次的摘要——摘要会被再摘要)")


class Handler(BaseHTTPRequestHandler):
    turn = 0
    finished = False

    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        msgs = body.get("messages", [])
        sysmsg = next((m.get("content") or "" for m in msgs if m.get("role") == "system"), "")
        tools = body.get("tools") or []
        state["requests"] += 1

        # 每个请求都查一遍配对：这是 API 层的硬约束，压缩不能把它弄断
        bad = pairing_violations(msgs)
        state["pairing_violations"] += bad
        if bad:
            print("   [assert] !! 本次请求有 %d 条 tool 消息找不到父 tool_calls" % bad, flush=True)

        if not tools and SUMMARY_MARK in sysmsg:
            judge_summary_request(msgs, sysmsg)
            payload = text(SUMMARY_TEXT)
        else:
            payload = self.judge_turn(msgs, sysmsg)

        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

        if Handler.turn >= len(SCRIPT) and not Handler.finished:
            Handler.finished = True
            self.finish_report()

    def judge_turn(self, msgs, sysmsg):
        t = Handler.turn
        Handler.turn += 1
        joined = "\n".join((m.get("content") or "") for m in msgs)
        size = estimate(msgs)

        # 第一次看见 <summary>：这时候 messages 里应该是「摘要 + 活状态 + 最近几轮」
        if not state["post_compact_seen"] and SUMMARY_BLOCK_MARK in joined:
            state["post_compact_seen"] = True
            check("summary_in_context", SUMMARY_TEXT[:20] in joined, "(摘要正文顶替了被删的那段)")
            check("goal_still_first",
                  msgs[1].get("role") == "user" and "慢查询日志" in (msgs[1].get("content") or ""),
                  "(原始目标还在第 2 条，没被压掉)")
            check("live_state_reinjected", "<live-state>" in joined and "当前待办清单" in joined,
                  "(待办清单重新贴回去了)")
            check("loaded_skill_reminder", "已加载技能：sql-review" in joined,
                  "(技能正文被压掉时，提醒模型重新取)")
            tail = last_tool(msgs)
            check("recent_kept", "FILE-" in tail and len(tail) > 1000,
                  "(最近一轮工具结果原样保留，没被截断)")
            check("size_shrunk", size < state["max_before"],
                  "(%d < 压缩前最大 %d)" % (size, state["max_before"]))
            third = msgs[2] if len(msgs) > 2 else {}
            check("tail_not_started_by_tool",
                  third.get("role") != "tool" and SUMMARY_BLOCK_MARK in (third.get("content") or ""),
                  "(摘要后面紧跟的不是孤儿 tool 消息)")
        state["max_before"] = max(state["max_before"], size)

        return SCRIPT[t](msgs, sysmsg) if t < len(SCRIPT) else text("done")

    def finish_report(self):
        check("tool_call_pairing_intact", state["pairing_violations"] == 0,
              "(全程 %d 个请求，出现 %d 次孤立 tool 消息)"
              % (state["requests"], state["pairing_violations"]))
        check("compaction_happened", state["post_compact_seen"], "(至少压过一次)")
        check("summary_requests_at_least_2", state["summary_requests"] >= 2,
              "(压了 %d 次)" % state["summary_requests"])
        with open(LOG, "w") as f:
            for name, ok, detail in checks:
                f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
            f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
        print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    write_data()
    print("fake LLM listening on http://127.0.0.1:%d/v1" % PORT, flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
