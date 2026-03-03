"""
收款通知服务端 - 接收 Android App 推送的支付通知
用法: python server.py [--host HOST] [--port PORT] [--secret SECRET] [--db DB_PATH]
"""

import argparse
import hashlib
import json
import sqlite3
import time
from binascii import unhexlify
from contextlib import contextmanager
from datetime import datetime

from flask import Flask, Response, g, jsonify, render_template_string, request

app = Flask(__name__)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
CONFIG = {
    "HOST": "0.0.0.0",
    "PORT": 5000,
    "SECRET_KEY": "",      # DES 解密密码 / MD5 签名密钥，留空则不解密不验签
    "DB_PATH": "receipts.db",
}

# ---------------------------------------------------------------------------
# 数据库
# ---------------------------------------------------------------------------

def get_db() -> sqlite3.Connection:
    if "db" not in g:
        g.db = sqlite3.connect(CONFIG["DB_PATH"])
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(exc):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db():
    conn = sqlite3.connect(CONFIG["DB_PATH"])
    conn.execute("""
        CREATE TABLE IF NOT EXISTS receipts (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            type        TEXT,
            time        TEXT,
            title       TEXT,
            money       TEXT,
            content     TEXT,
            deviceid    TEXT,
            sign        TEXT,
            encrypt     TEXT,
            raw_json    TEXT,
            created_at  TEXT DEFAULT (datetime('now','localtime'))
        )
    """)
    conn.commit()
    conn.close()

# ---------------------------------------------------------------------------
# DES 解密 (DES/CBC/PKCS5Padding, key=iv=password)
# ---------------------------------------------------------------------------

def des_decrypt(hex_cipher: str, password: str) -> str:
    try:
        from Crypto.Cipher import DES
        key = password.encode("utf-8")[:8].ljust(8, b"\0")
        iv = key
        cipher = DES.new(key, DES.MODE_CBC, iv)
        data = unhexlify(hex_cipher)
        decrypted = cipher.decrypt(data)
        pad_len = decrypted[-1]
        if 1 <= pad_len <= 8 and all(b == pad_len for b in decrypted[-pad_len:]):
            decrypted = decrypted[:-pad_len]
        return decrypted.decode("utf-8")
    except Exception as e:
        app.logger.warning("DES 解密失败: %s", e)
        return hex_cipher

# ---------------------------------------------------------------------------
# MD5 签名
# ---------------------------------------------------------------------------

def md5(s: str) -> str:
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def verify_sign(pay_type: str, money: str, sign: str, secret: str) -> bool:
    if not sign:
        return True
    if secret:
        expected = md5(md5(pay_type + money) + secret)
    else:
        expected = md5(md5(pay_type + money))
    return expected.lower() == sign.lower()

# ---------------------------------------------------------------------------
# API: 接收通知
# ---------------------------------------------------------------------------

SKIP_DECRYPT_FIELDS = {"sign", "encrypt", "url"}

