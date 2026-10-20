#!/usr/bin/env python3
"""Reproducible local lifecycle demo. Python 3.10+, standard library only."""
import argparse
import json
import os
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def read_env():
    path = ROOT / ".env"
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key] = value.strip().strip('"')
    values.update(os.environ)
    return values


def request(base, method, path, data=None, token=None, key=None, expected=(200, 201, 202)):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            content = response.read()
            if response.status not in expected:
                raise RuntimeError(f"{method} {path}: unexpected HTTP {response.status}")
            return json.loads(content) if content else {}
    except urllib.error.HTTPError as exc:
        # Error bodies contain useful Problem Details, never print request credentials.
        raise RuntimeError(f"{method} {path}: HTTP {exc.code}: {exc.read().decode()[:600]}") from exc


def wait_for(description, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.5)
    raise TimeoutError("Timed out waiting for " + description)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--receiver", default="http://localhost:8090")
    parser.add_argument("--destination", default="http://receiver:8090/webhook")
    parser.add_argument("--prepare-load", action="store_true", help="Create credentials and endpoint only; save .local/demo-secrets.json")
    args = parser.parse_args()
    env = read_env()
    control_token = env.get("RECEIVER_CONTROL_TOKEN", "")
    if not control_token:
        raise RuntimeError("Run scripts/setup-env first; RECEIVER_CONTROL_TOKEN is required")
    request(args.api, "GET", "/actuator/health")
    run_id = uuid.uuid4().hex[:12]
    email = f"demo-{run_id}@example.test"
    password = "Dg!" + secrets.token_urlsafe(24)
    auth = request(args.api, "POST", "/api/v1/auth/register", {
        "tenantName": "DispatchGrid Demo " + run_id, "email": email, "password": password})
    token = auth["accessToken"]
    api_key = request(args.api, "POST", "/api/v1/api-keys", {"name": "Demo publisher"}, token)["key"]
    endpoint = request(args.api, "POST", "/api/v1/endpoints", {
        "url": args.destination, "description": "Signed local demo receiver", "subscribedEvents": ["order.created", "load.created"],
        "active": True, "maxAttempts": 3, "retryBaseSeconds": 1, "retryMaxSeconds": 2}, token)
    request(args.receiver, "POST", "/control", {"signingSecret": endpoint["signingSecret"], "failureCount": 0, "delayMs": 0}, control_token)
    local = ROOT / ".local"
    local.mkdir(exist_ok=True)
    credentials_file = local / "demo-secrets.json"
    credentials_file.write_text(json.dumps({"email": email, "password": password, "apiKey": api_key,
        "accessToken": token, "tenantId": auth["tenant"]["id"], "endpointId": endpoint["id"]}, indent=2), encoding="utf-8")
    if os.name != "nt":
        credentials_file.chmod(0o600)
    print("Registered tenant, generated API key, and configured signed receiver.")
    print("Dashboard login and publisher credentials saved privately in .local/demo-secrets.json")
    if args.prepare_load:
        print("Load-test fixture ready. It uses the tenant's configured ingestion quota.")
        return
    results = {"runId": run_id, "tenantId": auth["tenant"]["id"], "endpointId": endpoint["id"], "scenarios": []}

    def publish(label):
        data = {"eventType": "order.created", "payload": {"orderId": "ORD-" + run_id, "scenario": label, "amount": 1499, "currency": "INR"}}
        key = run_id + "-" + label
        event = request(args.api, "POST", "/api/v1/events", data, api_key, key)
        return event, data, key

    def delivery(event_id, status):
        page = request(args.api, "GET", "/api/v1/deliveries?eventId=" + event_id, token=token)
        return next((item for item in page["items"] if item["status"] == status), None)

    event, data, key = publish("success")
    duplicate = request(args.api, "POST", "/api/v1/events", data, api_key, key)
    assert duplicate["id"] == event["id"], "Duplicate ingestion created a second logical event"
    successful = wait_for("successful signed delivery", lambda: delivery(event["id"], "DELIVERED"))
    received = request(args.receiver, "GET", "/received", token=control_token)
    assert any(item["deliveryId"] == successful["id"] for item in received["items"]), "Receiver did not persist the delivery"
    results["scenarios"].append({"name": "success-and-idempotency", "eventId": event["id"], "deliveryId": successful["id"], "status": successful["status"]})
    print("PASS: durable ingestion, duplicate idempotency key, signed delivery and receiver deduplication record.")

    request(args.receiver, "POST", "/control", {"failureCount": 1, "status": 500}, control_token)
    event, _, _ = publish("retry")
    retried = wait_for("successful retry after HTTP 500", lambda: delivery(event["id"], "DELIVERED"))
    attempts = request(args.api, "GET", "/api/v1/deliveries/" + retried["id"] + "/attempts", token=token)
    rows = attempts.get("items", []) if isinstance(attempts, dict) else attempts
    assert len(rows) >= 2, "Expected a failed attempt followed by success"
    results["scenarios"].append({"name": "retry", "deliveryId": retried["id"], "attempts": len(rows), "status": retried["status"]})
    print("PASS: HTTP 500 scheduled a durable retry and subsequent delivery succeeded.")

    request(args.receiver, "POST", "/control", {"failureCount": -1, "status": 500}, control_token)
    event, _, _ = publish("exhaustion")
    exhausted = wait_for("retry exhaustion", lambda: delivery(event["id"], "EXHAUSTED"))
    assert exhausted["attemptCount"] == 3, "Retry limit was not respected"
    request(args.receiver, "POST", "/control", {"failureCount": 0}, control_token)
    request(args.api, "POST", "/api/v1/deliveries/" + exhausted["id"] + "/replay", {}, token)
    replayed = wait_for("successful manual replay", lambda: delivery(event["id"], "DELIVERED"))
    results["scenarios"].append({"name": "exhaustion-and-replay", "deliveryId": replayed["id"], "exhaustedAttempts": 3, "status": replayed["status"]})
    report = local / "demo-result.json"
    report.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("PASS: retry exhaustion, terminal record, and manual replay to success.")
    print("Evidence saved to .local/demo-result.json; open http://localhost:3000 and http://localhost:3001.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, TimeoutError, AssertionError, urllib.error.URLError) as exc:
        print("Demo failed: " + str(exc), file=sys.stderr)
        sys.exit(1)
