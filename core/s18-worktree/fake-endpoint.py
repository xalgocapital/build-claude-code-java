#!/usr/bin/env python3
"""Local fake LLM endpoint for the s18 worktree-isolation experiment.

s17 的端点扮演「一个被驱动起来的人手」；这一篇扮演「两个同时开工的成员」。
端点靠 system 分辨角色：
    lead    —— 主循环。开人、看树、合并
    member  —— 成员。在**自己的** worktree 里写文件

两个场景，各验一件事：
    SCENARIO=main   # 两棵树、同一行各改各的、先合一个成功、再合一个冲突被拦下，18 项断言
    SCENARIO=naive  # 配 WORKTREE_MODE=naive，两人共写主树，后写的静默盖掉先写的，6 项断言

断言只读四样东西：每次请求的 messages、JVM stdout、沙箱里的文件、沙箱里的 git 状态。
快照在**每个 lead 请求到达时**取一次——那一刻 JVM 刚把上一轮的工具执行完，
读到的就是「上一轮结束时的盘面」。合并前后各有一张，冲突有没有合坏主树看的是这个。
"""
import json
import os
import re
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8918"))
SCENARIO = os.environ.get("SCENARIO", "main")
LOG = os.environ.get("ASSERT_LOG", "/tmp/s18_assert%s.txt" % ("" if SCENARIO == "main" else "_" + SCENARIO))
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s18_stdout.log")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")
TEAM_DIR = os.environ.get("TEAM_DIR", ".team")
SANDBOX = os.environ.get("SANDBOX_DIR", "/tmp/s18-sandbox")

SUMMARIZER_MARK = "你在压缩一段 Agent 会话的历史记录"
LEAD_MARK = "You are the lead of a small agent team"
MEMBER_MARK = "You are a teammate named "

DONE_TEXT = "沙箱合完了，两棵树一成一败，结论看上面那几行。"

SEED_PLAN = """# 发布计划
- 步骤 1：准备
- 步骤 2：灰度
- 步骤 3：全量
"""
ALICE_PLAN = """# 发布计划
- 步骤 1：准备（先核对版本号）
- 步骤 2：灰度
- 步骤 3：全量
"""
BOB_PLAN = """# 发布计划
- 步骤 1：准备（先备份 orders 表）
- 步骤 2：灰度
- 步骤 3：全量
"""

checks = []
state = {
    "posts": 0,
    "lead_idx": 0,
    "roles": [],
    "systems": {},
    "posts_log": [],
    "snaps": [],          # 每个 lead 请求到达时的盘面
    "member_tools": {},   # name → 它第一次 POST 带的工具名列表
    "member_pwd": {},     # name → bash pwd 的输出
}

# ---------- lead 的剧本 ----------
LEAD_MAIN = [
    lambda: call(("team_spawn", {
        "name": "alice", "role": "补发布计划的步骤 1",
        "prompt": "把 notes/plan.md 里「步骤 1：准备」那行补一句：先核对版本号。只改这一行。"}),
        ("team_spawn", {
            "name": "bob", "role": "补发布计划的步骤 1",
            "prompt": "把 notes/plan.md 里「步骤 1：准备」那行补一句：先备份 orders 表。只改这一行。"})),
    lambda: call(("worktree_list", {})),
    lambda: call(("worktree_list", {})),
    lambda: call(("worktree_merge", {"name": "alice"})),
    lambda: call(("worktree_merge", {"name": "bob"}), ("worktree_list", {})),
    lambda: call(("worktree_merge", {"name": "carol"}), ("worktree_list", {})),
    lambda: text(DONE_TEXT),
]

LEAD_NAIVE = [
    lambda: call(("team_spawn", {
        "name": "alice", "role": "补发布计划的步骤 1",
        "prompt": "把 notes/plan.md 里「步骤 1：准备」那行补一句：先核对版本号。只改这一行。"}),
        ("team_spawn", {
            "name": "bob", "role": "补发布计划的步骤 1",
            "prompt": "把 notes/plan.md 里「步骤 1：准备」那行补一句：先备份 orders 表。只改这一行。"})),
    lambda: call(("worktree_list", {})),
    lambda: text(DONE_TEXT),
]

