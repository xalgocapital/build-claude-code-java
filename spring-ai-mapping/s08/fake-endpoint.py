#!/usr/bin/env python3
"""Local fake endpoint for the s08 Spring AI comparison.

只做两件事：
  1. 收到「压缩」请求（system 里带标记）→ 返回一段摘要
  2. 收到正常请求 → 打一行日志，列出它收到的消息类型，然后返回一句话

这样不用花钱也能看清：advisor 的 before() 到底把什么交给了模型。
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("FAKE_PORT", "8909"))
SUMMARY_MARK = "压缩一段 Agent 会话"
SUMMARY_TEXT = ("SPRING_SUMMARY: 目标=挑最慢三条 SQL 给索引建议；"
                "已完成=读过 slow-1..slow-4，最慢 /api/orders 4000ms；下一步=写 notes/verdict.txt")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        msgs = body.get("messages", [])
        sysmsg = " ".join((m.get("content") or "") for m in msgs
                          if m.get("role") == "system" and isinstance(m.get("content"), str))

        if SUMMARY_MARK in sysmsg:
            print("   [fake] 摘要请求：收到 %d 条消息，返回固定摘要" % len(msgs), flush=True)
            content = SUMMARY_TEXT
        else:
            roles = [m.get("role") for m in msgs]
            print("   [fake] 正常请求：%d 条消息 %s" % (len(msgs), roles), flush=True)
            for m in msgs:
                text = m.get("content") or ""
                if isinstance(text, str) and "条消息已压缩移除" in text:
                    print("   [fake]   上下文里有摘要，摘要是 %d 字符" % len(text), flush=True)
                    break
            content = "按摘要继续：最慢三条已在摘要里，接着写 notes/verdict.txt。"

        data = json.dumps({
            "id": "chatcmpl-fake",
            "object": "chat.completion",
            "model": body.get("model", "fake"),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }, ensure_ascii=False).encode()

        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print("fake LLM listening on http://127.0.0.1:%d" % PORT, flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
