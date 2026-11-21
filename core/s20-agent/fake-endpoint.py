#!/usr/bin/env python3
"""Local fake LLM endpoint for the s20 boot/budget experiment (port 8920).

它扮演三个角色：
  1. 模型：按剧本让 AgentLoop 建任务、读记忆、echo 大段文本、调 status、列任务板。
  2. 故障注入：第 8 个剧本位第一次 POST 回 400 context_length_exceeded，
     逼 AgentLoop 走「服务端拒收 → 强制压缩」这条路；压缩请求本身被端点拦截，
     不占剧本位，回一段固定摘要。
  3. 观察者：读 stdout 日志核对装配表和 [prompt]/[compact] 日志；读请求体核对
     system 分段、裁掉的段、摘要请求里的记忆索引、status 返回的字段。

断言只用两种事实：发出去的请求体、端点自己发过的响应。程序自己打印的「我做了」
只在 stdout 里核对行数，不作为机制成立的证据。

    SCENARIO=main  python3 fake-endpoint.py   # 断言写 /tmp/s20_assert.txt
    SCENARIO=naive python3 fake-endpoint.py   # BOOT_MODE=naive 的反例，写 _naive.txt
"""
import json
import os
import re
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SCENARIO = os.environ.get("SCENARIO", "main")
PORT = int(os.environ.get("FAKE_PORT", "8920"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s20_assert.txt" if SCENARIO == "main" else "/tmp/s20_assert_naive.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG",
                            "/tmp/s20_stdout.log" if SCENARIO == "main" else "/tmp/s20_stdout_naive.log")
MCP_LOG = os.environ.get("MCP_LOG", "/tmp/s20_mcp.log")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")

# 第 N 个剧本位（0 起）第一次 POST 回 400，逼出「服务端拒收 → forceCompact」。
# 重试的 POST 会带 <summary>，端点据此分辨「第一次」和「压缩后的重试」。
OVERFLOW_AT = 7

checks = []
state = {
    "posts": 0,
    "turns": 0,
    "bodies": [],          # 每个 200 POST 的完整请求体（按剧本位顺序）
    "all_bodies": [],      # 每个 POST，包括 400 和摘要请求
    "overflow_sent": 0,
    "summary_reqs": [],    # 拦下来的压缩请求
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


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return ""


# ---------- 大段回显文本：把 messages 撑过估算阈值用的 filler ----------

_WORDS = ("orders latency index query user created slow threshold report plan batch "
          "buffer shard token cache flush segment merge replica timeout retry queue "
          "worker schema migration rollback snapshot column partition table metric").split()


def filler(tag, words):
    out = []
    for i in range(words):
        out.append(_WORDS[i % len(_WORDS)] + "-" + tag + "-" + str(i))
    return " ".join(out)


MED1 = filler("m1", 150)
MED2 = filler("m2", 150)
BIG = filler("big", 800)

SUMMARY_TEXT = ("摘要：磁盘上原有 3 个任务，本轮新建 T4（给 /api/orders 加联合索引）和 T5（通知值班看报告，等 T4）。"
                "读过记忆 api-error-shape 的全文：错误返回先看 error.code。远端 echo 回了三段文本，add(20,22) 算出 42。"
                "下一步先调 status 看各子系统状态，再用 task_list 拿回任务板。")


def tool_messages(body):
    return [m.get("content") or "" for m in body.get("messages", []) if m.get("role") == "tool"]


def last_tool(body):
    msgs = tool_messages(body)
    return msgs[-1] if msgs else ""


def system_of(body):
    for m in body.get("messages", []):
        if m.get("role") == "system":
            return m.get("content") or ""
    return ""


def is_summary_request(body):
    msgs = body.get("messages", [])
    return (len(msgs) == 2 and msgs[0].get("role") == "system"
            and "压缩一段 Agent 会话" in (msgs[0].get("content") or ""))


SCRIPT = [
    lambda: call("task_create", {"subject": "给 /api/orders 加联合索引"}),
    lambda: call("task_create", {"subject": "通知值班看报告", "blockedBy": ["T4"]}),
    lambda: call("memory_read", {"key": "api-error-shape"}),
    lambda: call("mcp__demo__echo", {"text": MED1}),
    lambda: call("mcp__demo__echo", {"text": MED2}),
    lambda: call("mcp__demo__echo", {"text": BIG}),
    lambda: call("mcp__demo__add", {"a": 20, "b": 22}),
    lambda: call("status", {}),
    lambda: call("task_list", {}),
    lambda: text("总装完成：10 个子系统各自能跑，预算不够时先砍谁也测出来了。"),
]

FINAL_MARKER = "总装完成"


def seed_tasks():
    """播种 3 个磁盘任务：装配表里「磁盘 3 个任务」来自这里，不是剧本建出来的。"""
    shutil.rmtree(TASK_DIR, ignore_errors=True)
    os.makedirs(TASK_DIR, exist_ok=True)
    now = "11-20 09:00:00"
    tasks = [
        {"id": "T1", "subject": "回填慢查询报告", "status": "PENDING",
         "blockedBy": [], "owner": "", "createdAt": now, "updatedAt": now, "note": ""},
        {"id": "T2", "subject": "核对回填结果", "status": "PENDING",
         "blockedBy": ["T1"], "owner": "", "createdAt": now, "updatedAt": now, "note": ""},
        {"id": "T3", "subject": "备份 orders 表", "status": "COMPLETED",
         "blockedBy": [], "owner": "", "createdAt": now, "updatedAt": now, "note": ""},
    ]
    for t in tasks:
        with open(os.path.join(TASK_DIR, t["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(t, f, ensure_ascii=False, indent=2)
    with open(os.path.join(TASK_DIR, "seq"), "w") as f:
        f.write("3")


def reset_state():
    seed_tasks()
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    if os.path.exists(MCP_LOG):
        os.remove(MCP_LOG)


def memory_keys():
    """memory.seed 里的全部 key。装配正确时，摘要请求里应该一条不少。"""
    keys = []
    for scope in ("project", "user"):
        content = read_text(os.path.join("memory.seed", scope, "MEMORY.md"))
        for line in content.splitlines():
            if line.startswith("## "):
                keys.append(line[3:].strip())
    return keys


def tok(text):
    """和 Compactor.tokens 同一套估算：一个汉字一个，一个英文词一个。"""
    cjk = sum(1 for c in text if 0x4E00 <= ord(c) <= 0x9FFF)
    words = len(re.findall(r"[A-Za-z0-9]+", text))
    return cjk + words


def has_summary_message(body):
    # 注意不能拿「全文包含 <summary>」来判断：tool-policy 静态段里也提到这个词。
    # 摘要消息的特征是 content 以 <summary> 开头
    return any((m.get("content") or "").startswith("<summary>")
               for m in body.get("messages", []))


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
        state["all_bodies"].append(body)

        # 压缩请求：拦截，不占剧本位
        if is_summary_request(body):
            state["summary_reqs"].append(body)
            print("   [fake] 拦截摘要请求（第 %d 次）" % len(state["summary_reqs"]), flush=True)
            self.reply(200, text(SUMMARY_TEXT))
            return

        t = state["turns"]
        if t == OVERFLOW_AT and not has_summary_message(body):
            # 第一次到这一位：回 400 逼强制压缩，压缩完重试的那次才给剧本回复
            state["overflow_sent"] += 1
            state["posts"] += 1
            print("   [fake] 第 %d 位第一次 POST → 400 context_length_exceeded" % t, flush=True)
            self.reply(400, {"error": {"code": "context_length_exceeded",
                                       "message": "maximum context length exceeded"}})
            return

        state["posts"] += 1
        state["bodies"].append(body)
        state["turns"] += 1
        payload = SCRIPT[t]() if t < len(SCRIPT) else text("done")
        msg = payload["choices"][0]["message"]
        name = (msg.get("tool_calls") or [{}])[0].get("function", {}).get("name", "text")
        print("   [fake] 第 %d 轮 POST → %s" % (t, name), flush=True)
        self.reply(200, payload)

        if state["turns"] >= len(SCRIPT):
            threading.Thread(target=finish_naive if SCENARIO == "naive" else finish,
                             daemon=True).start()


def parse_boot_table(out):
    """从 stdout 里抠装配表行：  name ← deps detail"""
    rows = []
    for line in out.splitlines():
        m = re.match(r"^  (\S+)\s+←\s+(\S+)\s+(.*)$", line)
        if m:
            rows.append((m.group(1), m.group(2), m.group(3)))
    return rows


def status_result():
    for body in state["bodies"]:
        for m in body.get("messages", []):
            if m.get("role") == "tool" and "[status]" in (m.get("content") or ""):
                return m["content"]
    return ""


def finish():
    out = ""
    for _ in range(160):
        out = read_text(STDOUT_LOG)
        if FINAL_MARKER in out:
            break
        time.sleep(0.25)

    bodies = state["bodies"]
    keys = memory_keys()
    rows = parse_boot_table(out)
    names = [r[0] for r in rows]

    expect_order = ["tools", "tasks", "skills", "memory", "hooks", "compact",
                    "subagent", "prompt", "mcp", "status"]

    check("boot_table_printed",
          "10 个子系统，按依赖顺序启动" in out and len(rows) == 10 and set(names) == set(expect_order),
          "(装配表打了 %d 行：%s)" % (len(rows), ",".join(names)))
    check("boot_topo_order",
          names == expect_order and all(
              all(names.index(d) < names.index(n) for d in deps.split(",") if d in names)
              for n, deps, _ in rows),
          "(声明序即拓扑序：memory 在 compact 前，tasks/skills/memory 都在 prompt 前)")

    table_tokens = 0
    for _, _, detail in rows:
        m = re.search(r"(\d+) token", detail)
        if m:
            table_tokens += int(m.group(1))
    first_system = system_of(bodies[0]) if bodies else ""
    actual = tok(first_system)
    check("boot_tokens_near_system",
          bool(table_tokens) and actual <= table_tokens <= actual * 1.35,
          "(装配表合计 %d token，实际首个 system %d token，差的是被裁掉的段)" % (table_tokens, actual))

    last_system = system_of(bodies[-1]) if bodies else ""
    ident = "You are a coding agent working inside a repository"
    policy = "For any multi-step task, call task_create FIRST"
    check("static_never_trimmed",
          first_system.startswith(ident) and policy in first_system
          and last_system.startswith(ident) and policy in last_system
          and first_system[:600] == last_system[:600],
          "(identity / tool-policy 两个 STATIC 段首尾逐字节相同)")

    check("trim_order_round_first",
          "裁掉：tasks, workspace" in out,
          "([prompt] 日志里被裁的是 tasks 然后 workspace，轮不到 STATIC)")

    check("tasks_board_absent_from_system",
          bool(bodies) and all("ready to start" not in system_of(b) and "Tasks on disk" not in system_of(b)
                               for b in bodies),
          "(预算压穿后所有请求的 system 里都没有任务板——丢进度是真的)")

    final_tools = [t.get("function", {}).get("name") for t in (bodies[-1].get("tools") or [])] if bodies else []
    task_list_result = last_tool(bodies[-1]) if bodies else ""
    check("board_recoverable_via_task_list",
          "task_list" in final_tools and "T1 [PENDING]" in task_list_result,
          "(task_list 还在 tools 里，调一次任务清单就回来——但前提是模型自己想起来调)")

    n_compact = out.count("[compact] 第 ")
    check("overflow_forced_compaction",
          state["overflow_sent"] == 1 and n_compact == 1 and "第 1 次压缩" in out,
          "(端点回 1 次 400，客户端强制压缩 1 次)")
    check("compaction_once", n_compact == 1, "(整个会话只压了 1 次，之后估算一直没再过阈值)")

    summary_sys = ""
    if state["summary_reqs"]:
        summary_sys = state["summary_reqs"][0]["messages"][0].get("content") or ""
    hit = sum(1 for k in keys if k in summary_sys)
    check("summary_request_has_memory",
          len(state["summary_reqs"]) >= 1 and hit == len(keys),
          "(摘要请求里 %d/%d 条记忆索引都在——compact ← memory 这条边真的在传数据)"
          % (hit, len(keys)))

    def pairing_ok(body):
        seen = set()
        for m in body.get("messages", []):
            calls = m.get("tool_calls") or []
            if calls:
                for c in calls:
                    seen.add(c.get("id"))
            elif m.get("role") == "tool":
                if m.get("tool_call_id") not in seen:
                    return False
                seen.discard(m.get("tool_call_id"))
        return not seen

    check("tool_pairing_intact",
          bool(state["all_bodies"]) and all(pairing_ok(b) for b in state["all_bodies"]),
          "(全部 %d 个 POST 的 tool_call_id 配对完整)" % len(state["all_bodies"]))

    st = status_result()
    check("status_fields_complete",
          all(s in st for s in ["boot: 10/10 就绪", "tasks: 5 个，可开工 2",
                                "tools: 16 个（本地 13 + mcp 3）", "skills: 3 项，已加载 0",
                                "memory: 35 条（project 30 / user 5）", "compact: 压缩 1 次",
                                "prompt: 预算 1100", "前缀复用", "进程 1，重连 0，超时 0"]),
          "(status 一次返回了装配、任务、工具、技能、记忆、压缩、前缀、MCP 进程)")
    disk_tasks = len([f for f in os.listdir(TASK_DIR) if f.endswith(".json")]) if os.path.isdir(TASK_DIR) else 0
    m = re.search(r"tasks: (\d+) 个", st)
    check("status_tasks_match_disk",
          bool(m) and int(m.group(1)) == disk_tasks,
          "(status 说 %s 个任务，磁盘上 %d 个任务文件)"
          % (m.group(1) if m else "?", disk_tasks))
    check("status_memory_matches_seed",
          "memory: %d 条" % len(keys) in st,
          "(status 说的和 memory.seed 的 %d 条一致)" % len(keys))
    mcp_tools_in_request = [n for n in final_tools if n and n.startswith("mcp__")]
    mm = re.search(r"mcp (\d+)）", st)
    check("status_mcp_matches_request",
          bool(mm) and int(mm.group(1)) == len(mcp_tools_in_request),
          "(status 说 mcp %s 个，请求 tools 数组里 %d 个远端工具)"
          % (mm.group(1) if mm else "?", len(mcp_tools_in_request)))
    reuse = re.search(r"前缀复用 (\d+) 次", st)
    final_reuse = re.search(r"前缀复用 (\d+) 次，重算", out)
    check("status_prefix_reuse_plausible",
          bool(reuse and final_reuse) and 1 <= int(reuse.group(1)) <= int(final_reuse.group(1)),
          "(status 时点复用 %s 次，收尾报告 %s 次，快照不大于终值)"
          % (reuse.group(1) if reuse else "?", final_reuse.group(1) if final_reuse else "?"))

    events = []
    for line in read_text(MCP_LOG).splitlines():
        line = line.strip()
        if line:
            try:
                events.append(json.loads(line))
            except ValueError:
                pass
    inits = [e["msg"] for e in events if e.get("dir") == "recv" and e["msg"].get("method") == "initialize"]
    mcp_row = next((r for r in rows if r[0] == "mcp"), None)
    check("mcp_handshake_present",
          bool(inits) and inits[0].get("params", {}).get("clientInfo", {}).get("name") == "lcc-s20"
          and mcp_row is not None and "远端 3 个" in mcp_row[2],
          "(s19 的机制还在：握手 clientInfo=lcc-s20，装配表 mcp 行「远端 3 个」)")

    check("loop_survived",
          state["turns"] == len(SCRIPT) and FINAL_MARKER in out,
          "(剧本 %d 轮全部走完，含 1 次 400 重试与 1 次被拦的摘要请求)" % len(SCRIPT))

    write_assert()


def finish_naive():
    """BOOT_MODE=naive 的反例断言：装配顺序按字母排，压缩悄悄丢了记忆。"""
    out = ""
    for _ in range(160):
        out = read_text(STDOUT_LOG)
        if FINAL_MARKER in out:
            break
        time.sleep(0.25)

    bodies = state["bodies"]
    keys = memory_keys()
    rows = parse_boot_table(out)
    names = [r[0] for r in rows]
    alphabetical = sorted(["tools", "tasks", "skills", "memory", "hooks", "compact",
                           "subagent", "prompt", "mcp", "status"])

    check("boot_mode_naive",
          "BOOT_MODE=naive：按字母序装配" in out and len(rows) == 10,
          "(装配表照打，但顺序已经不是依赖序)")
    check("boot_order_alphabetical",
          names == alphabetical,
          "(实际顺序 %s，恰好是字母序)" % ",".join(names))
    check("memory_after_compact",
          "compact" in names and "memory" in names and names.index("memory") > names.index("compact"),
          "(compact 排在 memory 前面——Compactor 构造时抓住的 memory 引用是 null)")

    summary_sys = ""
    if state["summary_reqs"]:
        summary_sys = state["summary_reqs"][0]["messages"][0].get("content") or ""
    hit = sum(1 for k in keys if k in summary_sys)
    check("summary_missing_memory",
          len(state["summary_reqs"]) >= 1 and hit == 0,
          "(摘要请求里 0/%d 条记忆索引——对比正确装配序的 %d/%d，少整 %d 条)"
          % (len(keys), len(keys), len(keys), len(keys)))

    first_system = system_of(bodies[0]) if bodies else ""
    check("system_memory_still_there",
          bool(first_system) and "api-error-shape" in first_system,
          "(system 里的记忆目录还在——坏的只有 compact 那条构造期引用，所以没人报错)")

    ident = "You are a coding agent working inside a repository"
    last_system = system_of(bodies[-1]) if bodies else ""
    check("prompt_assembly_unaffected",
          first_system.startswith(ident) and last_system.startswith(ident),
          "(system 分段照常工作——这也正是这个 bug 难发现的原因：别的机制都好好的)")

    n_compact = out.count("[compact] 第 ")
    check("naive_compaction_still_works",
          state["overflow_sent"] == 1 and n_compact == 1,
          "(压缩本身照常触发——少记忆不报错，这就是装配顺序问题的麻烦之处)")

    check("naive_loop_survived",
          state["turns"] == len(SCRIPT) and FINAL_MARKER in out,
          "(naive 装配下剧本 %d 轮也能跑完——它不崩，它只是悄悄错)" % len(SCRIPT))

    write_assert()


def write_assert():
    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d TURNS=%d SUMMARY_REQS=%d OVERFLOW=%d\n"
                % (SCENARIO, state["posts"], state["turns"],
                   len(state["summary_reqs"]), state["overflow_sent"]))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    if SCENARIO in ("main", "naive"):
        reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s)" % (PORT, SCENARIO), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
