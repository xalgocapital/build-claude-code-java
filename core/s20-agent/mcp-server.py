#!/usr/bin/env python3
"""一个最小的 MCP server：stdin 一行一个 JSON-RPC 请求，stdout 一行一个响应。

只实现四个方法：
    initialize                 握手，回协议版本和 serverInfo
    notifications/initialized  通知，没有 id，不回
    tools/list                 列出工具
    tools/call                 调一个工具

三个工具：echo（回显）、add（两数相加）、fail（故意失败）。

两件事是为了让客户端真的被考到：
  1. 每个带 id 的请求开一个线程处理。慢工具不会挡住后面的请求，
     响应因此可能乱序——客户端按 id 配对才对得上。echo 收到 "SLOW" 会睡 450ms。
  2. echo 收到 "HANG" 就睡 30 秒，用来测客户端的超时。

协议报文按行写进 MCP_LOG（默认 /tmp/s20_mcp.log），假端点读这个文件做断言。
"""
import json
import os
import sys
import threading
import time

LOG = os.environ.get("MCP_LOG", "/tmp/s20_mcp.log")
SLOW_S = float(os.environ.get("MCP_SLOW_S", "0.45"))
HANG_S = float(os.environ.get("MCP_HANG_S", "30"))
PROTOCOL_VERSION = "2024-11-05"

SERVER_INFO = {"name": "demo-mcp", "version": "0.1.0"}

TOOLS = [
    {
        "name": "echo",
        "description": "Echo the given text back. The text is returned unchanged.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to echo back"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "add",
        "description": "Add two numbers and return the sum.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "a": {"type": "number", "description": "First addend"},
                "b": {"type": "number", "description": "Second addend"},
            },
            "required": ["a", "b"],
        },
    },
    {
        "name": "fail",
        "description": "Always fails on purpose. Used to test isError handling.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
]

_write_lock = threading.Lock()
_log_lock = threading.Lock()


def log(direction, msg):
    """把每一条收发都记下来。假端点靠它看清握手字段和响应顺序。"""
    if not LOG:
        return
    line = json.dumps({"t": round(time.time() * 1000), "dir": direction,
                       "pid": os.getpid(), "msg": msg}, ensure_ascii=False)
    with _log_lock:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")


def send(msg):
    line = json.dumps(msg, ensure_ascii=False)
    log("send", msg)
    with _write_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def result(req_id, payload):
    send({"jsonrpc": "2.0", "id": req_id, "result": payload})


def error(req_id, code, message):
    send({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})


def text_block(text):
    return {"content": [{"type": "text", "text": text}], "isError": False}


def call_tool(params):
    name = params.get("name", "")
    args = params.get("arguments") or {}

    if name == "echo":
        text = str(args.get("text", ""))
        if text == "SLOW":
            time.sleep(SLOW_S)
            return text_block("slow-ok")
        if text == "HANG":
            # 永远不回。客户端不该陪它等下去。
            time.sleep(HANG_S)
            return text_block("late")
        return text_block(text)

    if name == "add":
        a = args.get("a", 0)
        b = args.get("b", 0)
        total = a + b
        if isinstance(a, int) and isinstance(b, int):
            total = int(total)
        return text_block(str(total))

    if name == "fail":
        return {"content": [{"type": "text", "text": "boom: disk on fire (simulated)"}],
                "isError": True}

    raise KeyError(name)


def handle(msg):
    req_id = msg.get("id")
    method = msg.get("method", "")
    params = msg.get("params") or {}
    try:
        if method == "initialize":
            result(req_id, {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": SERVER_INFO,
            })
        elif method == "tools/list":
            result(req_id, {"tools": TOOLS})
        elif method == "tools/call":
            result(req_id, call_tool(params))
        elif method == "ping":
            result(req_id, {})
        else:
            error(req_id, -32601, "method not found: " + method)
    except KeyError:
        error(req_id, -32602, "unknown tool: " + str(params.get("name")))
    except Exception as e:  # 进程不能因为一个工具崩掉
        error(req_id, -32603, "%s: %s" % (e.__class__.__name__, e))


def main():
    log("start", {"event": "start", "pid": os.getpid(), "argv": sys.argv,
                  "protocolVersion": PROTOCOL_VERSION})
    print("[mcp-server] ready pid=%d" % os.getpid(), file=sys.stderr, flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            print("[mcp-server] 收到非 JSON：%r" % line[:120], file=sys.stderr, flush=True)
            continue
        log("recv", msg)
        if "id" not in msg:
            # notifications/initialized 之类的通知。按协议不回。
            continue
        # 每个请求一个线程：慢工具不能挡住别的请求
        threading.Thread(target=handle, args=(msg,), daemon=True).start()


if __name__ == "__main__":
    main()
