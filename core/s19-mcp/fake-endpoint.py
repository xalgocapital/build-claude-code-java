#!/usr/bin/env python3
"""Local fake LLM endpoint for the s19 MCP experiment (port 8919).

它同时扮演两个角色：
  1. 模型：按剧本让 AgentLoop 去调 mcp__demo__add / echo / fail / 不存在的工具，
     中途把 MCP server 杀掉、再让它调一个会挂住的工具。
  2. 观察者：读 JSON-RPC 报文日志（MCP_LOG），看清握手字段、tools/list、
     响应顺序、重连后的新 pid；读请求体，看工具结果到底回了什么。

断言只用两种事实：发出去的请求体、server 自己写的协议日志。不看程序内部状态。

    SCENARIO=main python3 fake-endpoint.py
"""
import json
import os
import shutil
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8919"))
LOG = os.environ.get("ASSERT_LOG", "/tmp/s19_assert.txt")
STDOUT_LOG = os.environ.get("STDOUT_LOG", "/tmp/s19_stdout.log")
MCP_LOG = os.environ.get("MCP_LOG", "/tmp/s19_mcp.log")
SCENARIO = os.environ.get("SCENARIO", "main")
TASK_DIR = os.environ.get("TASK_DIR", ".tasks")

checks = []
state = {
    "posts": 0,
    "turns": 0,
    "bodies": [],          # 每个 POST 的完整请求体
    "post_times": [],
    "killed": [],
}

PROTOCOL_VERSION = "2024-11-05"


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


def read_mcp_log():
    out = []
    for line in read_text(MCP_LOG).splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def kill_server():
    """把当前活着的 MCP server 杀掉。pid 从它自己的启动日志里读。"""
    pid = None
    for event in read_mcp_log():
        if event["dir"] == "start":
            pid = event["msg"].get("pid")
    if pid:
        try:
            os.kill(pid, signal.SIGKILL)
            state["killed"].append(pid)
            print("   [fake] 杀掉 MCP server pid=%d" % pid, flush=True)
            time.sleep(0.15)   # 给读线程一点时间发现 EOF
        except ProcessLookupError:
            pass
    return pid


SCRIPT = [
    lambda: call("mcp__demo__add", {"a": 2, "b": 3}),
    lambda: call("mcp__demo__echo", {"text": "hello from mcp"}),
    lambda: call("mcp__demo__fail", {}),
    lambda: call("mcp__demo__nope", {}),
    lambda: (kill_server(), call("mcp__demo__add", {"a": 3, "b": 4}))[1],   # 杀掉后再调
    lambda: call("mcp__demo__echo", {"text": "HANG"}),                      # 永远不回
    lambda: text("MCP 这一圈跑完了：远端工具能调、失败能回灌、进程挂了能自己接回来。"),
]


def reset_state():
    shutil.rmtree(TASK_DIR, ignore_errors=True)
    shutil.rmtree("memory", ignore_errors=True)
    if os.path.isdir("memory.seed"):
        shutil.copytree("memory.seed", "memory")
    if os.path.exists(MCP_LOG):
        os.remove(MCP_LOG)


def tool_messages(body):
    return [m.get("content") or "" for m in body.get("messages", []) if m.get("role") == "tool"]


def last_tool(body):
    msgs = tool_messages(body)
    return msgs[-1] if msgs else ""


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
        state["posts"] += 1
        state["bodies"].append(body)
        state["post_times"].append(time.time())

        t = state["turns"]
        state["turns"] += 1
        payload = SCRIPT[t]() if t < len(SCRIPT) else text("done")
        msg = payload["choices"][0]["message"]
        name = (msg.get("tool_calls") or [{}])[0].get("function", {}).get("name", "text")
        print("   [fake] 第 %d 轮 POST → %s" % (t, name), flush=True)
        self.reply(200, payload)

        if state["turns"] >= len(SCRIPT):
            threading.Thread(target=finish, daemon=True).start()


