#!/usr/bin/env python3
"""Carbit 日志接收器。

- GET  /list                     列出已收到的文件（JSON）
- POST /upload?name=xxx.txt      请求体写入 /data/xxx.txt
- PUT  /upload/xxx.txt           同上，文件名走路径
- GET  /health                   健康检查

鉴权：环境变量 TOKEN 非空时，所有请求必须带 ?token=XXX。
落盘只取 basename 并过滤字符，杜绝 ../ 路径穿越。
"""
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse, parse_qs

DATA = os.environ.get("DATA_DIR", "/data")
TOKEN = os.environ.get("TOKEN", "")
MAX_BYTES = 512 * 1024 * 1024


def safe_name(raw):
    name = unquote(raw or "")
    name = os.path.basename(name.replace("\\", "/"))
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name).strip("._") or "log"
    if not name.lower().endswith((".txt", ".log", ".json", ".apk", ".zip", ".bin", ".jpg", ".jpeg", ".png")):
        name += ".txt"
    return name[:120]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CarbitReceiver/1.0"

    # ---- helpers ----
    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self, query):
        if not TOKEN:
            return True
        return (query.get("token") or [""])[0] == TOKEN

    def log_message(self, fmt, *args):
        # 简洁访问日志，落到容器 stdout 供 docker logs 看
        print("[%s] %s" % (self.address_string(), fmt % args), flush=True)

    # ---- routes ----
    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path.rstrip("/") in ("", "/health"):
            return self._send_json(200, {"ok": True, "service": "carbit-receiver"})
        if parsed.path.rstrip("/") in ("/", "/list"):
            if not self._authed(query):
                return self._send_json(401, {"ok": False, "error": "bad token"})
            items = []
            try:
                for name in sorted(os.listdir(DATA)):
                    path = os.path.join(DATA, name)
                    if os.path.isfile(path):
                        items.append({
                            "name": name,
                            "bytes": os.path.getsize(path),
                            "mtime": int(os.path.getmtime(path)),
                        })
            except FileNotFoundError:
                pass
            return self._send_json(200, {"ok": True, "count": len(items), "files": items})
        return self._send_json(404, {"ok": False, "error": "not found"})

    def _store(self, name):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if not self._authed(query):
            return self._send_json(401, {"ok": False, "error": "bad token"})
        if not name:
            name = (query.get("name") or [""])[0]
        name = safe_name(name or "log-%d" % int(time.time()))
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return self._send_json(411, {"ok": False, "error": "length required"})
        if length <= 0:
            return self._send_json(400, {"ok": False, "error": "empty body"})
        if length > MAX_BYTES:
            return self._send_json(413, {"ok": False, "error": "too large"})
        os.makedirs(DATA, exist_ok=True)
        target = os.path.join(DATA, name)
        received = 0
        with open(target, "wb") as fh:
            while received < length:
                chunk = self.rfile.read(min(65536, length - received))
                if not chunk:
                    break
                fh.write(chunk)
                received += len(chunk)
        return self._send_json(200, {"ok": True, "name": name, "bytes": received})

    def do_POST(self):
        parsed = urlparse(self.path)
        if not parsed.path.startswith("/upload"):
            return self._send_json(404, {"ok": False, "error": "not found"})
        name = (parse_qs(parsed.query).get("name") or [""])[0]
        return self._store(name)

    def do_PUT(self):
        parsed = urlparse(self.path)
        if not parsed.path.startswith("/upload"):
            return self._send_json(404, {"ok": False, "error": "not found"})
        name = parsed.path[len("/upload"):].lstrip("/")
        return self._store(name)


if __name__ == "__main__":
    os.makedirs(DATA, exist_ok=True)
    port = int(os.environ.get("PORT", "8899"))
    print("carbit-receiver listening on :%d, data=%s, token=%s"
          % (port, DATA, "on" if TOKEN else "off"), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