@app.route("/api/receive", methods=["POST"])
def receive():
    try:
        data = request.get_json(force=True, silent=True)
        if not data:
            raw = request.get_data(as_text=True)
            try:
                data = json.loads(raw)
            except Exception:
                return jsonify(code=1, msg="无法解析 JSON"), 400
    except Exception:
        return jsonify(code=1, msg="无法解析请求体"), 400

    raw_json = json.dumps(data, ensure_ascii=False)
    encrypted = data.get("encrypt", "0") == "1"
    secret = CONFIG["SECRET_KEY"]

    if encrypted and secret:
        for key in list(data.keys()):
            if key not in SKIP_DECRYPT_FIELDS and data[key]:
                data[key] = des_decrypt(data[key], secret)

    pay_type = data.get("type", "")
    money = data.get("money", "")
    sign = data.get("sign", "")

    if secret and not verify_sign(pay_type, money, sign, secret):
        app.logger.warning("签名验证失败: type=%s money=%s", pay_type, money)

    db = get_db()
    db.execute(
        """INSERT INTO receipts (type, time, title, money, content, deviceid, sign, encrypt, raw_json)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            pay_type,
            data.get("time", ""),
            data.get("title", ""),
            money,
            data.get("content", ""),
            data.get("deviceid", ""),
            sign,
            data.get("encrypt", "0"),
            raw_json,
        ),
    )
    db.commit()

    app.logger.info("[收款] %s  %s元  %s  %s", pay_type, money, data.get("title", ""), data.get("time", ""))
    return jsonify(code=0, msg="success")

# ---------------------------------------------------------------------------
# API: 查询记录 (JSON)
# ---------------------------------------------------------------------------

@app.route("/api/records")
def api_records():
    page = max(int(request.args.get("page", 1)), 1)
    size = min(int(request.args.get("size", 50)), 200)
    offset = (page - 1) * size

    db = get_db()
    rows = db.execute(
        "SELECT * FROM receipts ORDER BY id DESC LIMIT ? OFFSET ?", (size, offset)
    ).fetchall()
    total = db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0]

    records = [dict(r) for r in rows]
    return jsonify(code=0, total=total, page=page, size=size, records=records)

# ---------------------------------------------------------------------------
# Web 界面
# ---------------------------------------------------------------------------

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>收款通知</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
         background: #f0f2f5; color: #333; }
  .header { background: #fff; padding: 20px 24px; box-shadow: 0 1px 4px rgba(0,0,0,.08);
            display: flex; align-items: center; justify-content: space-between; }
  .header h1 { font-size: 20px; font-weight: 600; }
  .header .stats { font-size: 14px; color: #888; }
  .container { max-width: 960px; margin: 24px auto; padding: 0 16px; }
  .card { background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,.06);
          margin-bottom: 12px; padding: 16px 20px; display: flex; align-items: flex-start;
          gap: 16px; transition: box-shadow .15s; }
  .card:hover { box-shadow: 0 2px 8px rgba(0,0,0,.12); }
  .badge { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 12px;
           font-weight: 500; color: #fff; white-space: nowrap; flex-shrink: 0; }
  .badge-alipay { background: #1677ff; }
  .badge-wechat { background: #07c160; }
  .badge-unionpay { background: #e60012; }
  .badge-bank { background: #fa8c16; }
  .badge-other { background: #8c8c8c; }
  .info { flex: 1; min-width: 0; }
  .info .title { font-size: 15px; font-weight: 500; margin-bottom: 4px; }
  .info .content { font-size: 13px; color: #666; margin-bottom: 6px;
                   overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .info .meta { font-size: 12px; color: #999; display: flex; gap: 12px; flex-wrap: wrap; }
  .money { font-size: 22px; font-weight: 600; color: #f5222d; white-space: nowrap;
           flex-shrink: 0; align-self: center; }
  .pagination { text-align: center; padding: 20px 0; }
  .pagination a, .pagination span {
    display: inline-block; padding: 6px 14px; margin: 0 4px; border-radius: 4px;
    font-size: 14px; text-decoration: none; color: #333; background: #fff;
    box-shadow: 0 1px 2px rgba(0,0,0,.08); }
  .pagination a:hover { background: #e6f4ff; }
  .pagination .current { background: #1677ff; color: #fff; }
  .empty { text-align: center; padding: 80px 0; color: #bbb; font-size: 15px; }
</style>
</head>
<body>
<div class="header">
  <h1>收款通知</h1>
  <div class="stats">共 {{ total }} 条记录</div>
</div>
<div class="container">
{% if records %}
  {% for r in records %}
  <div class="card">
    <span class="badge {{ badge_class(r.type) }}">{{ r.type or '未知' }}</span>
    <div class="info">
      <div class="title">{{ r.title or '-' }}</div>
      <div class="content">{{ r.content or '-' }}</div>
      <div class="meta">
        <span>{{ r.time or r.created_at }}</span>
        <span>{{ r.deviceid }}</span>
      </div>
    </div>
    <div class="money">&yen;{{ r.money or '0' }}</div>
  </div>
  {% endfor %}
  <div class="pagination">
    {% if page > 1 %}<a href="?page={{ page - 1 }}">上一页</a>{% endif %}
    {% for p in range(1, total_pages + 1) %}
      {% if p == page %}<span class="current">{{ p }}</span>
      {% elif (p - page)|abs <= 3 or p == 1 or p == total_pages %}<a href="?page={{ p }}">{{ p }}</a>
      {% elif (p - page)|abs == 4 %}<span>...</span>{% endif %}
    {% endfor %}
    {% if page < total_pages %}<a href="?page={{ page + 1 }}">下一页</a>{% endif %}
  </div>
{% else %}
  <div class="empty">暂无收款记录</div>
{% endif %}
</div>
</body>
</html>"""


def badge_class(pay_type: str) -> str:
    if not pay_type:
        return "badge-other"
    t = pay_type.lower()
    if "alipay" in t:
        return "badge-alipay"
    if "wechat" in t:
        return "badge-wechat"
    if "unionpay" in t:
        return "badge-unionpay"
    if "bank" in t:
        return "badge-bank"
    return "badge-other"


@app.route("/")
def index():
    page = max(int(request.args.get("page", 1)), 1)
    size = 20
    offset = (page - 1) * size

    db = get_db()
    rows = db.execute(
        "SELECT * FROM receipts ORDER BY id DESC LIMIT ? OFFSET ?", (size, offset)
    ).fetchall()
    total = db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0]
    total_pages = max((total + size - 1) // size, 1)

    records = [dict(r) for r in rows]
    return render_template_string(
        HTML_TEMPLATE,
        records=records,
        total=total,
        page=page,
        total_pages=total_pages,
        badge_class=badge_class,
    )

# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------

def parse_args():
    parser = argparse.ArgumentParser(description="收款通知服务端")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址 (默认 0.0.0.0)")
    parser.add_argument("--port", type=int, default=5000, help="监听端口 (默认 5000)")
    parser.add_argument("--secret", default="", help="DES 解密密码 / MD5 签名密钥")
    parser.add_argument("--db", default="receipts.db", help="SQLite 数据库文件路径")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    CONFIG["HOST"] = args.host
    CONFIG["PORT"] = args.port
    CONFIG["SECRET_KEY"] = args.secret
    CONFIG["DB_PATH"] = args.db

    init_db()
    print(f"收款通知服务端启动: http://{args.host}:{args.port}")
    print(f"数据库: {args.db}")
    if args.secret:
        print(f"已启用解密/验签 (密钥长度: {len(args.secret)})")
    else:
        print("未设置密钥，数据将以原文存储")

    app.run(host=args.host, port=args.port, debug=False)
