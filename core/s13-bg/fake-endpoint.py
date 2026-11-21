#!/usr/bin/env python3
"""Local fake LLM endpoint for the s12 task-system experiment.

这一篇要测的不是模型，是「状态到底存在哪」。所以剧本只做一件事：
按顺序把任务建出来、按依赖开工、故意违反依赖、故意造环，然后看代码拦不拦得住。

两个场景：
    SCENARIO=main     # 建 3 个带依赖的任务，走完一遍
    SCENARIO=restart  # 进程重启后读盘：任务还在不在、in_progress 有没有降级、序号接着走

断言全在端点这一侧：它读 .tasks/ 下的文件、读请求里的 system、读 JVM 输出。
"""
import json
import os
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8912"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s12_assert.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s12_stdout.log")
SCENARIO = os.environ.get("SCENARIO", "main")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"

checks = []
state = {"posts": 0, "turns": 0, "systems": [], "errors": [], "system_seen": ""}


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


SCRIPT = [
    lambda: call("task_create", {"subject": "写迁移脚本"}),
    lambda: call("task_create", {"subject": "写回滚方案", "blockedBy": ["T1"]}),
    lambda: call("task_create", {"subject": "补联合索引", "blockedBy": ["T2"]}),
    lambda: call("task_list", {}),
    lambda: call("task_update", {"id": "T1", "status": "IN_PROGRESS", "owner": "lead"}),
    lambda: call("task_update", {"id": "T1", "status": "COMPLETED"}),
    lambda: call("task_update", {"id": "T3", "status": "IN_PROGRESS"}),   # 前置没完成，应被拒
    lambda: call("task_update", {"id": "T2", "status": "IN_PROGRESS", "owner": "lead"}),
    lambda: call("task_update", {"id": "T1", "status": "COMPLETED", "blockedBy": ["T3"]}),  # 造环，应被拒
    lambda: call("task_list", {}),
    lambda: text("三个任务已建好：T1 完成，T2 进行中，T3 还在等 T2。依赖没乱。"),
]

RESTART_SCRIPT = [
    lambda: call("task_create", {"subject": "补一条验收清单"}),
    lambda: text("重启后接着干：新任务是下一步。"),
]


def reset_state():
    shutil.rmtree(TASK_DIR, ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")


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
            self.reply(200, text("摘要：三个任务，T1 已完成。"))
            return

        # 每一轮都记：任务板是随剧本长出来的，中间某几轮才有 ready 一栏
        state["system_seen"] = sysmsg
        state["systems"].append(sysmsg)

        t = state["turns"]
        state["turns"] += 1
        script = SCRIPT if SCENARIO == "main" else RESTART_SCRIPT
        payload = script[t]() if t < len(script) else text("done")
        print("   [fake] 第 %d 轮 → %s" % (t, payload["choices"][0]["message"].get("tool_calls", [{}])[0]
                                          .get("function", {}).get("name", "text")), flush=True)
        self.reply(200, payload)

        if state["turns"] >= len(script):
            threading.Thread(target=finish, daemon=True).start()


def finish():
    out = ""
    for _ in range(160):
        out = read_stdout()
        if SCENARIO == "main" and "三个任务已建好" in out:
            break
        if SCENARIO == "restart" and "重启后接着干" in out:
            break
        time.sleep(0.25)

    tasks = read_tasks()
    sysmsg = state["system_seen"]

    if SCENARIO == "main":
        check("tasks_on_disk", len(tasks) == 3,
              "(.tasks/ 下 %d 个文件，不是内存里的一张表)" % len(tasks))
        check("dependency_saved", tasks.get("T2", {}).get("blockedBy") == ["T1"]
              and tasks.get("T3", {}).get("blockedBy") == ["T2"],
              "(T2 ← T1，T3 ← T2，依赖写在文件里)")
        check("status_saved", tasks.get("T1", {}).get("status") == "COMPLETED"
              and tasks.get("T2", {}).get("status") == "IN_PROGRESS",
              "(T1 COMPLETED / T2 IN_PROGRESS，重启不会丢)")
        check("blocked_task_rejected", "还等着 T2 完成" in out,
              "(T3 前置没完成，task_update 直接被拒)")
        check("cycle_rejected", "形成环" in out,
              "(把 T1 的依赖改成 T3 会绕回去，被拒)")
        check("blocked_not_in_ready", "blocked (do NOT start these yet)" in sysmsg,
              "(被挡住的进 system 时单独一栏，不是混在可开工里)")
        check("ready_section_present", any("ready to start" in s for s in state["systems"]),
              "(中间有任务可开工时，单独一栏列出；最后 T3 被挡、没有可开工的，这一栏就消失)")
        check("board_in_system_tail", sysmsg.rfind("Tasks on disk") > sysmsg.find("Long-term memory"),
              "(任务板垫在 system 末尾，改它不影响前面的前缀)")
        check("no_lost_update", "已建好" in out, "(剧本 11 轮跑完)")
    else:
        check("tasks_restored", len(tasks) == 4,
              "(重启后读回 %d 个任务，含上一轮建的 3 个)" % len(tasks))
        check("orphan_marked", tasks.get("T2", {}).get("status") == "ORPHANED",
              "(上次死在 in_progress 的 T2 被标成 ORPHANED，不是装作还在跑)")
        check("seq_continues", "T4" in tasks,
              "(新建的是 T4，不是从 T1 再来一遍)")
        check("orphan_visible_in_system", "orphaned from last session" in sysmsg,
              "(孤儿任务进了 system 的那一段，模型看得见)")

    check("script_finished", True, "(场景 %s 的剧本跑完)" % SCENARIO)
    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d TURNS=%d\n" % (SCENARIO, state["posts"], state["turns"]))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    if SCENARIO == "main":
        reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s)" % (PORT, SCENARIO), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
