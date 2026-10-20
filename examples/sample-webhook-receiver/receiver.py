"""Development receiver: signature freshness, constant-time HMAC, durable deduplication.

No dependencies. Expose behind HTTPS in a real receiver; never expose dev control publicly.
The exact original request bytes are signed, not reserialized JSON.
"""
import hashlib
import hmac
import json
import os
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.getenv("RECEIVER_DB", "receiver.sqlite3")
CONTROL_TOKEN = os.getenv("RECEIVER_CONTROL_TOKEN", "")
DEV_CONTROL = os.getenv("ENABLE_DEV_CONTROL", "false").lower() == "true"
MAX_BODY = 1024 * 1024
CONFIG_LOCK = threading.Lock()
CONFIG = {"signingSecret": os.getenv("WEBHOOK_SIGNING_SECRET", ""),
          "signingSecrets": [], "failureCount": 0, "status": 500, "delayMs": 0}


def connect():
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.execute("PRAGMA journal_mode=WAL")
    return db


def initialize():
    with connect() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS received (
          delivery_id TEXT PRIMARY KEY, event_id TEXT NOT NULL,
          received_at REAL NOT NULL, body_hash TEXT NOT NULL)""")


def verify_signature(secret, timestamp, body, signature, now=None):
    """Reject stale or future timestamps; accept current or grace-period v1 signatures."""
    try:
        if abs((time.time() if now is None else now) - int(timestamp)) > 300:
            return False
    except (ValueError, TypeError):
        return False
    if not secret:
        return False
    expected = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    candidates = [part.strip()[3:] for part in signature.split(",") if part.strip().startswith("v1=")]
    # Evaluate each comparison rather than accepting a timing-dependent early match.
    return any([hmac.compare_digest(expected, candidate) for candidate in candidates])


class Handler(BaseHTTPRequestHandler):
    server_version = "DispatchGridReceiver/1.0"

    def log_message(self, fmt, *args):
        # Never log authorization, signing secrets, body, or query strings.
        print(json.dumps({"component": "receiver", "method": self.command,
                          "path": self.path.split("?")[0], "message": fmt % args}), flush=True)

    def respond(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        try:
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when the delay fixture outlives the sender's timeout.

    def authorized_control(self):
        supplied = self.headers.get("Authorization", "")
        return DEV_CONTROL and bool(CONTROL_TOKEN) and hmac.compare_digest(supplied, "Bearer " + CONTROL_TOKEN)

    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"status": "UP"})
        if self.path == "/received" and self.authorized_control():
            with connect() as db:
                rows = db.execute("SELECT delivery_id,event_id,received_at,body_hash FROM received ORDER BY received_at DESC LIMIT 100").fetchall()
            return self.respond(200, {"items": [dict(zip(("deliveryId", "eventId", "receivedAt", "bodyHash"), row)) for row in rows]})
        self.respond(404, {"error": "Not found"})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.respond(400, {"error": "Invalid content length"})
        if length < 1 or length > MAX_BODY:
            return self.respond(413, {"error": "Body must be between 1 byte and 1 MiB"})
        self.connection.settimeout(10)
        body = self.rfile.read(length)
        if self.path == "/control":
            if not self.authorized_control():
                return self.respond(404, {"error": "Not found"})
            try:
                update = json.loads(body)
                if not isinstance(update, dict) or any(k not in CONFIG for k in update):
                    raise ValueError("Unsupported field")
                if "signingSecret" in update and (not isinstance(update["signingSecret"], str) or len(update["signingSecret"]) < 32):
                    raise ValueError("Secret must have at least 32 characters")
                if "signingSecrets" in update and (not isinstance(update["signingSecrets"], list) or len(update["signingSecrets"]) > 100 or any(not isinstance(s, str) or len(s) < 32 for s in update["signingSecrets"])):
                    raise ValueError("Invalid signing secrets")
                for key, low, high in (("failureCount", -1, 1000000), ("status", 400, 599), ("delayMs", 0, 30000)):
                    if key in update and (type(update[key]) is not int or not low <= update[key] <= high):
                        raise ValueError("Invalid " + key)
                with CONFIG_LOCK:
                    CONFIG.update(update)
                return self.respond(200, {"configured": True})
            except (ValueError, TypeError):
                return self.respond(400, {"error": "Invalid control request"})
        if self.path != "/webhook":
            return self.respond(404, {"error": "Not found"})
        with CONFIG_LOCK:
            secrets = [CONFIG["signingSecret"], *CONFIG["signingSecrets"]]
        timestamp = self.headers.get("X-DispatchGrid-Timestamp", "")
        signature = self.headers.get("X-DispatchGrid-Signature", "")
        if not any([verify_signature(secret, timestamp, body, signature) for secret in secrets]):
            return self.respond(401, {"error": "Invalid or expired signature"})
        delivery_id = self.headers.get("X-DispatchGrid-Delivery-Id", "")
        event_id = self.headers.get("X-DispatchGrid-Event-Id", "")
        if not delivery_id or not event_id or len(delivery_id) > 128 or len(event_id) > 128:
            return self.respond(400, {"error": "Missing or invalid identifier"})
        with CONFIG_LOCK:
            delay = CONFIG["delayMs"]
            fail = CONFIG["failureCount"] != 0
            status = CONFIG["status"]
            if CONFIG["failureCount"] > 0:
                CONFIG["failureCount"] -= 1
        if delay:
            time.sleep(delay / 1000)
        if fail:
            return self.respond(status, {"error": "Configured development failure"})
        body_hash = hashlib.sha256(body).hexdigest()
        with connect() as db:
            existing = db.execute("SELECT body_hash FROM received WHERE delivery_id = ?", (delivery_id,)).fetchone()
            if existing and existing[0] != body_hash:
                return self.respond(409, {"error": "Delivery identifier reused with a different body"})
            cursor = db.execute("INSERT OR IGNORE INTO received VALUES (?,?,?,?)", (delivery_id, event_id, time.time(), body_hash))
            duplicate = cursor.rowcount == 0
            # Apply the real business mutation in THIS transaction, before committing.
        return self.respond(200, {"accepted": True, "duplicate": duplicate, "deliveryId": delivery_id})


if __name__ == "__main__":
    if DEV_CONTROL and len(CONTROL_TOKEN) < 32:
        raise SystemExit("Development control requires a random RECEIVER_CONTROL_TOKEN of at least 32 characters")
    initialize()
    ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "8090"))), Handler).serve_forever()
