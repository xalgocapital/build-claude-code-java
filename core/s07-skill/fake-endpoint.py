#!/usr/bin/env python3
"""Local fake LLM endpoint for the s07 skill-loading experiment.

固定剧本，验证机制而不是模型质量：
  1. todo_write                  先列计划（s05 的机制，避免提醒噪音）
  2. skill("nope")               -> 未知技能返回 error，不崩
  3. bash("git tag v1.0.0")      -> 没加载技能，需要审批，被拒
  4. skill("release-notes")      -> 正文注入
  5. bash("git tag v1.0.0")      -> 技能预授权 bash(git tag)，放行
  6. skill("hotfix")             -> allowed-tools: bash
  7. bash("sudo shutdown now")   -> 硬拒绝仍然优先，预授权绕不过
  8. 纯文本收尾

每轮顺手断言「这一刻上下文里有什么」。
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8907"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s07_assert.txt")
checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    print(f"   [assert] {name} = {'YES' if ok else 'NO'} {detail}", flush=True)


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


def todos():
    return call("todo_write", {"todos": [
        {"content": "写发布说明", "status": "in_progress"},
        {"content": "打 tag", "status": "pending"},
    ]})


SCRIPT = [
    lambda msgs, sysmsg: todos(),
    lambda msgs, sysmsg: call("skill", {"name": "nope"}),
    lambda msgs, sysmsg: call("bash", {"command": "git tag v1.0.0"}),
    lambda msgs, sysmsg: call("skill", {"name": "release-notes"}),
    lambda msgs, sysmsg: call("bash", {"command": "git tag v1.0.0"}),
    lambda msgs, sysmsg: call("skill", {"name": "hotfix"}),
    lambda msgs, sysmsg: call("bash", {"command": "sudo shutdown now"}),
    lambda msgs, sysmsg: text("发布说明写好了，v1.0.0 已打 tag。线上那台按 hotfix 流程先回滚。"),
]


def last_tool(msgs):
    for m in reversed(msgs):
        if m.get("role") == "tool":
            return m.get("content") or ""
    return ""


class Handler(BaseHTTPRequestHandler):
    turn = 0

    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        msgs = body.get("messages", [])
        sysmsg = next((m.get("content") or "" for m in msgs if m.get("role") == "system"), "")
        tool = last_tool(msgs)
        joined = "\n".join((m.get("content") or "") for m in msgs)
        t = Handler.turn
        Handler.turn += 1

        if t == 0:
            # 目录在 system prompt 里，正文不在
            check("catalog_in_system", "- release-notes:" in sysmsg)
            check("catalog_has_all_three", sysmsg.count("\n- ") == 3, "(目录 3 行)")
            check("body_not_in_system", "打 tag 之前确认版本号" not in sysmsg, "(正文没进 system prompt)")
            check("skill_tool_exposed", any(
                x.get("function", {}).get("name") == "skill" for x in body.get("tools", [])),
                  "(分发表第 8 项)")
        if t == 2:
            check("unknown_skill_error", tool.startswith("(error: unknown skill 'nope'"))
            check("error_lists_available", "release-notes" in tool and "hotfix" in tool)
        if t == 3:
            check("needs_approval_denied", "needs approval" in tool, "(没加载技能时 git tag 被拒)")
        if t == 4:
            check("body_injected", '<skill name="release-notes">' in tool)
            check("body_full_text", "打 tag 之前确认版本号" in tool)
            check("body_has_structure", "## 措辞" in tool, "(标题层级原样保留)")
        if t == 5:
            check("preapproval_allows", "Permission denied" not in tool,
                  "(预授权 bash(git tag) 命中，命令真的跑了)")
        if t == 6:
            check("second_skill_loaded", '<skill name="hotfix">' in tool)
            check("first_body_still_there", "打 tag 之前确认版本号" in joined,
                  "(加载第二个技能，第一个的正文还在上下文里)")
        if t == 7:
            check("deny_list_still_wins", tool == "Permission denied by hook deny list",
                  "(hotfix 预授权了整个 bash，sudo 仍被硬拒绝拦下)")

        payload = SCRIPT[t](msgs, sysmsg) if t < len(SCRIPT) else text("done")
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

        if t == len(SCRIPT) - 1:
            with open(LOG, "w") as f:
                for name, ok, detail in checks:
                    f.write(f"{name}={('YES' if ok else 'NO')} {detail}\n")
                f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
            print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    print(f"fake LLM listening on http://127.0.0.1:{PORT}/v1", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
