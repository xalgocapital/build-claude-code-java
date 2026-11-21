#!/usr/bin/env python3
"""Local fake LLM endpoint for the s13 background-task experiment.

这一篇测的不是模型，是「命令跑着的时候，循环在干什么」。所以剧本只做一件事：
起一个要跑三秒的后台命令，然后在它跑的过程中让模型干别的，再分两次读它的输出。

两个场景：
    SCENARIO=main    # 通知走 messages
    SCENARIO=naive   # 通知走 system（反例：每次任务结束，前缀作废）

断言只看端点看得见的事实：请求里的 messages / system、POST 的间隔、磁盘上的 .bg/*.json。
JVM 的 stdout 只在需要时读一眼，不作为主要依据。
"""
import hashlib
import json
import os
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8913"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s13_assert.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s13_stdout.log")
SCENARIO = os.environ.get("SCENARIO", "main")
BG_DIR = os.environ.get("BG_DIR", ".bg")
BG_NOTICE = os.environ.get("BG_NOTICE", "messages")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"

checks = []
state = {
    "posts": 0,
    "turns": 0,
    "systems": [],
    "system_md5": [],
    "post_ms": [],
    "requests": [],      # 每个请求的 messages 快照
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
                    "id": "call_%d" % state["turns"],
                    "type": "function",
                    "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)},
                }],
            },
            "finish_reason": "tool_calls",
        }]
    }


def text(msg):
    return {"choices": [{"message": {"role": "assistant", "content": msg}, "finish_reason": "stop"}]}


SCRIPT = [
    # 0 起一个 3 秒的后台任务
    lambda: call("bash_bg", {"command": "for i in 1 2 3 4 5 6; do echo line-$i; sleep 0.5; done",
                             "timeoutSec": 30}),
    # 1 立刻做别的事：这一步必须很快返回，否则说明循环被挡住了
    lambda: call("read_file", {"path": "README.md", "limit": 3}),
    # 2 熬一点时间，让任务吐出前几行
    lambda: call("bash", {"command": "sleep 0.9"}),
    # 3 第一次读：应该只拿到当时已经有的行
    lambda: call("bash_output", {"id": "bg_1"}),
    # 4 再熬一会儿，让任务跑完（总时长 3s）
    lambda: call("bash", {"command": "sleep 2.6"}),
    # 5 第二次读：只该拿到新增的行，且这一轮请求前应该有完成通知
    lambda: call("bash_output", {"id": "bg_1"}),
    # 6 非零退出码
    lambda: call("bash_bg", {"command": "echo boom; exit 3", "timeoutSec": 30}),
    lambda: call("bash", {"command": "sleep 0.6"}),
    # 8 读它：输出 boom，状态带 exit 3
    lambda: call("bash_output", {"id": "bg_2"}),
    # 9 超时：1 秒杀一个 sleep 30
    lambda: call("bash_bg", {"command": "sleep 30", "timeoutSec": 1}),
    lambda: call("bash", {"command": "sleep 1.6"}),
    # 11 这一轮 poll 应该发现它超时并杀掉
    lambda: call("bash_output", {"id": "bg_3"}),
    # 12 手动 kill
    lambda: call("bash_bg", {"command": "sleep 30", "timeoutSec": 60}),
    lambda: call("kill_task", {"id": "bg_4"}),
    lambda: call("bash_output", {"id": "bg_4"}),
    # 15 一次吐 200 行，中途不读
    lambda: call("bash_bg", {"command": "seq 1 200", "timeoutSec": 30}),
    lambda: call("bash", {"command": "sleep 0.8"}),
    # 17 收的时候只回末尾 40 行
    lambda: call("bash_output", {"id": "bg_5"}),
    lambda: text("测试在后台跑完了，退出码 3 那个是我故意造的；超时和手动 kill 都记下来了。"),
]


