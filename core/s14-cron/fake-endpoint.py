#!/usr/bin/env python3
"""Local fake LLM endpoint for the s14 cron experiment.

这一篇测的不是模型，是「没有人踢的时候，任务怎么到点自己跑」。端点同时扮演
两种模型：主会话模型和定时会话模型，靠 system 里有没有 [cron] 身份段区分。
定时会话由调度器的守护线程发来，和主会话的请求交错到达——单线程 HTTP 串行处理，
时间戳照样可信。

两个场景（都预置一个 nextRun 落在 10 分钟前的 cron_1，等价于重启时的现场）：
    SCENARIO=main    # 错过不补跑：只按最新一次触发，差额记 skipped
    SCENARIO=naive   # 反例：错过的每个周期都补跑，重启瞬间 N 连发

断言只看端点看得见的事实：请求里的 messages / system、POST 的时间戳序列、
磁盘上的 .cron/jobs.json 与 .cron/<id>.log，必要时读 JVM stdout（解析自检）。
"""
import json
import os
import shutil
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8914"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s14_assert.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s14_stdout.log")
SCENARIO = os.environ.get("SCENARIO", "main")
CRON_DIR = os.environ.get("CRON_DIR", ".cron")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"
CRON_MARK = "[cron]"
CRON_REPLY = "定时任务已执行：本次触发已记录。"
FINISH_MARK = "cron 验证完成"

SEED_PROMPT = "补跑检查点：报告你被触发这件事"
HEARTBEAT_PROMPT = "心跳：报告你被触发了一次"
PATROL_PROMPT = "巡检：五分钟到了"

checks = []
state = {
    "posts": 0,
    "turns": 0,
    "systems": [],
    "requests": [],        # 主会话请求的 messages 快照
    "req_ms": [],          # 主会话请求到达时间
    "cron_posts": [],      # (到达时间 ms, prompt)
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
    lambda: call("cron_create", {"expr": "*/1 * * * *", "prompt": HEARTBEAT_PROMPT}),
    lambda: call("cron_create", {"expr": "*/5 * * * *", "prompt": PATROL_PROMPT}),
    lambda: call("cron_list", {}),
    lambda: call("bash", {"command": "sleep 1.0"}),
    lambda: call("cron_delete", {"id": "cron_3"}),
    lambda: call("bash", {"command": "sleep 0.8"}),
    lambda: call("cron_list", {}),
    lambda: text(FINISH_MARK + "，检查点都落盘了。"),
]

SCRIPT_NAIVE = [
    lambda: call("bash", {"command": "sleep 0.4"}),
    lambda: call("cron_list", {}),
    lambda: text(FINISH_MARK + "，检查点都落盘了。"),
]