def finish():
    out = ""
    for _ in range(160):
        out = read_text(STDOUT_LOG)
        if "MCP 这一圈跑完了" in out:
            break
        time.sleep(0.25)

    events = read_mcp_log()
    recv = [e["msg"] for e in events if e["dir"] == "recv"]
    sent = [e["msg"] for e in events if e["dir"] == "send"]
    starts = [e["msg"] for e in events if e["dir"] == "start"]

    inits = [m for m in recv if m.get("method") == "initialize"]
    init = inits[0] if inits else {}
    init_params = init.get("params") or {}
    client_info = init_params.get("clientInfo") or {}

    check("initialize_version_correct",
          init_params.get("protocolVersion") == PROTOCOL_VERSION,
          "(客户端说的协议版本是 %s)" % init_params.get("protocolVersion"))
    check("client_info_present",
          client_info.get("name") == "lcc-s19" and client_info.get("version") == "1.0.0",
          "(clientInfo=%s/%s)" % (client_info.get("name"), client_info.get("version")))

    notified = [i for i, m in enumerate(recv)
                if m.get("method") == "notifications/initialized" and "id" not in m]
    init_at = recv.index(init) if init in recv else 10 ** 9
    check("initialized_notification_sent", bool(notified) and notified[0] > init_at,
          "(initialize 之后发了一条没有 id 的通知)")

    listed = [m for m in recv if m.get("method") == "tools/list"]
    list_results = [m for m in sent if "result" in m and "tools" in (m["result"] or {})]
    tool_names = [t["name"] for t in (list_results[0]["result"]["tools"] if list_results else [])]
    check("tools_list_three", len(listed) >= 1 and tool_names == ["echo", "add", "fail"],
          "(tools/list 回 %d 个：%s)" % (len(tool_names), ",".join(tool_names)))

    first_tools = (state["bodies"][0].get("tools") or []) if state["bodies"] else []
    exposed = [t["function"]["name"] for t in first_tools]
    check("tools_prefixed_in_request",
          all("mcp__demo__" + n in exposed for n in ("echo", "add", "fail")),
          "(发给模型的 tools 里有 %s)" % ", ".join(n for n in exposed if n.startswith("mcp__")))

    add_schema = next((t["function"]["parameters"] for t in first_tools
                       if t["function"]["name"] == "mcp__demo__add"), {})
    props = add_schema.get("properties") or {}
    check("schema_passed_through",
          props.get("a", {}).get("type") == "number" and props.get("b", {}).get("type") == "number"
          and add_schema.get("required") == ["a", "b"],
          "(add 的 inputSchema 原样到了 tools[].function.parameters)")

    bodies = state["bodies"]
    add_result = last_tool(bodies[1]) if len(bodies) > 1 else ""
    check("call_add_result_five", add_result.strip() == "5",
          "(add(2,3) 的 tool 消息是 %r)" % add_result.strip())

    echo_result = last_tool(bodies[2]) if len(bodies) > 2 else ""
    check("call_echo_text_joined", echo_result.strip() == "hello from mcp",
          "(content[] 里的 text 被拼成了一个字符串)")

    fail_result = last_tool(bodies[3]) if len(bodies) > 3 else ""
    check("iserror_mapped_to_tool_error",
          "<tool-error" in fail_result and "boom" in fail_result,
          "(isError=true 变成 <tool-error tool=\"mcp__demo__fail\">…)")

    unknown_result = last_tool(bodies[4]) if len(bodies) > 4 else ""
    check("unknown_tool_explicit_error",
          "unknown tool" in unknown_result and "mcp__demo__nope" in unknown_result,
          "(没静默成功，报的是 unknown tool)")

    # 乱序：前两条 tools/call（启动自检）同时发出，后发的那条先回
    calls = [m for m in recv if m.get("method") == "tools/call"]
    pair = calls[:2]
    ok_order = False
    if len(pair) == 2:
        a, b = pair[0].get("id"), pair[1].get("id")
        send_ids = [m.get("id") for m in sent if m.get("method") is None]
        if a in send_ids and b in send_ids:
            ok_order = send_ids.index(b) < send_ids.index(a)
    check("out_of_order_responses_paired",
          ok_order and "乱序自检通过" in out,
          "(id %s 先回、id %s 后回，两条各自配对)"
          % (pair[1].get("id") if len(pair) == 2 else "?", pair[0].get("id") if len(pair) == 2 else "?"))

    pids = [s.get("pid") for s in starts]
    check("reconnected_after_kill",
          len(pids) >= 2 and len(set(pids)) >= 2 and len(inits) >= 2,
          "(server 被 kill 后起了新进程 %s，并重新 initialize)" % pids)

    after_kill = last_tool(bodies[5]) if len(bodies) > 5 else ""
    check("reconnect_call_succeeded", after_kill.strip() == "7",
          "(重连之后 add(3,4) 回了 %r)" % after_kill.strip())

    timeout_result = last_tool(bodies[6]) if len(bodies) > 6 else ""
    elapsed = (state["post_times"][6] - state["post_times"][5]) if len(state["post_times"]) > 6 else 0
    check("timeout_returns_error",
          "timeout" in timeout_result and "2000" in timeout_result,
          "(HANG 的工具回了 %r)" % timeout_result.strip()[:80])
    check("timeout_cut_at_two_seconds", 1.9 <= elapsed <= 4.5,
          "(卡住到下一次 POST 之间 %.2fs，不是永远挂着)" % elapsed)

    check("loop_survived", state["posts"] == len(SCRIPT),
          "(剧本 %d 轮全部走完，客户端没崩)" % len(SCRIPT))

    with open(LOG, "w") as f:
        f.write("SCENARIO=%s POSTS=%d TURNS=%d PIDS=%s\n"
                % (SCENARIO, state["posts"], state["turns"], ",".join(str(p) for p in pids)))
        for name, ok, detail in checks:
            f.write("%s=%s %s\n" % (name, "YES" if ok else "NO", detail))
        f.write("ALL=%s\n" % ("YES" if all(c[1] for c in checks) else "NO"))
    print("   [assert] 断言写入 " + LOG, flush=True)


if __name__ == "__main__":
    if SCENARIO == "main":
        reset_state()
    print("fake LLM listening on http://127.0.0.1:%d/v1 (scenario=%s)" % (PORT, SCENARIO), flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