# ---------- member 的剧本：pwd 一次，写一遍，收工 ----------
MEMBERS = {
    "alice": [
        lambda: call(("bash", {"command": "pwd"})),
        lambda: call(("write_file", {"path": "notes/plan.md", "content": ALICE_PLAN})),
        lambda: text("改了 notes/plan.md 的第二行：准备阶段先核对版本号。"),
    ],
    "bob": [
        lambda: call(("bash", {"command": "pwd"})),
        lambda: call(("write_file", {"path": "notes/plan.md", "content": BOB_PLAN})),
        lambda: text("改了 notes/plan.md 的第二行：准备阶段先备份 orders 表。"),
    ],
}


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


# ---------- 沙箱 ----------

def reset_state():
    for d in (TASK_DIR, TEAM_DIR):
        shutil.rmtree(d, ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    os.makedirs(TASK_DIR, exist_ok=True)
    # 沙箱整个删掉：git 仓库、分支、worktree 都要从零开始，否则上一遍的 agent/* 还在
    shutil.rmtree(SANDBOX, ignore_errors=True)


def git(*args):
    try:
        p = subprocess.run(["git"] + list(args), cwd=SANDBOX, capture_output=True,
                           text=True, timeout=30, env=dict(os.environ, LC_ALL="C"))
        return p.stdout.strip()
    except Exception as e:
        return "(git failed: %s)" % e


def read_text(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def snap():
    """一张盘面照：主树和两棵树的内容、git 状态、还有哪些树开着"""
    return {
        "main": read_text(os.path.join(SANDBOX, "notes/plan.md")),
        "alice": read_text(os.path.join(SANDBOX, ".worktrees/alice/notes/plan.md")),
        "bob": read_text(os.path.join(SANDBOX, ".worktrees/bob/notes/plan.md")),
        "status": git("status", "--porcelain"),
        "diff": git("diff"),
        "unmerged": git("diff", "--name-only", "--diff-filter=U"),
        "worktrees": [l for l in git("worktree", "list", "--porcelain").split("\n")
                      if l.startswith("worktree ")],
        "dirs": [d for d in ("alice", "bob")
                 if os.path.isdir(os.path.join(SANDBOX, ".worktrees", d))],
        "branches": [re.sub(r"^[+*]\s*", "", b) for b in git("branch", "--list", "agent/*").split("\n")
                     if b.strip()],
    }


def read_stdout():
    try:
        with open(STDOUT_LOG, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return ""


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
    if MEMBER_MARK in system:
        return "member"
    return "unknown"


def member_name(system):
    m = re.search(re.escape(MEMBER_MARK) + r"(\w+)", system)
    return m.group(1) if m else "?"


def step_of(msgs):
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
        tools = [t.get("function", {}).get("name", "") for t in body.get("tools", [])]

        if role == "summarizer":
            self.reply(200, text("摘要：两个成员各写自己那棵树。"))
            return

        state["posts"] += 1
        idx = state["posts"]
        state["roles"].append(role)
        state["systems"].setdefault(role, system)

        if role == "lead":
            state["snaps"].append(snap())
            i = state["lead_idx"]
            state["lead_idx"] += 1
            script = LEAD_NAIVE if SCENARIO == "naive" else LEAD_MAIN
            payload = text("done") if i >= len(script) else script[i]()
        elif role == "member":
            name = member_name(system)
            step = step_of(msgs)
            state["member_tools"].setdefault(name, tools)
            # 成员第二次 POST 的上下文里有 pwd 的结果，捞出来当 cwd 的证据
            if step >= 1 and name not in state["member_pwd"]:
                for m in msgs:
                    if m.get("role") == "tool" and isinstance(m.get("content"), str) \
                            and m["content"].startswith("/") and "s18-sandbox" in m["content"]:
                        state["member_pwd"][name] = m["content"].strip()
                        break
            script = MEMBERS.get(name, [])
            payload = text("done") if step >= len(script) else script[step]()
            print("   [fake] POST %d member %s 第 %d 步" % (idx, name, step), flush=True)
        else:
            payload = text("done")

        calls = payload["choices"][0]["message"].get("tool_calls") or []
        names = [c["function"]["name"] for c in calls]
        state["posts_log"].append({"idx": idx, "role": role, "messages": msgs,
                                   "system": system, "tools": tools, "returns": names})
        if role == "lead":
            print("   [fake] POST %d lead 第 %d 步 → %s"
                  % (idx, state["lead_idx"] - 1, ",".join(names) or "text"), flush=True)
        self.reply(200, payload)

        if role == "lead" and state["lead_idx"] >= len(LEAD_NAIVE if SCENARIO == "naive" else LEAD_MAIN):
            threading.Thread(target=finish, daemon=True).start()


# ---------- 断言 ----------

def finish():
    out = ""
    for _ in range(300):
        out = read_stdout()
        if DONE_TEXT in out:
            break
        time.sleep(0.2)

    if SCENARIO == "naive":
        finish_naive(out)
    else:
        finish_main(out)
    write_log()


def lead_msgs(i):
    """第 i 个 lead 请求带上去的 messages（0 起）"""
    leads = [p for p in state["posts_log"] if p["role"] == "lead"]
    return contents_of(leads[i]["messages"]) if i < len(leads) else ""


def finish_main(out):
    snaps = state["snaps"]
    post_spawn = snaps[1] if len(snaps) > 1 else {}
    after_edits = snaps[2] if len(snaps) > 2 else {}
    after_alice = snaps[4] if len(snaps) > 4 else {}
    after_conflict = snaps[5] if len(snaps) > 5 else {}

    commits = git("rev-list", "--count", "HEAD")
    tracked = git("ls-files")
    check("sandbox_repo_initialized",
          os.path.isdir(os.path.join(SANDBOX, ".git")) and commits.isdigit() and int(commits) >= 1,
          "(沙箱 %s 里有仓库，HEAD 有 %s 个提交——worktree add 要有基线提交才起得来)" % (SANDBOX, commits))

    check("seed_plan_tracked", "notes/plan.md" in tracked.split(),
          "(notes/plan.md 是被 git 跟踪的；后面「主树没变」才有东西可比)")

    worktree_paths = post_spawn.get("worktrees", [])
    check("two_worktrees_on_disk",
          sorted(post_spawn.get("dirs", [])) == ["alice", "bob"] and len(worktree_paths) == 3,
          "(起完两个人那一刻：.worktrees/ 下 alice、bob 两个目录，git worktree list 有 %d 条"
          "（主树 + 两个分支）)" % len(worktree_paths))

    check("worktree_list_reports_two",
          "list：2 个 worktree" in out and "worktree 2 个" in lead_msgs(2),
          "(第二轮的 worktree_list：工具回「worktree 2 个」，日志里是「list：2 个 worktree」)")

    branches = sorted(re.sub(r"^[+*]\s*", "", b) for b in post_spawn.get("branches", []))
    check("agent_branches_created", branches == ["agent/alice", "agent/bob"],
          "(一人一条分支：%s)" % "、".join(branches))

    check("worktrees_gitignored",
          post_spawn.get("status") == "",
          "(.worktrees/ 在 .gitignore 里，主树 git status 为空——不然「主树没动」证明不了)")

    alice_tools = state["member_tools"].get("alice", [])
    check("member_tools_writable",
          all(t in alice_tools for t in ("write_file", "edit_file", "read_file")),
          "(alice 的工具表：%s——s15 只有 read_file/glob，现在写和改都在)" % ",".join(alice_tools))

    check("member_tools_include_bash", "bash" in alice_tools,
          "(bash 也在，所以「在哪个目录跑」必须有答案，不能在提示词里含糊)")

    check("cwd_bound_to_worktree",
          state["member_pwd"].get("alice", "").endswith("/.worktrees/alice")
          and state["member_pwd"].get("bob", "").endswith("/.worktrees/bob"),
          "(各自 bash pwd：alice=%s，bob=%s)"
          % (state["member_pwd"].get("alice", "?"), state["member_pwd"].get("bob", "?")))

    check("main_tree_untouched_while_editing",
          after_edits.get("main") == SEED_PLAN and after_edits.get("status") == ""
          and after_edits.get("diff") == "",
          "(两人都写完那一刻：主树 notes/plan.md 逐字节等于种子，git status 空、git diff 空)")

    a, b = after_edits.get("alice"), after_edits.get("bob")
    same_other_lines = bool(a and b and a.split("\n")[0] == b.split("\n")[0]
                            and a.split("\n")[2:] == b.split("\n")[2:])
    check("same_line_diverges",
          bool(a and b and a != b and a != SEED_PLAN and b != SEED_PLAN and same_other_lines),
          "(两棵树只差第二行：alice「先核对版本号」、bob「先备份 orders 表」，其余三行逐字相同)")

    check("merge_alice_takes_main", after_alice.get("main") == ALICE_PLAN,
          "(合完 alice，主树第二行变成「先核对版本号」——这是第一棵树真的落地了)")

    left = [p for p in after_alice.get("worktrees", [])
            if os.path.realpath(p[len("worktree "):].strip()) != os.path.realpath(SANDBOX)]
    check("merge_cleans_worktree",
          len(left) == 1 and ".worktrees/bob" in left[0]
          and not os.path.isdir(os.path.join(SANDBOX, ".worktrees", "alice")),
          "(合成功就把树和分支都删了：worktree list 剩 %d 条（主树 + bob），.worktrees/alice 已经不在了)"
          % len(after_alice.get("worktrees", [])))

    check("conflict_captured",
          "CONFLICT (content): Merge conflict in notes/plan.md" in out
          and "冲突文件 notes/plan.md" in out,
          "(git 的原话被原样留下来：CONFLICT (content): Merge conflict in notes/plan.md)")

    check("conflict_reaches_lead",
          "CONFLICT" in lead_msgs(5) and "notes/plan.md" in lead_msgs(5),
          "(下一轮 lead 的上下文里有这段错误——模型知道「这次没合进去」，不是只看日志的人知道)")

    check("main_survives_conflict",
          after_conflict.get("main") == ALICE_PLAN and after_conflict.get("unmerged") == ""
          and after_conflict.get("status") == "",
          "(冲突后主树还是 alice 那版，没有未合并文件、没有冲突标记——abort 是为了这个)")

    check("merge_unknown_rejected",
          "没有 carol 的 worktree" in out,
          "(合一个不存在的树：直接报「没有 carol 的 worktree」，不装成功)")

    check("script_finished", True,
          "(%d 次 POST：lead %d 次、member %d 次)"
          % (state["posts"], state["roles"].count("lead"), state["roles"].count("member")))


def finish_naive(out):
    main_txt = read_text(os.path.join(SANDBOX, "notes/plan.md"))
    status = git("status", "--porcelain")
    status_lines = [l for l in status.split("\n") if l.strip()]

    check("naive_no_worktree",
          not os.path.isdir(os.path.join(SANDBOX, ".worktrees/alice"))
          and len([l for l in git("worktree", "list", "--porcelain").split("\n")
                   if l.startswith("worktree ")]) == 1,
          "(WORKTREE_MODE=naive：一棵树都没开，git worktree list 只有主树那一行)")

    check("naive_cwd_is_main",
          os.path.realpath(state["member_pwd"].get("alice", "").strip()) == os.path.realpath(SANDBOX)
          and os.path.realpath(state["member_pwd"].get("bob", "").strip()) == os.path.realpath(SANDBOX),
          "(两个人的 pwd 都是 %s——工具绑的是主树)" % SANDBOX)

    check("naive_second_write_wins",
          main_txt == BOB_PLAN and "先核对版本号" not in (main_txt or ""),
          "(主树最后是 bob 那一版：「先备份 orders 表」在，「先核对版本号」一个字都没剩下)")

    check("naive_git_status_one_file",
          len(status_lines) == 1 and "notes/plan.md" in status_lines[0],
          "(git status 只有 1 条：M notes/plan.md。两次写同一个文件，git 只记得最后一次)")

    check("naive_no_error_anywhere",
          "CONFLICT" not in out and "(error:" not in out and "Error:" not in out,
          "(整份日志里没有一个报错，两次 write_file 都回了 Wrote——这就是 s15 把写权限封掉的原因)")

    check("naive_script_finished", True,
          "(%d 次 POST：lead %d 次、member %d 次)"
          % (state["posts"], state["roles"].count("lead"), state["roles"].count("member")))


def write_log():
    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d\n" % (SCENARIO, state["posts"]))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s, sandbox=%s)"
          % (PORT, SCENARIO, SANDBOX), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
