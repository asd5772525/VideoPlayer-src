#!/usr/bin/env python3
"""
播放源 / 解析接口 并发测速与自动清理（由 .github/workflows/source-check.yml 定时运行）。

- 对 config.json 中每个启用的播放源请求 `?ac=list`（超时 10 秒），能解析出分类或列表即为成功
- 对解析接口请求其地址（非 5xx 即视为可用）
- 结果写入 status.json：latency(ms) / ok / lastCheck / failCount（连续失败次数，成功清零）
- 连续失败达到 3 次：从 config.json 移除，记录到 removed.json（可在后台页面一键恢复）
- 播放源按延迟分档（500ms 一档）重新排序，避免抖动导致每次都重新编译
- 本次全部失败时，先探测一下网络（默认 https://api.github.com，可用环境变量 NET_PROBE_URL 覆盖）：
  网络不通才认为是运行环境问题、不计失败次数；网络正常则照常累计（否则只有一个坏源时永远不会被移除）
- 通过 GITHUB_OUTPUT 输出 config_changed / summary
只用 Python 标准库，无需安装依赖。
"""
import concurrent.futures as cf
import json
import os
import ssl
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

MAX_FAIL = 3
TIMEOUT = 10
UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
NET_PROBE_URL = os.environ.get("NET_PROBE_URL", "https://api.github.com")
ROOT = os.environ.get("VP_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "config.json")
STATUS = os.path.join(ROOT, "status.json")
REMOVED = os.path.join(ROOT, "removed.json")

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE  # 很多采集站证书不规范，只测可用性


def load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def list_url(api):
    return api + ("&" if "?" in api else "?") + "ac=list"


def fetch(url, limit=2 * 1024 * 1024):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    start = time.time()
    with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as r:
        body = r.read(limit)
        return r.status, body, int((time.time() - start) * 1000)


def test_source(api):
    try:
        status, body, ms = fetch(list_url(api))
        if status >= 400:
            return -1, f"HTTP {status}"
        text = body.decode("utf-8", "ignore").lstrip("\ufeff \r\n\t")
        if text.startswith("<"):
            root = ET.fromstring(text)
            n = len(root.findall(".//ty")) + len(root.findall(".//video"))
        else:
            d = json.loads(text)
            n = len(d.get("class") or []) + len(d.get("list") or [])
        return (ms, "") if n > 0 else (-1, "无分类或列表数据")
    except Exception as e:  # noqa
        return -1, str(e)[:120]


def test_parse(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-1023"})
        start = time.time()
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as r:
            r.read(1024)
            return int((time.time() - start) * 1000), ""
    except urllib.error.HTTPError as e:
        return (-1, f"HTTP {e.code}") if e.code >= 500 else (100, "")
    except Exception as e:  # noqa
        return -1, str(e)[:120]


def network_ok():
    try:
        req = urllib.request.Request(NET_PROBE_URL, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as r:
            return r.status < 500
    except urllib.error.HTTPError as e:
        return e.code < 500
    except Exception:  # noqa
        return False


def main():
    cfg = load(CFG, {})
    status = load(STATUS, {"sources": {}, "parses": {}})
    removed = load(REMOVED, [])
    now = int(time.time())

    sources = [s for s in cfg.get("sources", []) if s.get("api")]
    parses = [p for p in cfg.get("parses", []) if p.get("url")]
    jobs = [("sources", s["api"], s) for s in sources if s.get("enabled", True)] + \
           [("parses", p["url"], p) for p in parses if p.get("enabled", True)]

    results = {}
    with cf.ThreadPoolExecutor(max_workers=16) as ex:
        futs = {ex.submit(test_source if kind == "sources" else test_parse, url): (kind, url) for kind, url, _ in jobs}
        for f in cf.as_completed(futs):
            results[futs[f]] = f.result()

    all_failed = bool(results) and all(ms < 0 for ms, _ in results.values())
    if all_failed:
        if network_ok():
            print("⚠️ 全部失败，但网络正常：照常累计失败次数")
            all_failed = False
        else:
            print("⚠️ 全部失败且网络不通，判定为运行环境网络问题，本次不累计失败次数")

    remove_keys = {"sources": set(), "parses": set()}
    for (kind, url), (ms, err) in results.items():
        old = status.setdefault(kind, {}).get(url, {})
        fail = 0 if ms >= 0 else (old.get("failCount", 0) if all_failed else old.get("failCount", 0) + 1)
        status[kind][url] = {"latency": ms, "ok": ms >= 0, "lastCheck": now, "failCount": fail, "error": err}
        print(f"[{kind}] {'✅' if ms >= 0 else '❌'} {ms:>6}ms fail={fail} {url} {err}")
        if fail >= MAX_FAIL:
            remove_keys[kind].add(url)

    changed = False
    summary = []
    for kind, field in (("sources", "api"), ("parses", "url")):
        keep, gone = [], []
        for item in cfg.get(kind, []):
            (gone if item.get(field) in remove_keys[kind] else keep).append(item)
        for g in gone:
            removed.append({"kind": kind, "item": g, "removedAt": now,
                            "reason": f"连续 {MAX_FAIL} 次测速失败：{status[kind][g[field]].get('error', '')}"})
            status[kind].pop(g[field], None)
            summary.append(f"自动移除{'播放源' if kind == 'sources' else '解析'}：{g.get('name') or g[field]}")
        if gone:
            changed = True
        # 按延迟分档重排（失败 / 未测置底），同档保持原顺序
        def rank(item, kind=kind, field=field):
            st = status.get(kind, {}).get(item.get(field), {})
            lat = st.get("latency", -1)
            return (lat // 500) if lat is not None and lat >= 0 else 10_000
        new_order = sorted(keep, key=rank)
        if [i.get(field) for i in new_order] != [i.get(field) for i in cfg.get(kind, [])]:
            if not gone:
                summary.append(f"{'播放源' if kind == 'sources' else '解析接口'}按速度重新排序")
            changed = True
        for i, item in enumerate(new_order):
            item["order"] = i + 1
        cfg[kind] = new_order

    # 清理已不在配置中的状态
    for kind, field in (("sources", "api"), ("parses", "url")):
        valid = {i.get(field) for i in cfg.get(kind, [])}
        status[kind] = {k: v for k, v in status.get(kind, {}).items() if k in valid}
    status["updatedAt"] = now

    save(STATUS, status)
    save(REMOVED, removed)
    if changed:
        if cfg.get("sources") and not any(s.get("default") for s in cfg["sources"]):
            cfg["sources"][0]["default"] = True
        save(CFG, cfg)

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"config_changed={'true' if changed else 'false'}\n")
            f.write(f"summary={'；'.join(summary) or '播放源测速更新'}\n")
    print("config.json 已变化" if changed else "config.json 无变化")


if __name__ == "__main__":
    main()
