#!/usr/bin/env python3
"""Local fake LLM endpoint for the s11 error-recovery experiment.

这一篇要测的是「挂了之后怎么办」，所以端点得主动制造故障，而且要制造得可预期：

  第 1 次 POST  → 429 + Retry-After: 1    （限流，听服务端的）
  第 2 次 POST  → 503                     （临时故障，指数退避）
  第 3 次 POST  → 200 但响应体半截         （不是 HTTP 错误，是解析失败）
  轮到第 5 轮时 → 400 context_length_exceeded（上下文超长，要压缩后重发）

除这三处，还有两个由剧本制造的麻烦：工具报错（读一个不存在的文件）、
以及连续 5 次完全相同的调用（死循环）。

断言分两半：一半是端点自己看得见的（POST 间隔、重试请求体是不是同一份、messages 里的痕迹），
另一半只能从 JVM 的输出里看（[loop] 中止、[recover] 统计）。所以 finish_report 会去读
STDOUT_LOG 指向的文件——两边都对上，这一篇的结论才成立。

用法：
    SCENARIO=main  python3 fake-endpoint.py      # 正常跑一遍
    SCENARIO=fatal python3 fake-endpoint.py      # 401 那一遍，验证「不该重试的不重试」
"""
import hashlib
import json
import os
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8911"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s11_assert.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s11_stdout.log")
SCENARIO = os.environ.get("SCENARIO", "main")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"
_fatal_done = False