def reset_state():
    shutil.rmtree(BG_DIR, ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")


def read_stdout():
    try:
        with open(STDOUT_LOG, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return ""


def read_meta(name):
    path = os.path.join(BG_DIR, name + ".json")
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def alive(pid):
    if not pid:
        return False
    try:
        os.kill(int(pid), 0)
        return True
    except OSError:
        return False


def md5(s):
    return hashlib.md5(s.encode("utf-8")).hexdigest()[:8]


def lcp(a, b):
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def flatten(messages):
    """把累计的 messages 摊平成 (tool 名, 参数, 结果) 列表，按 tool_call_id 配对。"""
    calls, results, order = {}, {}, []
    for m in messages:
        for c in (m.get("tool_calls") or []):
            cid = c.get("id")
            fn = c.get("function") or {}
            try:
                args = json.loads(fn.get("arguments") or "{}")
            except json.JSONDecodeError:
                args = {}
            calls[cid] = (fn.get("name"), args)
            order.append(cid)
        if m.get("role") == "tool":
            results[m.get("tool_call_id")] = m.get("content") or ""
    return [(calls[c][0], calls[c][1], results.get(c, "")) for c in order if c in calls]


def outputs_of(messages, task_id):
    return [out for name, args, out in flatten(messages)
            if name == "bash_output" and args.get("id") == task_id]


def user_notices(messages, needle):
    return [m for m in messages if m.get("role") == "user" and needle in (m.get("content") or "")]


def pairing_ok(messages):
    pending = set()
    for m in messages:
        for c in (m.get("tool_calls") or []):
            pending.add(c.get("id"))
        if m.get("role") == "tool":
            pending.discard(m.get("tool_call_id"))
    return not pending


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, payload):
        data = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        msgs = body.get("messages", [])
        sysmsg = next((m.get("content") or "" for m in msgs if m.get("role") == "system"), "")
        state["posts"] += 1

        if SUMMARIZER_MARK in sysmsg:
            self.reply(200, text("摘要：后台任务都处理完了。"))
            return

        state["systems"].append(sysmsg)
        state["system_md5"].append(md5(sysmsg))
        state["post_ms"].append(time.time() * 1000)
        state["requests"].append(msgs)

        t = state["turns"]
        state["turns"] += 1
        payload = SCRIPT[t]() if t < len(SCRIPT) else text("done")
        head = payload["choices"][0]["message"]
        name = (head.get("tool_calls") or [{}])[0].get("function", {}).get("name", "text")
        print("   [fake] 第 %d 轮 → %s" % (t, name), flush=True)
        self.reply(200, payload)

        if state["turns"] >= len(SCRIPT):
            threading.Thread(target=finish, daemon=True).start()


def finish():
    out = ""
    for _ in range(240):
        out = read_stdout()
        if "后台跑完了" in out:
            break
        time.sleep(0.25)

    meta = {f"bg_{i}": read_meta(f"bg_{i}") for i in range(1, 6)}
    last = state["requests"][-1] if state["requests"] else []
    md5s = state["system_md5"]

    if SCENARIO == "main":
        m1 = meta["bg_1"]
        check("task_meta_on_disk", m1.get("pid") and "line-$i" in (m1.get("command") or ""),
              "(.bg/bg_1.json pid=%s，命令原样存着)" % m1.get("pid"))

        # 起任务那一轮到下一轮隔了多久 vs 任务本身跑了多久
        gap = (state["post_ms"][1] - state["post_ms"][0]) if len(state["post_ms"]) > 1 else None
        dur = None
        if m1.get("started_ms") and m1.get("finished_ms"):
            dur = (m1["finished_ms"] - m1["started_ms"]) / 1000.0
        check("loop_not_blocked", gap is not None and gap < 1000 and (dur is None or dur > 2.5),
              "(起任务→下一轮 %.0fms，任务本身 %.1fs)" % (gap or -1, dur or -1))

        outs = outputs_of(last, "bg_1")
        first = outs[0] if outs else ""
        check("first_read_partial", "line-1" in first and "line-6" not in first,
              "(第一次 %d 字符，有 line-1，没有 line-6)" % len(first))

        second = outs[1] if len(outs) > 1 else ""
        check("second_read_incremental",
              "line-1" not in second and "line-6" in second and second.strip() != "",
              "(第二次 %d 字符，没有 line-1，有 line-6)" % len(second))

        notices_round = None
        for i, req in enumerate(state["requests"]):
            if user_notices(req, "[background] bg_1 finished"):
                notices_round = i
                break
        check("notice_in_messages", notices_round is not None,
              "(第 %s 轮请求的 messages 里出现 [background] bg_1 finished)" % notices_round)

        # 通知是一条 user 消息：第一轮的 messages 里不该有它
        check("notice_is_user_role", notices_round is not None and notices_round > 0,
              "(通知不是 system，是一次请求里新加的 user 消息)")

        check("system_unchanged", len(set(md5s)) == 1,
              "(%d 个请求，%d 种 system，md5 %s，%d 字符)"
              % (len(md5s), len(set(md5s)), md5s[0] if md5s else "",
                 len(state["systems"][0]) if state["systems"] else 0))
        check("notice_not_in_system", not any("[background]" in s for s in state["systems"]),
              "(system 里搜不到 [background])")

        check("exit_code_recorded", meta["bg_2"].get("exit_code") == 3,
              "(bg_2 exit_code=%s)" % meta["bg_2"].get("exit_code"))
        check("exit_code_in_notice",
              any(user_notices(req, "bg_2 finished (exit 3)") for req in state["requests"]),
              "(通知里写着 bg_2 finished (exit 3))")

        check("timeout_auto_killed", meta["bg_3"].get("status") == "timeout"
              and not alive(meta["bg_3"].get("pid")),
              "(bg_3 status=%s，pid %s 已不在)"
              % (meta["bg_3"].get("status"), meta["bg_3"].get("pid")))

        killed = meta["bg_4"]
        check("kill_task_works", killed.get("status") == "killed" and not alive(killed.get("pid")),
              "(bg_4 status=%s，pid %s 已退出)" % (killed.get("status"), killed.get("pid")))

        fifth = outputs_of(last, "bg_5")
        got = fifth[0] if fifth else ""
        check("output_truncated", "只回最后 40 行" in got and "160" in got and "200" in got,
              "(200 行只回了 40 行，丢了 160 行)")

        check("tool_pairing_intact", all(pairing_ok(req) for req in state["requests"]),
              "(全部请求 tool_call 都有回应)")
        check("script_finished", "后台跑完了" in out, "(剧本 %d 轮跑完)" % len(SCRIPT))
    else:
        check("naive_notice_in_system", any("[background] bg_1 finished" in s for s in state["systems"]),
              "(system 里出现了任务完成通知)")
        check("naive_system_changed", len(set(md5s)) > 1,
              "(%d 个请求拼出 %d 种 system)" % (len(md5s), len(set(md5s))))
        check("naive_no_user_notice",
              not any(user_notices(req, "[background]") for req in state["requests"]),
              "(messages 里没有通知，全进了 system)")
        pairs = [lcp(state["systems"][i], state["systems"][i + 1])
                 for i in range(len(state["systems"]) - 1)]
        changed_pairs = [p for a, b, p in
                         zip(state["systems"], state["systems"][1:], pairs) if a != b]
        total = len(state["systems"][0]) if state["systems"] else 0
        worst = max(changed_pairs) if changed_pairs else -1
        check("naive_prefix_collapsed", changed_pairs and worst * 10 < total,
              "(变了 system 的相邻请求 %d 对，公共前缀最多 %d 字符，是 %d 字符的 %d%%)"
              % (len(changed_pairs), worst, total, round(worst * 100.0 / total) if total else -1))
        check("script_finished", "后台跑完了" in out, "(剧本 %d 轮跑完)" % len(SCRIPT))

    with open(LOG, "w") as f:
        f.write("SCENARIO=%s BG_NOTICE=%s POSTS=%d TURNS=%d\n"
                % (SCENARIO, BG_NOTICE, state["posts"], state["turns"]))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("SYSTEM_MD5=%s\n" % " ".join(md5s))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    if SCENARIO == "main":
        reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s, BG_NOTICE=%s)"
          % (PORT, SCENARIO, BG_NOTICE), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