def reset_state():
    """清掉 .cron，再预置一条 nextRun 落在 10 分钟前的 cron_1。

    CronStore 启动时从磁盘读回任务，和重启走的是同一段代码——预置的现场
    就是「进程停了十分钟再回来」的现场。分钟取整是为了让错过的次数确定：10 分钟
    整 = 11 个该触发而没触发的点。
    """
    shutil.rmtree(CRON_DIR, ignore_errors=True)
    shutil.rmtree(".bg", ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    base = datetime.now().replace(second=0, microsecond=0) - timedelta(minutes=10)
    seed = {
        "id": "cron_1", "expr": "*/1 * * * *", "prompt": SEED_PROMPT,
        "enabled": True, "lastRun": None,
        "nextRun": base.strftime("%Y-%m-%dT%H:%M:%S"),
        "runs": 0, "skipped": 0,
    }
    os.makedirs(CRON_DIR, exist_ok=True)
    with open(os.path.join(CRON_DIR, "jobs.json"), "w", encoding="utf-8") as f:
        json.dump({"jobs": [seed]}, f, ensure_ascii=False, indent=2)
    print("   [seed] cron_1 nextRun=%s（10 分钟前）" % seed["nextRun"], flush=True)


def read_stdout():
    try:
        with open(STDOUT_LOG, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return ""


def read_jobs():
    path = os.path.join(CRON_DIR, "jobs.json")
    try:
        with open(path, encoding="utf-8") as f:
            root = json.load(f)
        return {j["id"]: j for j in root.get("jobs", [])}
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def read_log(job_id):
    path = os.path.join(CRON_DIR, job_id + ".log")
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return ""


def flatten(messages):
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


def pairing_ok(messages):
    pending = set()
    for m in messages:
        for c in (m.get("tool_calls") or []):
            pending.add(c.get("id"))
        if m.get("role") == "tool":
            pending.discard(m.get("tool_call_id"))
    return not pending


def cron_posts_for(prompt):
    return [t for t, p, n in state["cron_posts"] if p == prompt]


def cron_msg_counts(prompt):
    return [n for t, p, n in state["cron_posts"] if p == prompt]


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
        now_ms = time.time() * 1000
        state["posts"] += 1

        if SUMMARIZER_MARK in sysmsg:
            self.reply(200, text("摘要：定时任务都处理完了。"))
            return

        if CRON_MARK in sysmsg:
            user = next((m.get("content") or "" for m in msgs if m.get("role") == "user"), "")
            state["cron_posts"].append((now_ms, user, len(msgs)))
            print("   [fake] cron 会话 %.3f → %s（messages %d 条）" % (now_ms % 100000, user[:14], len(msgs)), flush=True)
            self.reply(200, text(CRON_REPLY))
            return

        state["systems"].append(sysmsg)
        state["requests"].append(msgs)
        state["req_ms"].append(now_ms)

        t = state["turns"]
        state["turns"] += 1
        script = SCRIPT if SCENARIO == "main" else SCRIPT_NAIVE
        payload = script[t]() if t < len(script) else text("done")
        head = payload["choices"][0]["message"]
        name = (head.get("tool_calls") or [{}])[0].get("function", {}).get("name", "text")
        print("   [fake] 第 %d 轮 → %s" % (t, name), flush=True)
        self.reply(200, payload)

        if state["turns"] >= len(script):
            threading.Thread(target=finish, daemon=True).start()


def main_query_in_cron():
    needle = "两个定时任务"
    return any(needle in p for _, p, n in state["cron_posts"])


def finish():
    out = ""
    for _ in range(240):
        out = read_stdout()
        if FINISH_MARK in out:
            break
        time.sleep(0.25)
    time.sleep(0.5)   # 让最后几条 cron 会话落盘

    jobs = read_jobs()
    seed = cron_posts_for(SEED_PROMPT)
    heart = cron_posts_for(HEARTBEAT_PROMPT)
    patrol = cron_posts_for(PATROL_PROMPT)

    if SCENARIO == "main":
        # 解析自检：磁盘和请求体证明不了日历推算对不对，只能看 stdout 里的自检行
        check("parse_step_selfcheck", "10:05 → 10:10 → 10:15" in out,
              "(stdout 自检行给出 10:05 → 10:10 → 10:15)")
        check("parse_weekday_selfcheck", "周一 2026-11-23 09:00" in out,
              "(stdout 自检行：0 9 * * 1-5 从周日推到周一 09:00)")

        # 错过不补跑：cron_1 的 nextRun 在 10 分钟前。如果错过的 10 个点被逐个补跑，
        # 会出现一串间隔 <50ms 的连发。正常写法最多只有 1 对短间隔——补跑那一发落在
        # 「现在」（不在整分边界），到下一个日历分钟边界的那一段可以很短
        gaps = [b - a for a, b in zip(seed, seed[1:])]
        burst_pairs = [g for g in gaps if g < 50]
        check("no_replay_burst", len(burst_pairs) <= 1,
              "(cron_1 的 %d 对相邻触发里只有 %d 对 <50ms，10 个错过的点没有被逐个补跑)"
              % (len(gaps), len(burst_pairs)))
        j1 = jobs.get("cron_1", {})
        check("missed_recorded_skipped", j1.get("skipped", 0) >= 10 and j1.get("runs", 0) >= 1,
              "(jobs.json cron_1 skipped=%s runs=%s，错过的不补)" % (j1.get("skipped"), j1.get("runs")))

        # 定时会话是独立上下文：只有 system + user 两条，主会话的 query 不在里面
        counts = cron_msg_counts(SEED_PROMPT) + cron_msg_counts(HEARTBEAT_PROMPT) \
            + cron_msg_counts(PATROL_PROMPT)
        check("cron_sessions_isolated", counts and all(n == 2 for n in counts)
              and not main_query_in_cron(),
              "(全部 %d 次定时会话 messages 都是 2 条，主会话 query 没出现)"
              % (len(counts)))
        check("cron_result_logged",
              CRON_REPLY in read_log("cron_1") and CRON_REPLY in read_log("cron_2"),
              "(.cron/cron_1.log 与 cron_2.log 都有结果正文)")

        check("heartbeat_fires", len(heart) >= 4,
              "(cron_2 心跳触发 %d 次，期望 ≥4)" % len(heart))
        hgaps = [b - a for a, b in zip(heart, heart[1:])]
        check("heartbeat_periodic", all(120 <= g <= 800 for g in hgaps),
              "(cron_2 相邻间隔 %d-%dms，标称 200ms)" % (int(min(hgaps)), int(max(hgaps)))
              if hgaps else "(没有触发)")

        check("cron_list_shows_next",
              any("next=" in (m.get("content") or "") for req in state["requests"]
                  for m in req if m.get("role") == "tool"),
              "(cron_list 的 tool 结果里有 next=)")

        j2 = jobs.get("cron_2", {})
        check("cron_create_persisted",
              j2.get("expr") == "*/1 * * * *" and j2.get("prompt") == HEARTBEAT_PROMPT,
              "(jobs.json cron_2 expr=%s runs=%s)" % (j2.get("expr"), j2.get("runs")))

        # 删除之后不再触发：以「带着 Deleted cron_3 结果的那个主请求」的到达时间为界
        t_del = None
        for i, req in enumerate(state["requests"]):
            for name_, args_, res_ in flatten(req):
                if name_ == "cron_delete" and "Deleted cron_3" in res_:
                    t_del = state["req_ms"][i]
        after = [t for t in patrol if t_del is not None and t > t_del]
        check("cron_delete_stops_firing",
              len(patrol) >= 1 and t_del is not None and not after,
              "(cron_3 删除前触发 %d 次，删除后 %d 次)" % (len(patrol), len(after)))

        check("main_tool_pairing_intact", all(pairing_ok(req) for req in state["requests"]),
              "(全部主请求 tool_call 都有回应)")
        check("script_finished", FINISH_MARK in out, "(主剧本 %d 轮跑完)" % len(SCRIPT))
    else:
        # 反例：补跑全部错过的周期
        check("naive_burst_count", len(seed) >= 10,
              "(cron_1 共触发 %d 次，错过的周期全补了)" % len(seed))
        first11 = seed[:11]
        span = (first11[-1] - first11[0]) if len(first11) >= 11 else None
        check("naive_burst_at_restart",
              span is not None and span < 1000,
              "(前 11 次触发集中在 %dms 内——重启瞬间)" % int(span) if span else "(触发不足 11 次)")
        j1 = jobs.get("cron_1", {})
        check("naive_skipped_zero", j1.get("skipped", -1) == 0 and j1.get("runs", 0) >= 10,
              "(jobs.json cron_1 skipped=%s runs=%s)" % (j1.get("skipped"), j1.get("runs")))
        gaps = [b - a for a, b in zip(seed, seed[1:])]
        burst_gaps = [g for g in gaps if g < 50]
        check("naive_gap_signature", len(burst_gaps) >= 8,
              "(%d 对相邻触发间隔 <50ms，主场景是 0 对)" % len(burst_gaps))
        check("naive_script_finished", FINISH_MARK in out, "(naive 剧本跑完)")

    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d MAIN_TURNS=%d CRON_POSTS=%d\n"
                % (SCENARIO, state["posts"], state["turns"], len(state["cron_posts"])))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s)"
          % (PORT, SCENARIO), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
