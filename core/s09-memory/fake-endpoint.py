#!/usr/bin/env python3
"""Local fake LLM endpoint for the s09 memory experiment.

剧本固定，验证机制而不是模型质量：
  1. todo_write                          先列计划
  2. memory_read("api-error-shape")      读一条上一次会话留下的记忆（换会话还在）
  3. memory_write(project, 同 key)        改一条：覆盖，不是追加
  4. memory_write(user, reply-style)      写一条用户级：跨项目，不进仓库
  5. memory_write(project, 765 字过程日志) 被拒：记忆存结论，不存过程
  6. memory_write(project, log-format)    新增第 31 条 → 淘汰最久没读的那条
  7. memory_read("reply-style")          用户级也能按需读
  8-10. read_file(data/big-N.log)        三份大日志，把上下文顶到预算之上 → 触发压缩
 11. 纯文本收尾

启动时把 memory.seed/ 复制成 memory/，所以每次跑都是同一份初始状态（30 条项目级 + 5 条用户级）。
断言分两类：请求里看（目录有没有进 system、写完有没有重拼）+ 落盘后看（覆盖、淘汰、拒绝）。
"""
import json
import os
import shutil
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8909"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s09_assert.txt")

PROJECT_FILE = "memory/project/MEMORY.md"
USER_FILE = "memory/user/MEMORY.md"
SEED_DIR = "memory.seed"

SUMMARY_MARK = "你在压缩一段 Agent 会话"
SUMMARY_BLOCK_MARK = "条消息已压缩移除"
SUMMARY_TEXT = ("S09_SUMMARY: 目标=记住接口错误返回形状、挑出最慢的一条日志；"
                "已完成=错误形状已写进记忆，读过 big-1..big-3；下一步=给结论")

checks = []
state = {
    "requests": 0,
    "pairing_violations": 0,
    "summary_requests": 0,
    "post_compact_seen": False,
    "no_index_requests": 0,
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
    {"content": "把接口错误返回的形状记下来", "status": "in_progress"},
    {"content": "读 data 下三份日志", "status": "pending"},
    {"content": "挑出最慢的一条", "status": "pending"},
]}

TODO_V2 = {"todos": [
    {"content": "把接口错误返回的形状记下来", "status": "completed"},
    {"content": "读 data 下三份日志", "status": "completed"},
    {"content": "挑出最慢的一条", "status": "in_progress"},
]}

PROCESS_LOG = (
    "本会话过程记录：第 1 轮列了三条待办；第 2 轮读了 api-error-shape 这条记忆，正文是"
    "{\"error\":{\"code\",\"message\"}} 形状；第 3 轮把这条记忆覆盖成了带例子的版本；"
    "第 4 轮写了一条用户级偏好；第 5 轮想写这条过程日志，被 MAX_BODY 拒了。"
    "第 6 轮新增 log-format 条目，触发了淘汰；第 7 轮读用户级偏好；第 8 到 10 轮连读三份大日志，"
    "把上下文顶过了预算；第 11 轮收尾。以上是流水账，不是可复用的事实，写进记忆只会污染下一次会话。"
) * 3


