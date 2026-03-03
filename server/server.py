"""
Receipt Notice Server v2.0
"""

import csv
import hashlib
import json
import logging
import os
import sqlite3
import threading
from binascii import unhexlify
from datetime import datetime
from functools import wraps
from io import StringIO

from flask import (Flask, Response, g, jsonify, redirect,
                   render_template_string, request, session, url_for)

# ---------------------------------------------------------------------------
# Paths & Config
# ---------------------------------------------------------------------------
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 5000,
    "secret": "",
    "db_path": "data/receipts.db",
    "log_file": "data/server.log",
    "web_password": "",
    "api_token": "",
    "webhook_urls": [],
}


def load_config():
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_CONFIG, f, indent=4, ensure_ascii=False)
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    for k, v in DEFAULT_CONFIG.items():
        cfg.setdefault(k, v)
    if not os.path.isabs(cfg["db_path"]):
        cfg["db_path"] = os.path.join(BASE_DIR, cfg["db_path"])
    if cfg["log_file"] and not os.path.isabs(cfg["log_file"]):
        cfg["log_file"] = os.path.join(BASE_DIR, cfg["log_file"])
    return cfg


os.makedirs(DATA_DIR, exist_ok=True)
CONFIG = load_config()

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
_handlers = [logging.StreamHandler()]
if CONFIG["log_file"]:
    os.makedirs(os.path.dirname(CONFIG["log_file"]), exist_ok=True)
    _handlers.append(logging.FileHandler(CONFIG["log_file"], encoding="utf-8"))
logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(message)s",
                    handlers=_handlers)
logger = logging.getLogger("server")

# ---------------------------------------------------------------------------
# Flask
# ---------------------------------------------------------------------------
app = Flask(__name__)
app.secret_key = (CONFIG.get("secret") or "receipt-notice-default-key") + "-session"

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------
def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(CONFIG["db_path"])
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(exc):
    db = g.pop("db", None)
    if db:
        db.close()


def init_db():
    os.makedirs(os.path.dirname(CONFIG["db_path"]), exist_ok=True)
    conn = sqlite3.connect(CONFIG["db_path"])
    conn.execute("""CREATE TABLE IF NOT EXISTS receipts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        type TEXT, time TEXT, title TEXT, money TEXT,
        content TEXT, deviceid TEXT, sign TEXT, encrypt TEXT,
        raw_json TEXT,
        created_at TEXT DEFAULT (datetime('now','localtime'))
    )""")
    conn.commit()
    conn.close()

# ---------------------------------------------------------------------------
# Crypto
# ---------------------------------------------------------------------------
def des_decrypt(hex_cipher, password):
    try:
        from Crypto.Cipher import DES
        key = password.encode("utf-8")[:8].ljust(8, b"\0")
        data = DES.new(key, DES.MODE_CBC, key).decrypt(unhexlify(hex_cipher))
        pad = data[-1]
        if 1 <= pad <= 8 and all(b == pad for b in data[-pad:]):
            data = data[:-pad]
        return data.decode("utf-8")
    except Exception:
        return hex_cipher


def md5(s):
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def verify_sign(pay_type, money, sign, secret):
    if not sign:
        return True
    expected = md5(md5(pay_type + money) + secret) if secret else md5(md5(pay_type + money))
    return expected.lower() == sign.lower()

# ---------------------------------------------------------------------------
# Webhook
# ---------------------------------------------------------------------------
def fire_webhooks(data):
    urls = CONFIG.get("webhook_urls") or []
    if not urls:
        return
    import urllib.request
    payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
    for url in urls:
        def _send(u=url):
            try:
                req = urllib.request.Request(
                    u, data=payload,
                    headers={"Content-Type": "application/json"})
                urllib.request.urlopen(req, timeout=10)
                logger.info("Webhook OK: %s", u)
            except Exception as e:
                logger.warning("Webhook fail %s: %s", u, e)
        threading.Thread(target=_send, daemon=True).start()

# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------
def require_web_auth(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        if not CONFIG.get("web_password"):
            return f(*args, **kwargs)
        if session.get("authed"):
            return f(*args, **kwargs)
        return redirect(url_for("login"))
    return wrapper


def require_api_token(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        token = CONFIG.get("api_token")
        if not token:
            return f(*args, **kwargs)
        req_token = request.headers.get("X-API-Token") or request.args.get("token")
        if req_token == token:
            return f(*args, **kwargs)
        return jsonify(code=401, msg="Invalid or missing API token"), 401
    return wrapper

# ---------------------------------------------------------------------------
# Query helpers
# ---------------------------------------------------------------------------
def build_filter():
    clauses, params = [], []
    if request.args.get("type"):
        clauses.append("type LIKE ?")
        params.append("%" + request.args["type"] + "%")
    if request.args.get("date_from"):
        clauses.append("created_at >= ?")
        params.append(request.args["date_from"])
    if request.args.get("date_to"):
        clauses.append("created_at <= ?")
        params.append(request.args["date_to"] + " 23:59:59")
    if request.args.get("keyword"):
        clauses.append("(title LIKE ? OR content LIKE ?)")
        kw = "%" + request.args["keyword"] + "%"
        params.extend([kw, kw])
    if request.args.get("min_money"):
        clauses.append("CAST(money AS REAL) >= ?")
        params.append(float(request.args["min_money"]))
    if request.args.get("max_money"):
        clauses.append("CAST(money AS REAL) <= ?")
        params.append(float(request.args["max_money"]))
    where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
    return where, params


def get_stats():
    db = get_db()
    today = datetime.now().strftime("%Y-%m-%d")
    month = datetime.now().strftime("%Y-%m")
    total_count = db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0]
    today_sum = db.execute(
        "SELECT COALESCE(SUM(CAST(money AS REAL)),0) FROM receipts WHERE created_at LIKE ?",
        (today + "%",)).fetchone()[0]
    month_sum = db.execute(
        "SELECT COALESCE(SUM(CAST(money AS REAL)),0) FROM receipts WHERE created_at LIKE ?",
        (month + "%",)).fetchone()[0]
    total_sum = db.execute(
        "SELECT COALESCE(SUM(CAST(money AS REAL)),0) FROM receipts").fetchone()[0]
    devices = db.execute(
        "SELECT COUNT(DISTINCT deviceid) FROM receipts WHERE deviceid!=''").fetchone()[0]
    type_rows = db.execute(
        "SELECT type, COUNT(*) as cnt, COALESCE(SUM(CAST(money AS REAL)),0) as total "
        "FROM receipts GROUP BY type ORDER BY total DESC").fetchall()
    by_type = [{"type": r["type"] or "unknown", "count": r["cnt"],
                "total": round(r["total"], 2)} for r in type_rows]
    return {
        "total_count": total_count,
        "today_income": round(today_sum, 2),
        "month_income": round(month_sum, 2),
        "total_income": round(total_sum, 2),
        "devices": devices,
        "by_type": by_type,
    }


def badge_cls(t):
    if not t:
        return "badge-other"
    t = t.lower()
    if "alipay" in t:
        return "badge-alipay"
    if "wechat" in t:
        return "badge-wechat"
    if "unionpay" in t:
        return "badge-union"
    return "badge-other"

# ---------------------------------------------------------------------------
# API Routes
# ---------------------------------------------------------------------------
SKIP_DECRYPT = {"sign", "encrypt", "url"}


@app.route("/api/receive", methods=["POST"])
def api_receive():
    try:
        data = request.get_json(force=True, silent=True)
        if not data:
            return jsonify(code=1, msg="Cannot parse JSON"), 400
    except Exception:
        return jsonify(code=1, msg="Bad request"), 400

    raw_json = json.dumps(data, ensure_ascii=False)
    secret = CONFIG["secret"]

    if data.get("encrypt") == "1" and secret:
        for k in list(data.keys()):
            if k not in SKIP_DECRYPT and data[k]:
                data[k] = des_decrypt(data[k], secret)

    pay_type = data.get("type", "")
    money = data.get("money", "")
    sign = data.get("sign", "")

    if secret and not verify_sign(pay_type, money, sign, secret):
        logger.warning("Sign verify failed: type=%s money=%s", pay_type, money)

    db = get_db()
    db.execute(
        "INSERT INTO receipts (type,time,title,money,content,deviceid,sign,encrypt,raw_json)"
        " VALUES (?,?,?,?,?,?,?,?,?)",
        (pay_type, data.get("time", ""), data.get("title", ""), money,
         data.get("content", ""), data.get("deviceid", ""), sign,
         data.get("encrypt", "0"), raw_json))
    db.commit()

    logger.info("[Payment] %s  %s yuan  %s", pay_type, money, data.get("title", ""))
    fire_webhooks(data)
    return jsonify(code=0, msg="success")


@app.route("/api/records")
@require_api_token
def api_records():
    db = get_db()
    page = max(int(request.args.get("page", 1)), 1)
    size = min(int(request.args.get("size", 50)), 200)
    where, params = build_filter()
    total = db.execute("SELECT COUNT(*) FROM receipts " + where, params).fetchone()[0]
    rows = db.execute(
        "SELECT * FROM receipts " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
        params + [size, (page - 1) * size]).fetchall()
    return jsonify(code=0, total=total, page=page, size=size,
                   records=[dict(r) for r in rows])


@app.route("/api/stats")
@require_api_token
def api_stats():
    return jsonify(code=0, data=get_stats())


@app.route("/api/export")
@require_api_token
def api_export():
    db = get_db()
    fmt = request.args.get("format", "csv")
    where, params = build_filter()
    rows = db.execute(
        "SELECT * FROM receipts " + where + " ORDER BY id DESC", params).fetchall()
    records = [dict(r) for r in rows]

    if fmt == "json":
        return Response(
            json.dumps(records, ensure_ascii=False, indent=2),
            mimetype="application/json",
            headers={"Content-Disposition": "attachment; filename=receipts.json"})

    output = StringIO()
    if records:
        w = csv.DictWriter(output, fieldnames=records[0].keys())
        w.writeheader()
        w.writerows(records)
    return Response(
        output.getvalue(), mimetype="text/csv",
        headers={"Content-Disposition": "attachment; filename=receipts.csv"})


@app.route("/api/health")
def api_health():
    return jsonify(code=0, status="running", time=datetime.now().isoformat())


@app.route("/api/records/<int:rid>", methods=["DELETE"])
@require_api_token
def api_delete(rid):
    db = get_db()
    db.execute("DELETE FROM receipts WHERE id=?", (rid,))
    db.commit()
    return jsonify(code=0, msg="deleted")

# ---------------------------------------------------------------------------
# Web Routes
# ---------------------------------------------------------------------------
@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        if request.form.get("password") == CONFIG.get("web_password"):
            session["authed"] = True
            return redirect("/")
        return render_template_string(LOGIN_TMPL, error=True)
    return render_template_string(LOGIN_TMPL, error=False)


@app.route("/logout")
def logout():
    session.pop("authed", None)
    return redirect("/login")


@app.route("/")
@require_web_auth
def page_dashboard():
    stats = get_stats()
    db = get_db()
    rows = db.execute("SELECT * FROM receipts ORDER BY id DESC LIMIT 10").fetchall()
    recent = [dict(r) for r in rows]
    body = render_template_string(DASHBOARD_TMPL, stats=stats, records=recent, badge_cls=badge_cls)
    return render_template_string(LAYOUT, title="Dashboard", active="dashboard",
                                  body=body, config=CONFIG)


@app.route("/records")
@require_web_auth
def page_records():
    db = get_db()
    page = max(int(request.args.get("page", 1)), 1)
    size = 20
    where, params = build_filter()
    total = db.execute("SELECT COUNT(*) FROM receipts " + where, params).fetchone()[0]
    rows = db.execute(
        "SELECT * FROM receipts " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
        params + [size, (page - 1) * size]).fetchall()
    total_pages = max((total + size - 1) // size, 1)
    records = [dict(r) for r in rows]
    body = render_template_string(RECORDS_TMPL, records=records, page=page,
                                  total=total, total_pages=total_pages,
                                  badge_cls=badge_cls, req=request)
    return render_template_string(LAYOUT, title="Records", active="records",
                                  body=body, config=CONFIG)


@app.route("/docs")
@require_web_auth
def page_docs():
    body = render_template_string(DOCS_TMPL, config=CONFIG)
    return render_template_string(LAYOUT, title="API Docs", active="docs",
                                  body=body, config=CONFIG)


@app.route("/settings")
@require_web_auth
def page_settings():
    safe = {}
    for k, v in CONFIG.items():
        if k in ("secret", "web_password", "api_token") and v:
            safe[k] = "***"
        else:
            safe[k] = v
    body = render_template_string(SETTINGS_TMPL, config=safe)
    return render_template_string(LAYOUT, title="Settings", active="settings",
                                  body=body, config=CONFIG)

# ---------------------------------------------------------------------------
# Templates
# ---------------------------------------------------------------------------
CSS = """
:root{--sb:#1e1e2d;--pri:#3699ff;--red:#f64e60;--grn:#1bc5bd;--wrn:#ffa800;
--bg:#f2f3f8;--card:#fff;--txt:#181c32;--muted:#b5b5c3;--border:#ebedf3;}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--txt)}
.sb{position:fixed;left:0;top:0;bottom:0;width:230px;background:var(--sb);padding:20px 0;z-index:100;overflow-y:auto}
.sb .logo{color:#fff;font-size:17px;font-weight:700;padding:0 20px 20px;border-bottom:1px solid rgba(255,255,255,.07)}
.sb .nav{padding:12px 0}
.sb a{display:block;padding:10px 20px;color:#9899ac;text-decoration:none;font-size:14px;transition:.15s}
.sb a:hover{color:#fff;background:rgba(255,255,255,.04)}
.sb a.on{color:#fff;background:rgba(54,153,255,.15);border-right:3px solid var(--pri)}
.mn{margin-left:230px;min-height:100vh}
.hd{background:var(--card);padding:14px 24px;border-bottom:1px solid var(--border);display:flex;align-items:center;justify-content:space-between}
.hd h1{font-size:17px;font-weight:600}
.ct{padding:24px}
.sg{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px;margin-bottom:24px}
.sc{background:var(--card);border-radius:8px;padding:18px;box-shadow:0 0 20px rgba(0,0,0,.03)}
.sc .lb{font-size:12px;color:var(--muted);margin-bottom:6px}
.sc .vl{font-size:22px;font-weight:700}
.sc .vl.money{color:var(--red)}.sc .vl.count{color:var(--pri)}.sc .vl.green{color:var(--grn)}
.cd{background:var(--card);border-radius:8px;box-shadow:0 0 20px rgba(0,0,0,.03);overflow:hidden;margin-bottom:24px}
.cd-h{padding:14px 18px;border-bottom:1px solid var(--border);font-weight:600;display:flex;align-items:center;justify-content:space-between;font-size:14px}
table{width:100%;border-collapse:collapse}
th{text-align:left;padding:10px 14px;font-size:12px;color:var(--muted);font-weight:500;border-bottom:1px solid var(--border);background:#fafbfc}
td{padding:10px 14px;font-size:13px;border-bottom:1px solid #f4f4f4}
tr:hover td{background:#f8f9fb}
.badge{display:inline-block;padding:3px 8px;border-radius:3px;font-size:11px;font-weight:600;color:#fff}
.badge-alipay{background:#1677ff}.badge-wechat{background:#07c160}.badge-union{background:#e60012}.badge-other{background:#8c8c8c}
.money-cell{font-weight:600;color:var(--red);white-space:nowrap}
.fl{display:flex;gap:10px;flex-wrap:wrap;padding:14px 18px;border-bottom:1px solid var(--border);align-items:center}
.fl input,.fl select{padding:6px 10px;border:1px solid #e4e6ef;border-radius:4px;font-size:13px}
.fl select{background:#fff}
.btn{display:inline-block;padding:7px 14px;border-radius:4px;font-size:12px;font-weight:500;cursor:pointer;border:none;text-decoration:none;color:#fff}
.btn-pri{background:var(--pri)}.btn-grn{background:var(--grn)}.btn-red{background:var(--red)}
.btn-sm{padding:4px 8px;font-size:11px}
.pg{display:flex;justify-content:center;gap:4px;padding:14px}
.pg a,.pg span{padding:5px 11px;border-radius:4px;font-size:13px;text-decoration:none;color:var(--txt);background:#fff;border:1px solid #e4e6ef}
.pg a:hover{background:#e8f0fe}.pg .on{background:var(--pri);color:#fff;border-color:var(--pri)}
.empty{text-align:center;padding:50px 0;color:var(--muted)}
.text-m{color:var(--muted);font-size:13px}
code{background:#f1f1f4;padding:2px 6px;border-radius:3px;font-size:13px}
pre{background:#1e1e2d;color:#e4e6ef;padding:14px;border-radius:6px;overflow-x:auto;font-size:13px;line-height:1.6;margin:8px 0}
.api-c{background:var(--card);border-radius:8px;padding:18px;margin-bottom:14px;box-shadow:0 0 20px rgba(0,0,0,.03)}
.api-c .mt{display:inline-block;padding:2px 7px;border-radius:3px;font-size:11px;font-weight:700;color:#fff;margin-right:6px}
.mt.get{background:var(--grn)}.mt.post{background:var(--pri)}.mt.delete{background:var(--red)}
.api-c .path{font-family:monospace;font-size:14px;font-weight:600}
.api-c .desc{color:var(--muted);margin-top:6px;font-size:13px}
.bar{display:flex;align-items:flex-end;gap:6px;margin-top:8px}
.bar-item{display:flex;flex-direction:column;align-items:center;gap:2px;font-size:11px;color:var(--muted)}
.bar-fill{width:32px;border-radius:3px 3px 0 0;min-height:4px}
.cfg-row{display:flex;padding:10px 0;border-bottom:1px solid #f4f4f4;font-size:14px}
.cfg-row .k{width:180px;color:var(--muted);font-weight:500}.cfg-row .v{flex:1;word-break:break-all}
"""

LAYOUT = """<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{ title }} - ReceiptNotice</title>
<style>""" + CSS + """</style></head>
<body>
<div class="sb">
  <div class="logo">ReceiptNotice</div>
  <nav class="nav">
    <a href="/" class="{{ 'on' if active=='dashboard' }}">仪表盘</a>
    <a href="/records" class="{{ 'on' if active=='records' }}">收款记录</a>
    <a href="/docs" class="{{ 'on' if active=='docs' }}">接口文档</a>
    <a href="/settings" class="{{ 'on' if active=='settings' }}">系统配置</a>
  </nav>
  {% if config.web_password %}
  <div style="position:absolute;bottom:12px;left:0;right:0;padding:0 20px">
    <a href="/logout" style="color:#f64e60">退出登录</a>
  </div>
  {% endif %}
</div>
<div class="mn">
  <div class="hd"><h1>{{ title }}</h1><span class="text-m">{{ now }}</span></div>
  <div class="ct">{{ body|safe }}</div>
</div>
</body></html>"""

LOGIN_TMPL = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Login</title>
<style>
body{display:flex;justify-content:center;align-items:center;min-height:100vh;background:#f2f3f8;font-family:-apple-system,sans-serif}
.box{background:#fff;padding:32px;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.08);width:320px;text-align:center}
h2{margin-bottom:20px;font-size:18px}
input{width:100%;padding:10px;border:1px solid #e4e6ef;border-radius:4px;font-size:14px;margin-bottom:12px}
button{width:100%;padding:10px;background:#3699ff;color:#fff;border:none;border-radius:4px;font-size:14px;cursor:pointer}
.err{color:#f64e60;font-size:13px;margin-bottom:8px}
</style></head><body>
<div class="box">
<h2>ReceiptNotice</h2>
{% if error %}<div class="err">密码错误</div>{% endif %}
<form method="POST">
<input type="password" name="password" placeholder="输入管理密码" autofocus>
<button type="submit">登录</button>
</form></div></body></html>"""

DASHBOARD_TMPL = """
<div class="sg">
  <div class="sc"><div class="lb">今日收入</div><div class="vl money">&yen;{{ "%.2f"|format(stats.today_income) }}</div></div>
  <div class="sc"><div class="lb">本月收入</div><div class="vl money">&yen;{{ "%.2f"|format(stats.month_income) }}</div></div>
  <div class="sc"><div class="lb">累计收入</div><div class="vl money">&yen;{{ "%.2f"|format(stats.total_income) }}</div></div>
  <div class="sc"><div class="lb">总记录数</div><div class="vl count">{{ stats.total_count }}</div></div>
  <div class="sc"><div class="lb">活跃设备</div><div class="vl green">{{ stats.devices }}</div></div>
</div>

{% if stats.by_type %}
<div class="cd">
  <div class="cd-h">收入分布</div>
  <div style="padding:18px">
    <div class="bar" style="height:120px;align-items:flex-end">
    {% set max_val = stats.by_type[0].total if stats.by_type else 1 %}
    {% for t in stats.by_type %}
      <div class="bar-item" style="flex:1">
        <span>&yen;{{ "%.0f"|format(t.total) }}</span>
        <div class="bar-fill {{ badge_cls(t.type) }}" style="height:{{ (t.total/max_val*100) if max_val else 0 }}%;width:100%"></div>
        <span>{{ t.type or 'other' }}</span>
      </div>
    {% endfor %}
    </div>
  </div>
</div>
{% endif %}

<div class="cd">
  <div class="cd-h">最近收款 <a href="/records" class="btn btn-pri btn-sm">查看全部</a></div>
  {% if records %}
  <table>
    <tr><th>类型</th><th>金额</th><th>标题</th><th>内容</th><th>时间</th><th>设备</th></tr>
    {% for r in records %}
    <tr>
      <td><span class="badge {{ badge_cls(r.type) }}">{{ r.type or '-' }}</span></td>
      <td class="money-cell">&yen;{{ r.money or '0' }}</td>
      <td>{{ r.title or '-' }}</td>
      <td class="text-m" style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ r.content or '-' }}</td>
      <td class="text-m">{{ r.time or r.created_at }}</td>
      <td class="text-m">{{ r.deviceid or '-' }}</td>
    </tr>
    {% endfor %}
  </table>
  {% else %}
  <div class="empty">暂无收款记录</div>
  {% endif %}
</div>"""

RECORDS_TMPL = """
<div class="cd">
  <div class="cd-h">收款记录 (共 {{ total }} 条)
    <div style="display:flex;gap:6px">
      <a href="/api/export?format=csv{% for k,v in req.args.items() if k!='page' %}&{{ k }}={{ v }}{% endfor %}" class="btn btn-grn btn-sm">导出 CSV</a>
      <a href="/api/export?format=json{% for k,v in req.args.items() if k!='page' %}&{{ k }}={{ v }}{% endfor %}" class="btn btn-pri btn-sm">导出 JSON</a>
    </div>
  </div>
  <form class="fl" method="GET" action="/records">
    <select name="type"><option value="">全部类型</option>
      <option value="alipay" {{ 'selected' if req.args.get('type')=='alipay' }}>支付宝</option>
      <option value="wechat" {{ 'selected' if req.args.get('type')=='wechat' }}>微信</option>
      <option value="unionpay" {{ 'selected' if req.args.get('type')=='unionpay' }}>银联</option>
    </select>
    <input type="date" name="date_from" value="{{ req.args.get('date_from','') }}" placeholder="开始日期">
    <input type="date" name="date_to" value="{{ req.args.get('date_to','') }}" placeholder="结束日期">
    <input type="text" name="keyword" value="{{ req.args.get('keyword','') }}" placeholder="关键词搜索">
    <input type="number" name="min_money" value="{{ req.args.get('min_money','') }}" placeholder="最小金额" step="0.01" style="width:90px">
    <input type="number" name="max_money" value="{{ req.args.get('max_money','') }}" placeholder="最大金额" step="0.01" style="width:90px">
    <button type="submit" class="btn btn-pri">搜索</button>
    <a href="/records" class="btn" style="background:#e4e6ef;color:#333">重置</a>
  </form>
  {% if records %}
  <table>
    <tr><th>#</th><th>类型</th><th>金额</th><th>标题</th><th>内容</th><th>时间</th><th>设备</th></tr>
    {% for r in records %}
    <tr>
      <td class="text-m">{{ r.id }}</td>
      <td><span class="badge {{ badge_cls(r.type) }}">{{ r.type or '-' }}</span></td>
      <td class="money-cell">&yen;{{ r.money or '0' }}</td>
      <td>{{ r.title or '-' }}</td>
      <td class="text-m" style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ r.content or '-' }}</td>
      <td class="text-m" style="white-space:nowrap">{{ r.time or r.created_at }}</td>
      <td class="text-m">{{ r.deviceid or '-' }}</td>
    </tr>
    {% endfor %}
  </table>
  <div class="pg">
    {% if page > 1 %}<a href="?page={{ page-1 }}{% for k,v in req.args.items() if k!='page' %}&{{ k }}={{ v }}{% endfor %}">上一页</a>{% endif %}
    {% for p in range(1, total_pages+1) %}
      {% if p == page %}<span class="on">{{ p }}</span>
      {% elif (p-page)|abs <= 2 or p == 1 or p == total_pages %}<a href="?page={{ p }}{% for k,v in req.args.items() if k!='page' %}&{{ k }}={{ v }}{% endfor %}">{{ p }}</a>
      {% elif (p-page)|abs == 3 %}<span>...</span>{% endif %}
    {% endfor %}
    {% if page < total_pages %}<a href="?page={{ page+1 }}{% for k,v in req.args.items() if k!='page' %}&{{ k }}={{ v }}{% endfor %}">下一页</a>{% endif %}
  </div>
  {% else %}
  <div class="empty">无匹配记录</div>
  {% endif %}
</div>"""

DOCS_TMPL = """
<div class="api-c">
  <span class="mt post">POST</span><span class="path">/api/receive</span>
  <div class="desc">接收 APP 推送的收款通知（核心接口，填入 APP 的推送地址）</div>
  <pre>{
  "type": "alipay",
  "time": "2026-03-04 14:30:00",
  "title": "支付宝收款",
  "money": "12.50",
  "content": "成功收款12.50元",
  "sign": "a1b2c3...",
  "deviceid": "device-001",
  "encrypt": "0"
}</pre>
</div>

<div class="api-c">
  <span class="mt get">GET</span><span class="path">/api/records</span>
  <div class="desc">查询收款记录，支持分页和筛选</div>
  <pre>GET /api/records?page=1&size=20&type=alipay&date_from=2026-01-01&date_to=2026-12-31&keyword=付款&min_money=10&max_money=100</pre>
</div>

<div class="api-c">
  <span class="mt get">GET</span><span class="path">/api/stats</span>
  <div class="desc">获取统计数据：今日/本月/累计收入、设备数、类型分布</div>
  <pre>GET /api/stats</pre>
</div>

<div class="api-c">
  <span class="mt get">GET</span><span class="path">/api/export</span>
  <div class="desc">导出收款记录为 CSV 或 JSON 文件，支持筛选参数</div>
  <pre>GET /api/export?format=csv
GET /api/export?format=json&type=wechat&date_from=2026-03-01</pre>
</div>

<div class="api-c">
  <span class="mt delete">DELETE</span><span class="path">/api/records/&lt;id&gt;</span>
  <div class="desc">删除指定 ID 的收款记录</div>
  <pre>DELETE /api/records/42</pre>
</div>

<div class="api-c">
  <span class="mt get">GET</span><span class="path">/api/health</span>
  <div class="desc">健康检查，返回服务运行状态</div>
  <pre>GET /api/health</pre>
</div>

{% if config.api_token %}
<div class="cd" style="margin-top:20px">
  <div class="cd-h">认证说明</div>
  <div style="padding:18px;font-size:14px;line-height:1.8">
    本服务已启用 API Token 认证，调用接口时需附带 Token：
    <pre>curl -H "X-API-Token: YOUR_TOKEN" http://HOST:PORT/api/records</pre>
    或通过 URL 参数传递：
    <pre>http://HOST:PORT/api/records?token=YOUR_TOKEN</pre>
    <code>/api/receive</code> 和 <code>/api/health</code> 不需要 Token。
  </div>
</div>
{% endif %}
"""

SETTINGS_TMPL = """
<div class="cd">
  <div class="cd-h">当前配置 <span class="text-m">修改 config.json 后重启服务生效</span></div>
  <div style="padding:18px">
    {% for k, v in config.items() %}
    <div class="cfg-row">
      <div class="k">{{ k }}</div>
      <div class="v">{% if v is iterable and v is not string %}<code>{{ v|tojson }}</code>{% elif v == '***' %}<code style="color:#f64e60">***</code>{% elif v %}{{ v }}{% else %}<span class="text-m">（未设置）</span>{% endif %}</div>
    </div>
    {% endfor %}
  </div>
</div>

<div class="cd">
  <div class="cd-h">配置说明</div>
  <div style="padding:18px;font-size:14px;line-height:2">
    <div class="cfg-row"><div class="k">host</div><div class="v">监听地址，0.0.0.0 表示所有网卡</div></div>
    <div class="cfg-row"><div class="k">port</div><div class="v">监听端口</div></div>
    <div class="cfg-row"><div class="k">secret</div><div class="v">DES 解密密钥 / MD5 签名密钥，需与 APP 端一致</div></div>
    <div class="cfg-row"><div class="k">db_path</div><div class="v">SQLite 数据库文件路径</div></div>
    <div class="cfg-row"><div class="k">log_file</div><div class="v">日志文件路径</div></div>
    <div class="cfg-row"><div class="k">web_password</div><div class="v">Web 管理界面登录密码，留空则不需要登录</div></div>
    <div class="cfg-row"><div class="k">api_token</div><div class="v">API 访问令牌，留空则不验证</div></div>
    <div class="cfg-row"><div class="k">webhook_urls</div><div class="v">收到付款后自动转发的 URL 列表 (数组格式)</div></div>
  </div>
</div>

<div class="cd">
  <div class="cd-h">数据目录</div>
  <div style="padding:18px;font-size:14px;line-height:2">
    所有数据文件统一存放在 <code>data/</code> 目录：
    <pre>server/
├── config.json        配置文件
├── server.py          服务端程序
├── start.bat          一键启动
├── requirements.txt   依赖清单
└── data/
    ├── receipts.db    数据库
    └── server.log     运行日志</pre>
  </div>
</div>
"""

# ---------------------------------------------------------------------------
# Template context
# ---------------------------------------------------------------------------
@app.context_processor
def inject_now():
    return {"now": datetime.now().strftime("%Y-%m-%d %H:%M")}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    init_db()
    logger.info("Server starting: http://%s:%d", CONFIG["host"], CONFIG["port"])
    logger.info("Database: %s", CONFIG["db_path"])
    if CONFIG["secret"]:
        logger.info("Encryption: ON")
    if CONFIG["web_password"]:
        logger.info("Web auth: ON")
    if CONFIG["api_token"]:
        logger.info("API token: ON")
    if CONFIG["webhook_urls"]:
        logger.info("Webhooks: %d URL(s)", len(CONFIG["webhook_urls"]))

    print("=" * 50)
    print(f"  ReceiptNotice Server v2.0")
    print(f"  http://localhost:{CONFIG['port']}")
    print("=" * 50)

    app.run(host=CONFIG["host"], port=CONFIG["port"], debug=False)