checks = []
state = {
    "posts": 0,
    "turns": 0,
    "times": [],
    "bodies": [],
    "saw_tool_error": False,
    "saw_loop_warning": False,
    "saw_not_executed": False,
    "overflow_served": False,
    "summarizer_requests": 0,
    "pairing_ok": True,
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


TODO = {"todos": [
    {"content": "记下接口错误返回的形状", "status": "in_progress"},
    {"content": "找到最慢的一条请求", "status": "pending"},
]}

MISSING = {"path": "data/missing.log"}

SCRIPT = [
    lambda: call("todo_write", TODO),
    lambda: call("read_file", MISSING),                            # 工具报错
    lambda: call("read_file", {"path": "data/slow.log"}),           # 改对了
    lambda: call("bash", {"command": "wc -l data/slow.log"}),       # 换个工具
    lambda: call("read_file", {"path": "data/slow.log", "limit": 3}),
    lambda: call("read_file", MISSING),                             # 死循环第 1 次
    lambda: call("read_file", MISSING),                             # 第 2 次
    lambda: call("read_file", MISSING),                             # 第 3 次 → 提醒
    lambda: call("read_file", MISSING),                             # 第 4 次
    lambda: call("read_file", MISSING),                             # 第 5 次 → 中止
]


def reset_state():
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    os.makedirs("data", exist_ok=True)
    try:
        os.remove("data/missing.log")
    except FileNotFoundError:
        pass
    rows = ["%02d %4dms /api/orders SELECT id FROM orders WHERE user_id = %d" % (i, 4300 - i * 7, 10000 + i)
            for i in range(1, 21)]
    with open("data/slow.log", "w") as f:
        f.write("\n".join(rows) + "\n")


def remember(msgs):
    """每条请求都顺手记一遍：工具错误、循环提醒、未执行标记、tool 配对"""
    for m in msgs:
        c = m.get("content") or ""
        if m.get("role") == "tool":
            if c.startswith("<tool-error"):
                state["saw_tool_error"] = True
            if c == "(not executed: repeated call detected)":
                state["saw_not_executed"] = True
        if m.get("role") == "user" and "<loop-warning>" in c:
            state["saw_loop_warning"] = True

    call_ids, tool_ids = set(), set()
    for m in msgs:
        for tc in (m.get("tool_calls") or []):
            call_ids.add(tc.get("id"))
        if m.get("role") == "tool":
            tool_ids.add(m.get("tool_call_id"))
    if call_ids and not call_ids.issubset(tool_ids):
        state["pairing_ok"] = False


def read_stdout():
    try:
        with open(STDOUT_LOG, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return ""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, payload, headers=None):
        data = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        raw = self.rfile.read(n) or b"{}"
        body = json.loads(raw)
        msgs = body.get("messages", [])
        sysmsg = next((m.get("content") or "" for m in msgs if m.get("role") == "system"), "")

        state["posts"] += 1
        state["times"].append(time.time())
        if state["posts"] <= 4:
            state["bodies"].append(hashlib.md5(raw).hexdigest())
        remember(msgs)

        if SUMMARIZER_MARK in sysmsg:
            state["summarizer_requests"] += 1
            print("   [fake] 收到摘要请求（第 %d 次压缩）" % state["summarizer_requests"], flush=True)
            self.reply(200, text("已完成的调用与结论：读了 data/slow.log 共 20 行；"
                                 "data/missing.log 不存在；待办两项，第一项进行中。"))
            return

        if SCENARIO == "fatal":
            # 401 一直给 401，什么时候停手是客户端的事——这样两种模式（分不分类）都能测
            self.reply(401, {"error": {"type": "authentication_error", "message": "invalid api key"}})
            threading.Thread(target=finish_fatal, daemon=True).start()
            return

        post = state["posts"]
        if post == 1:
            print("   [fake] POST 1 → 429（Retry-After: 1）", flush=True)
            self.reply(429, {"error": {"message": "rate limit"}}, {"retry-after": "1"})
            return
        if post == 2:
            print("   [fake] POST 2 → 503", flush=True)
            self.reply(503, {"error": {"message": "upstream unavailable"}})
            return
        if post == 3:
            print("   [fake] POST 3 → 200 但响应体半截（不是合法 JSON）", flush=True)
            self.reply(200, '{"choices":[{"message":{"role":"assistant","content":"半截'.encode())
            return

        # 第 5 轮之前插一次「上下文超长」。不是剧本的一轮，不消耗 turns
        if state["turns"] == 4 and not state["overflow_served"]:
            state["overflow_served"] = True
            print("   [fake] 注入 400 context_length_exceeded", flush=True)
            self.reply(400, {"error": {"code": "context_length_exceeded",
                                     "message": "maximum context length is 4096 tokens"}})
            return

        t = state["turns"]
        state["turns"] += 1
        payload = SCRIPT[t]() if t < len(SCRIPT) else text("done")
        print("   [fake] 第 %d 轮 → %s" % (t, payload["choices"][0]["message"].get("tool_calls", [{}])[0]
                                          .get("function", {}).get("name", "text")), flush=True)
        self.reply(200, payload)

        if state["turns"] >= len(SCRIPT):
            threading.Thread(target=finish_main, daemon=True).start()


def finish_main():
    """等 JVM 收尾（它会把 [recover] 统计打到 stdout），再对齐两边的事实"""
    out = ""
    for _ in range(160):
        out = read_stdout()
        if "[recover] 工具错误" in out:
            break
        time.sleep(0.25)

    times = state["times"]
    gaps = [times[i] - times[i - 1] for i in range(1, len(times))]
    g1 = gaps[0] if len(gaps) > 0 else 0     # 429 之后
    g2 = gaps[1] if len(gaps) > 1 else 0     # 503 之后
    g3 = gaps[2] if len(gaps) > 2 else 0     # 半截 JSON 之后

    bodies = state["bodies"]
    same_body = len(set(bodies[:3])) == 1 if len(bodies) >= 3 else False

    print("\n   [measure] POST 间隔：%.2fs（429 后） / %.2fs（503 后） / %.2fs（半截 JSON 后）"
          % (g1, g2, g3), flush=True)

    check("retry_after_honored", g1 >= 0.9,
          "(服务端说等 1s，实际 %.2fs——没有按自己的退避节奏走)" % g1)
    check("backoff_grows", g3 > g2,
          "(第二次 %.2fs → 第三次 %.2fs；等值抖动区间不重叠，所以一定更大)" % (g2, g3))
    check("bad_json_retried", len(bodies) >= 4,
          "(半截 JSON 没被当成「模型返回了空内容」，重发了一次)")
    check("retry_sends_identical_body", same_body,
          "(前三次 POST 的请求体 md5 相同：重试的是同一份 messages，没有重建)")
    check("tool_error_fed_back", state["saw_tool_error"],
          "(读不存在的文件后，tool 消息里出现 <tool-error>，模型下一轮换了路径)")
    check("tool_error_did_not_kill_loop", state["turns"] >= 3,
          "(工具报错后循环继续跑到第 %d 轮)" % state["turns"])
    check("overflow_recovered",
          state["overflow_served"] and state["summarizer_requests"] >= 1 and state["turns"] >= len(SCRIPT),
          "(400 超长 → 压缩 %d 次 → 同一条请求重发成功)" % state["summarizer_requests"])
    check("loop_warning_injected", "[loop] 连续 3 次相同调用" in out,
          "(第 3 次相同调用后注入了提醒，没等它自己醒)")
    check("loop_warning_reached_model", state["saw_loop_warning"],
          "(提醒是 user 消息，下一轮请求里能看到——不是只打印在日志里)")
    check("loop_stopped_at_five", "[loop] 连续 5 次相同调用，中止" in out,
          "(第 5 次不再执行，直接收尾)")
    check("not_executed_marked", "[loop] 这次调用不执行" in out,
          "(被中止的那次调用仍回了一条 tool 消息：配对不能坏)")
    check("tool_pairing_intact", state["pairing_ok"],
          "(全部请求里 assistant.tool_calls 的 id 都有对应的 tool 消息)")
    check("retry_reported", "[retry] 共重试 3 次" in out,
          "(三次失败都记账了：429 / 503 / 半截 JSON)")
    check("script_finished", state["turns"] >= len(SCRIPT), "(剧本 %d 轮跑完)" % len(SCRIPT))
    dump("main")


def finish_fatal():
    """等客户端自己放弃（stdout 出现「✗ 放弃」），再断言它发了几次"""
    global _fatal_done
    if _fatal_done:
        return
    _fatal_done = True
    out = ""
    for _ in range(160):
        out = read_stdout()
        if "✗ 放弃" in out:
            break
        time.sleep(0.25)

    naive = os.environ.get("RETRY_MODE", "sorted") == "naive"
    expect = 4 if naive else 1
    check("fatal_stopped_at_expected", state["posts"] == expect,
          "(发了 %d 次就停手，预期 %d 次：%s)" % (state["posts"], expect,
                                                "反例，无分类所以重试到上限" if naive else "分类后只发一次"))
    if naive:
        check("naive_reports_wrong_reason", "重试 4 次仍失败" in out and "invalid api key" not in out.split("✗")[-1],
              "(报「重试 4 次仍失败」，真正的原因不在最后一句话里)")
    else:
        check("fatal_reported_as_fatal", "✗ 放弃：FATAL" in out,
              "(报的是 FATAL，不是「重试 4 次仍失败」)")
        check("fatal_kept_reason", "401" in out and "invalid api key" in out,
              "(401 与 invalid api key 原样保留在输出里)")
    dump("naive" if naive else "fatal")


def dump(tag):
    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d TURNS=%d\n" % (tag, state["posts"], state["turns"]))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s)" % (PORT, SCENARIO), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