SCRIPT = [
    lambda msgs, sysmsg: call("todo_write", TODO_V1),
    lambda msgs, sysmsg: call("memory_read", {"key": "api-error-shape"}),
    lambda msgs, sysmsg: call("memory_write", {
        "scope": "project",
        "key": "api-error-shape",
        "text": "S09_UPDATED 接口错误返回固定是 {\"error\":{\"code\":\"...\",\"message\":\"...\"}}，"
                "没有 type 字段。解析走 code；HTTP 状态码不一定是 200，先判 error 再判 data。",
    }),
    lambda msgs, sysmsg: call("memory_write", {
        "scope": "user",
        "key": "reply-style",
        "text": "回答先给结论再给理由；代码注释用中文，变量名保持英文；不要先铺三段背景。",
    }),
    lambda msgs, sysmsg: call("memory_write", {
        "scope": "project",
        "key": "session-log-2026-11-21",
        "text": PROCESS_LOG,
    }),
    lambda msgs, sysmsg: call("memory_write", {
        "scope": "project",
        "key": "log-format",
        "text": "慢查询日志一行一条，格式是「时间戳 耗时ms 接口 SQL」，耗时字段带 ms 后缀，解析时去掉。",
    }),
    lambda msgs, sysmsg: call("memory_read", {"key": "reply-style"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/big-1.log"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/big-2.log"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/big-3.log"}),
    lambda msgs, sysmsg: call("todo_write", TODO_V2),
    lambda msgs, sysmsg: text("记忆已经存好：接口错误形状、日志格式、你的回复偏好。最慢的一条是 "
                              "/api/orders 的 4200ms，缺 (user_id, created_at) 联合索引。"),
]


def last_tool(msgs):
    for m in reversed(msgs):
        if m.get("role") == "tool":
            return m.get("content") or ""
    return ""


def tokens(s):
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


def pairing_violations(msgs):
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


def block(path, key):
    """取文件里某一个 ## key 段（到下一个 ## 或文件尾）"""
    try:
        with open(path) as f:
            content = f.read()
    except FileNotFoundError:
        return ""
    marker = "## " + key + "\n"
    i = content.find(marker)
    if i < 0:
        return ""
    j = content.find("\n## ", i + 1)
    return content[i:] if j < 0 else content[i:j]


def reset_memory():
    shutil.rmtree("memory", ignore_errors=True)
    shutil.copytree(SEED_DIR, "memory")


def write_data():
    """三份大日志：每份 25 行，用来把上下文顶过预算，触发 s08 的压缩"""
    os.makedirs("data", exist_ok=True)
    for i in range(1, 4):
        rows = []
        for j in range(1, 26):
            rows.append(
                "2026-11-21T10:%02d:%02dZ %4dms /api/orders "
                "SELECT id, user_id, total FROM orders WHERE user_id = %d AND created_at > '2026-11-01' "
                "ORDER BY created_at DESC LIMIT 50;"
                % (i, j, 4300 - i * 100 - j * 10, 10000 + j)
            )
        rows.append("FILE-%d-END 共 25 条慢查询" % i)
        with open("data/big-%d.log" % i, "w") as f:
            f.write("\n".join(rows) + "\n")


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

        bad = pairing_violations(msgs)
        state["pairing_violations"] += bad
        if bad:
            print("   [assert] !! 本次请求有 %d 条 tool 消息找不到父 tool_calls" % bad, flush=True)

        if tools and "- api-error-shape [project]" not in sysmsg:
            state["no_index_requests"] += 1

        if not tools and SUMMARY_MARK in sysmsg:
            state["summary_requests"] += 1
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
        last = last_tool(msgs)

        if t == 0:
            check("index_in_system", "- api-error-shape [project]" in sysmsg,
                  "(磁盘上的记忆目录进了 system prompt)")
            check("index_digest_only", "S09_SEED_TAIL" not in sysmsg and "S09_USER_STALE" not in sysmsg,
                  "(目录里只有一行摘要，正文没进上下文)")
            check("project_overrides_user", "- api-error-shape [user]" not in sysmsg,
                  "(同名 key 两级都有，目录只显示项目级那条)")
            check("hint_injected", "<memory-hint>" in joined,
                  "(用户说了「记住」，UserPromptSubmit 注入了写入提示)")
            import re as _re
            lines = _re.findall(r"(?m)^- \S+ \[(?:project|user)\]", sysmsg)
            check("index_line_count", len(lines) == 34,
                  "(30 条项目级 + 5 条用户级，同名去重后 %d 行)" % len(lines))

        if t == 2:
            check("cross_session_read", "S09_SEED_TAIL" in last and "<memory key=" in last,
                  "(读到的是上一次会话写进文件的正文，不是本轮对话)")

        if t == 3:
            check("overwrite_messaging", "replaced the previous value" in last,
                  "(同 key 写入被识别为覆盖)")
            check("index_refreshed", "S09_UPDATED" in sysmsg,
                  "(写完记忆，下一轮 system 里的目录立刻变了)")

        if t == 4:
            check("user_scope_write", "saved (user" in last, "(用户级写入走了另一个文件)")

        if t == 5:
            check("process_rejected", "NOT saved" in last and "超过上限" in last,
                  "(765 字的过程日志被拒，正文长度是硬闸门)")

        if t == 6:
            check("lru_evict_messaging", "evicted the least recently read entry: rollback" in last,
                  "(第 31 条进来，淘汰最久没读的那条)")

        if t == 7:
            check("user_level_read", "先给结论再给理由" in last, "(用户级记忆也能按需读全文)")

        if not state["post_compact_seen"] and SUMMARY_BLOCK_MARK in joined:
            state["post_compact_seen"] = True
            check("memory_survives_compact", "- api-error-shape [project]" in sysmsg,
                  "(压缩删了 messages 中间那段，system 里的记忆目录没动)")
            check("read_body_dropped", "S09_SEED_TAIL" not in joined,
                  "(读回来的正文被压掉了——所以记忆不能只放在上下文里)")
            check("head_kept", msgs[0].get("role") == "system"
                  and msgs[1].get("role") == "user" and "记住" in (msgs[1].get("content") or ""),
                  "(头两条还是 system + 原始目标)")

        return SCRIPT[t](msgs, sysmsg) if t < len(SCRIPT) else text("done")

    def finish_report(self):
        project = open(PROJECT_FILE).read() if os.path.exists(PROJECT_FILE) else ""
        user = open(USER_FILE).read() if os.path.exists(USER_FILE) else ""

        api = block(PROJECT_FILE, "api-error-shape")
        check("overwrite_not_append", project.count("## api-error-shape\n") == 1
              and "S09_UPDATED" in api and "S09_SEED_TAIL" not in api,
              "(文件里只有一段 api-error-shape，正文是新的)")
        check("reads_persisted", "reads: 2" in api,
              "(读过一次，计数落盘了——淘汰策略要有据可依)")
        check("scope_separation", "## reply-style" in user and "## reply-style" not in project,
              "(用户级写在用户文件里，项目文件里没有)")
        check("reject_not_persisted", "## session-log" not in project,
              "(被拒的写入没有落盘)")
        check("lru_evicted", "## rollback" not in project
              and len([line for line in project.split("\n") if line.startswith("## ")]) == 30,
              "(30 条上限成立，最久没读的 rollback 被淘汰)")
        check("new_entry_saved", "## log-format" in project, "(新 key 正常写入)")
        check("index_in_every_request", state["no_index_requests"] == 0,
              "(%d 个会话请求，每次都带着记忆目录)" % (state["requests"] - state["summary_requests"]))
        check("pairing_intact", state["pairing_violations"] == 0,
              "(全程 0 次孤立 tool 消息)")
        check("compaction_happened", state["post_compact_seen"], "(至少压过一次)")
        check("script_finished", Handler.turn >= len(SCRIPT), "(剧本 %d 轮跑完)" % len(SCRIPT))

        with open(LOG, "w") as f:
            for name, ok, detail in checks:
                f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
            f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
        print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    reset_memory()
    write_data()
    print("fake LLM listening on http://127.0.0.1:%d/v1" % PORT, flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
