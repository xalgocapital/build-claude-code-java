#!/usr/bin/env python3
"""Local fake LLM endpoint for the s10 system-prompt experiment.

这一篇要测的不是模型，是 system prompt 的字节稳定性。所以端点在每个请求里做三件事：
  1. 取 messages[0].content（system 全文）
  2. 和上一次请求的 system 比：一模一样？一样到第几个字符？（最长公共前缀 LCP）
  3. 估算 token，看有没有超预算

为什么用 LCP 而不是让程序自己上报指纹：模型服务的前缀缓存命中的就是「前缀相同的部分」，
LCP 是能从请求体里直接量出来的、和实现无关的数。程序里那套 SHA-1 只是给人看的日志。

剧本 11 轮，故意让四类事情各发生一次：写记忆（改 SESSION 段）、更新待办（改 ROUND 段）、
读日志（什么都不改）、加载技能（只改 messages，不改 system）。

    PROMPT_MODE=sorted python3 fake-endpoint.py   # 正常：按频率排序
    PROMPT_MODE=naive  python3 fake-endpoint.py   # 反例：时间戳置顶 + 按重要性排

端点靠 system 开头有没有 "Current time:" 判断是哪一遍，断言也分开写。
"""
import json
import os
import shutil
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8910"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s10_assert.txt")
BUDGET = int(os.environ.get("PROMPT_BUDGET", "1200"))

PROJECT_FILE = "memory/project/MEMORY.md"
USER_FILE = "memory/user/MEMORY.md"
SEED_DIR = "memory.seed"

checks = []
state = {
    "requests": 0,
    "systems": [],          # 每次带 tools 的请求里的 system 全文
    "pairs": [],            # (相同前缀长度, 上一次长度, 本次长度)
    "max_tokens": 0,
    "skill_turn_systems": [],
    "naive": False,
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
    {"content": "读 data 下两份日志", "status": "pending"},
    {"content": "挑出最慢的一条", "status": "pending"},
]}
TODO_V2 = {"todos": [
    {"content": "把接口错误返回的形状记下来", "status": "completed"},
    {"content": "读 data 下两份日志", "status": "in_progress"},
    {"content": "挑出最慢的一条", "status": "pending"},
]}
TODO_V3 = {"todos": [
    {"content": "把接口错误返回的形状记下来", "status": "completed"},
    {"content": "读 data 下两份日志", "status": "completed"},
    {"content": "挑出最慢的一条", "status": "completed"},
]}

SCRIPT = [
    lambda msgs, sysmsg: call("todo_write", TODO_V1),                    # ROUND 段出现
    lambda msgs, sysmsg: call("memory_read", {"key": "api-error-shape"}),
    lambda msgs, sysmsg: call("read_file", {"path": "data/big-1.log"}),
    lambda msgs, sysmsg: call("memory_write", {                          # SESSION 段变了
        "scope": "project",
        "key": "api-error-shape",
        "text": "S10_UPDATED 接口错误返回固定是 {\"error\":{\"code\":\"...\",\"message\":\"...\"}}，"
                "没有 type 字段。解析走 code；HTTP 状态码不一定是 200，先判 error 再判 data。",
    }),
    lambda msgs, sysmsg: call("todo_write", TODO_V2),                    # ROUND 段又变了
    lambda msgs, sysmsg: call("read_file", {"path": "data/big-2.log"}),
    lambda msgs, sysmsg: call("memory_write", {
        "scope": "user",
        "key": "reply-style",
        "text": "回答先给结论再给理由；代码注释用中文，变量名保持英文；不要先铺三段背景。",
    }),
    lambda msgs, sysmsg: call("memory_write", {
        "scope": "project",
        "key": "log-format",
        "text": "慢查询日志一行一条，格式是「时间戳 耗时ms 接口 SQL」，耗时字段带 ms 后缀，解析时去掉。",
    }),
    lambda msgs, sysmsg: call("todo_write", TODO_V3),
    lambda msgs, sysmsg: call("skill", {"name": "sql-review"}),          # 只改 messages，不改 system
    lambda msgs, sysmsg: text("记了三条：错误返回形状、日志格式、回复偏好。最慢的是 /api/orders "
                              "4200ms，缺 (user_id, created_at) 联合索引，索引名按约定叫 "
                              "idx_orders_user_id_created_at。"),
]


def lcp(a, b):
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


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


def reset_state():
    shutil.rmtree("memory", ignore_errors=True)
    shutil.copytree(SEED_DIR, "memory")


