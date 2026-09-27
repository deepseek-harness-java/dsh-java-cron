#!/usr/bin/env python3
"""Cron Forge E2E: app API + AI stream tool calls.

Usage: python3 cron_forge_e2e.py
Prereq: app on 127.0.0.1:8095, harness on 127.0.0.1:8090,
        plugin dsh-java-cron-plugin installed & activated.
"""
import json
import sys
import time
import urllib.request

APP = "http://127.0.0.1:8095"
results = []


def call(path, payload=None, method=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(APP + path, data=data, method=method or ("POST" if data else "GET"),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def stream_once(agent_id, message):
    body = json.dumps({"agentId": agent_id, "message": message}).encode()
    req = urllib.request.Request(APP + "/api/assistant/stream", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    content, tools, events = "", [], []
    try:
        with urllib.request.urlopen(req, timeout=200) as resp:
            event, data_buf = "message", []
            for raw in resp.read().decode("utf-8", "ignore").splitlines() + [""]:
                if raw.startswith("event:"):
                    event = raw[6:].strip()
                elif raw.startswith("data:"):
                    data_buf.append(raw[5:].strip())
                elif not raw and data_buf:
                    try:
                        payload = json.loads("\n".join(data_buf))
                        events.append(event)
                        if event == "chunk":
                            content += payload.get("content", "")
                        if event == "step_break" and payload.get("toolName"):
                            tools.append(payload["toolName"])
                    except json.JSONDecodeError:
                        pass
                    event, data_buf = "message", []
    except Exception as e:  # noqa: BLE001
        content += f"\nSTREAM-ERROR: {e}"
    return content, sorted(set(tools)), events


def case(name):
    def deco(fn):
        def wrapped():
            start = time.time()
            try:
                detail = fn()
                results.append((name, True, detail, time.time() - start))
                print(f"PASS  {name}  ({time.time()-start:.1f}s)  {detail}")
            except AssertionError as e:
                results.append((name, False, str(e), time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {e}")
            except Exception as e:  # noqa: BLE001
                results.append((name, False, f"{type(e).__name__}: {e}", time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {type(e).__name__}: {e}")
        return wrapped
    return deco


@case("T0 解析与人话解释")
def t0():
    status, body = call("/api/cron/parse", {"expr": "30 2 * * 1-5"})
    assert status == 200, f"parse {status}: {body}"
    assert "02:30" in body["human"], f"human={body['human']}"
    assert "周一" in body["human"] and "周五" in body["human"], f"human={body['human']}"
    assert body["kind"] == "weekly", f"kind={body['kind']}"
    return f"human={body['human']}"


@case("T1 下次触发时间")
def t1():
    status, body = call("/api/cron/next", {"expr": "*/5 * * * *", "count": 3})
    assert status == 200, f"next {status}: {body}"
    assert body["count"] == 3, f"count={body['count']}"
    assert len(body["nextRuns"]) == 3
    return f"next1={body['nextRuns'][0]}"


@case("T2 校验（月末陷阱 + 日周 OR）")
def t2():
    status, body = call("/api/cron/validate", {"expr": "0 0 31 * 1"})
    assert status == 200, f"validate {status}: {body}"
    joined = json.dumps(body, ensure_ascii=False)
    assert body["valid"], f"valid={body['valid']}"
    assert "dom-dow" in joined, "dom-dow warn missing"
    assert "month-length" in joined, "month-length warn missing"
    return f"issues={len(body['issues'])}"


@case("T3 非法表达式报 error")
def t3():
    status, body = call("/api/cron/validate", {"expr": "99 * * * *"})
    assert status == 200, f"validate {status}: {body}"
    assert not body["valid"], f"should be invalid: {body}"
    return "out-of-range rejected"


@case("T4 登记任务 + 去重建议")
def t4():
    call("/api/cron/jobs", {"name": "order-sync", "expr": "0 */2 * * *", "owner": "张三", "description": "订单同步"})
    call("/api/cron/jobs", {"name": "stock-sync", "expr": "0 */2 * * *", "owner": "李四", "description": "库存同步"})
    call("/api/cron/jobs", {"name": "daily-report", "expr": "30 8 * * 1-5", "owner": "王五", "description": "日报"})
    status, body = call("/api/cron/suggest", {})
    assert status == 200, f"suggest {status}: {body}"
    assert body["suggestionCount"] >= 1, f"suggestions={body['suggestions']}"
    types = {s["type"] for s in body["suggestions"]}
    assert "same-expr" in types, f"types={types}"
    return f"jobs={body['jobCount']} suggestions={body['suggestionCount']}"


@case("T5 批量解释")
def t5():
    status, body = call("/api/cron/batch-explain", {"exprs": ["0 9 * * 1-5", "0 0 1 * *", "bad expr"]})
    assert status == 200, f"batch {status}: {body}"
    assert body["count"] == 3
    items = body["items"]
    assert items[0]["ok"] and items[1]["ok"], "valid exprs marked not ok"
    assert not items[2]["ok"], "bad expr should fail"
    return f"ok={sum(1 for i in items if i['ok'])}/3"


@case("T6 AI 对话调用 cron 工具")
def t6():
    content, tools, events = stream_once("e2e-cron-t6", "0 3 * * 1 这个表达式是什么意思？下次触发是什么时候？")
    assert any("cron_" in t for t in tools), f"no cron_ tool called, tools={tools}"
    assert "chunk" in events, "no chunk events"
    assert len(content) > 20, f"content too short: {content[:80]}"
    return f"tools={tools} chars={len(content)}"


@case("T7 段数错误拒绝")
def t7():
    status, body = call("/api/cron/parse", {"expr": "* * * *"})
    assert status == 400, f"should reject: {status} {body}"
    return "4-field rejected with 400"


def main():
    for fn in (t0, t1, t2, t3, t4, t5, t6, t7):
        fn()
    passed = sum(1 for _, ok, _, _ in results if ok)
    print(f"\n{passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