def write_data():
    os.makedirs("data", exist_ok=True)
    for i in range(1, 3):
        rows = []
        for j in range(1, 21):
            rows.append(
                "2026-11-21T10:%02d:%02dZ %4dms /api/orders "
                "SELECT id, user_id, total FROM orders WHERE user_id = %d AND created_at > '2026-11-01' "
                "ORDER BY created_at DESC LIMIT 50;"
                % (i, j, 4300 - i * 100 - j * 10, 10000 + j)
            )
        rows.append("FILE-%d-END 共 20 条慢查询" % i)
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

        # 只统计带 tools 的会话请求；摘要请求的 system 是另一份，不该混进来
        if tools:
            if state["systems"]:
                prev = state["systems"][-1]
                state["pairs"].append((lcp(prev, sysmsg), len(prev), len(sysmsg)))
            state["systems"].append(sysmsg)
            state["max_tokens"] = max(state["max_tokens"], tokens(sysmsg))
            state["naive"] = sysmsg.startswith("Current time:")

        index = len(state["systems"]) - 1
        payload = self.judge_turn(msgs, sysmsg, index)

        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

        if Handler.turn >= len(SCRIPT) and not Handler.finished:
            Handler.finished = True
            self.finish_report()

    def judge_turn(self, msgs, sysmsg, index):
        t = Handler.turn
        Handler.turn += 1
        return SCRIPT[t](msgs, sysmsg) if t < len(SCRIPT) else text("done")

    def finish_report(self):
        systems = state["systems"]
        pairs = state["pairs"]
        naive = state.get("naive", False)
        last = systems[-1] if systems else ""

        # 完全相同 = 长度也一致。只算「前一条是本次的严格前缀」会把「追加一段」也算进来
        identical = sum(1 for n, a, b in pairs if a == b and n == a)
        covered = sum(1 for n, a, b in pairs if n == min(a, b))
        ratios = [n / min(a, b) for n, a, b in pairs]
        distinct = len(set(systems))

        print("\n   [measure] 请求  system 字符  与上次相同  占比", flush=True)
        for i, (n, a, b) in enumerate(pairs, start=2):
            print("   [measure] %4d %10d %11d %6.1f%%%s"
                  % (i, b, n, 100.0 * n / min(a, b),
                     "  ← 完全相同" if a == b else ("  ← 前一次整条都被复用" if n == min(a, b) else "")),
                  flush=True)

        # 段的位置：在最后一份 system 里找（此时待办段已经出现）
        pos = {}
        for key, mark in (("identity", "You are a coding agent"),
                          ("skills", "Available skills"),
                          ("memory", "Long-term memory"),
                          ("plan", "Current plan")):
            if mark in last:
                pos[key] = last.find(mark)

        if naive:
            check("naive_clock_first", last.startswith("Current time:"),
                  "(反例：秒级时间戳排在第一段，它每轮都不一样)")
            check("naive_memory_before_skills",
                  pos.get("memory", 1 << 30) < pos.get("skills", 1 << 30),
                  "(反例：按重要性把 754 token 的记忆目录排在技能目录前面)")
            check("naive_all_distinct", distinct == len(systems),
                  "(%d 个请求，%d 种 system，每轮都不一样)" % (len(systems), distinct))
            check("naive_prefix_broken", sum(1 for r in ratios if r >= 0.5) == 0,
                  "(%d 对相邻请求，没有一对的前缀能复用)" % len(ratios))
            check("naive_lcp_short", max(n for n, a, b in pairs) <= 64,
                  "(最长公共前缀只有 %d 字符，卡在时间戳的秒上)" % max(n for n, a, b in pairs))
        else:
            check("identity_first", last.startswith("You are a coding agent"),
                  "(身份在最前，它是静态段)")
            check("section_order",
                  pos.get("skills", -1) < pos.get("memory", -1) < pos.get("plan", 1 << 30),
                  "(顺序：技能目录 → 记忆目录 → 待办，变化频率递增)")
            check("volatile_at_tail",
                  last.rfind("Current plan") > last.find("Long-term memory"),
                  "(每轮可能变的待办段垫在最后)")
            check("trim_dropped_workspace",
                  all("Working directory" not in s for s in systems),
                  "(超预算，先裁掉 pwd 一句就能拿到的段)")
            check("byte_identical_pairs", identical >= 4,
                  "(%d 对相邻请求里 %d 对字节完全相同，一个 token 都不用重算)"
                  % (len(pairs), identical))
            check("prefix_fully_covered", covered >= 5,
                  "(另有 %d 对是「前一次整条被复用」：新内容只追加在尾部)"
                  % (covered - identical))
            check("prefix_survives_round_change",
                  sum(1 for r in ratios if r >= 0.5) >= 6,
                  "(%d 对里 %d 对前缀还能用——待办变了，它前面一个字没动)"
                  % (len(ratios), sum(1 for r in ratios if r >= 0.5)))
            check("distinct_systems_few", distinct < len(systems),
                  "(%d 个请求只拼出 %d 种 system：改的只是尾部)" % (len(systems), distinct))
            check("budget_reported", state["max_tokens"] > BUDGET,
                  "(最大 %d token 越过预算 %d，代码报了一次而不是砍记忆目录)"
                  % (state["max_tokens"], BUDGET))
            check("skill_load_keeps_system", len(systems) >= 11 and systems[10] == systems[9],
                  "(第 10 轮加载技能：正文进了 messages，system 一个字节没动)")

        check("script_finished", Handler.turn >= len(SCRIPT), "(剧本 %d 轮跑完)" % len(SCRIPT))

        with open(LOG, "w") as f:
            f.write("MODE=%s\n" % ("naive" if naive else "sorted"))
            f.write("REQUESTS=%d DISTINCT=%d IDENTICAL=%d AVG_LCP=%.3f MAX_TOKENS=%d\n"
                    % (len(systems), distinct, identical, sum(ratios) / len(ratios),
                       state["max_tokens"]))
            for name, ok, detail in checks:
                f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
            f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
        print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    reset_state()
    write_data()
    print("fake LLM listening on http://127.0.0.1:%d/v1" % PORT, flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
