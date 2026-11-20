"""Standalone Strawberry Bind API server.

This is intentionally small: SQLite + FastAPI, no external database required.
Run it behind HTTPS and set BIND_SERVER_KEY to a long random value.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import string
import uuid as uuidlib
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

DB_PATH = Path(os.environ.get("BIND_DB_PATH", "bind.db")).expanduser()
SERVER_KEY = os.environ.get("BIND_SERVER_KEY", "").strip()
SESSION_DAYS = 30
KEY_ALPHABET = string.ascii_letters + string.digits
PLAYER_RE = re.compile(r"^[A-Za-z0-9_]{1,16}$")

app = FastAPI(title="Strawberry Bind API", version="1.0.0")


@app.on_event("startup")
def _startup():
    init_db()


def now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def key_hash(key: str) -> str:
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def new_key() -> str:
    while True:
        suffix = "".join(secrets.choice(KEY_ALPHABET) for _ in range(29))
        if any(ch.isdigit() for ch in suffix) and any(ch.isalpha() for ch in suffix):
            return "sk-" + suffix


@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                is_admin INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL UNIQUE,
                license_hash TEXT UNIQUE,
                uuid TEXT UNIQUE,
                player_name TEXT,
                admin_issued INTEGER NOT NULL DEFAULT 0,
                key_created_at TEXT,
                bound_at TEXT,
                last_login_at TEXT,
                last_seen_at TEXT
            );
            CREATE TABLE IF NOT EXISTS orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_no TEXT NOT NULL UNIQUE,
                user_id INTEGER NOT NULL,
                product_name TEXT NOT NULL,
                grant_command TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'PAID',
                delivered_at TEXT,
                created_at TEXT NOT NULL
            );
            """
        )


def require_server_key(x_game_server_key: str = Header(default="")):
    if not SERVER_KEY or not hmac.compare_digest(x_game_server_key, SERVER_KEY):
        raise HTTPException(401, "invalid server key")


def account_for_user(conn: sqlite3.Connection, user_id: int):
    return conn.execute("SELECT * FROM accounts WHERE user_id=?", (user_id,)).fetchone()


def ensure_account(conn: sqlite3.Connection, user_id: int):
    account = account_for_user(conn, user_id)
    if not account:
        conn.execute("INSERT INTO accounts(user_id) VALUES (?)", (user_id,))
        account = account_for_user(conn, user_id)
    return account


def expires_at(account: sqlite3.Row):
    base = account["last_login_at"] or account["bound_at"]
    return (datetime.fromisoformat(base) + timedelta(days=SESSION_DAYS)) if base else None


def account_payload(conn, account):
    linked = bool(account["uuid"] and account["bound_at"])
    expires = expires_at(account)
    active = bool(linked and expires and expires > now())
    user = conn.execute("SELECT * FROM users WHERE id=?", (account["user_id"],)).fetchone()
    return {
        "user_no": account["user_id"],
        "username": user["username"] if user else "",
        "linked": linked,
        "bound": active,
        "login_required": bool(linked and not active),
        "has_key": bool(account["license_hash"]),
        "admin_issued": bool(account["admin_issued"]),
        "uuid": account["uuid"],
        "player_name": account["player_name"],
        "session_expires_at": iso(expires),
    }


class SessionRequest(BaseModel):
    player: str = Field(default="", max_length=16)
    uuid: str = Field(default="", max_length=36)


class BindRequest(SessionRequest):
    key: str = Field(default="", max_length=64)


def validate_player(player: str) -> str:
    player = (player or "").strip()
    if not PLAYER_RE.fullmatch(player):
        raise HTTPException(400, "invalid player name")
    return player


def validate_uuid(value: str) -> str:
    try:
        return str(uuidlib.UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(400, "invalid player uuid")


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/api/game/session", dependencies=[Depends(require_server_key)])
def session(payload: SessionRequest):
    player = validate_player(payload.player)
    uuid = validate_uuid(payload.uuid)
    with db() as conn:
        account = conn.execute("SELECT * FROM accounts WHERE uuid=?", (uuid,)).fetchone()
        if not account:
            return {"bound": False, "need_key": True, "player": player, "uuid": uuid}
        data = account_payload(conn, account)
        if data["bound"]:
            conn.execute("UPDATE accounts SET last_seen_at=?, player_name=? WHERE id=?",
                         (now().isoformat(), player, account["id"]))
        return {"bound": data["bound"], "need_key": not data["bound"],
                "login_required": data["login_required"], "is_admin":
                bool(account["admin_issued"] or (conn.execute(
                    "SELECT is_admin FROM users WHERE id=?", (account["user_id"],)
                ).fetchone()["is_admin"])),
                "player": player, "uuid": uuid, "user_no": account["user_id"]}


@app.post("/api/game/bind", dependencies=[Depends(require_server_key)])
def bind(payload: BindRequest):
    player = validate_player(payload.player)
    uuid = validate_uuid(payload.uuid)
    key = (payload.key or "").strip()
    if not key.startswith("sk-") or len(key) != 32:
        raise HTTPException(400, "invalid key")
    with db() as conn:
        account = conn.execute("SELECT * FROM accounts WHERE license_hash=?", (key_hash(key),)).fetchone()
        if not account:
            raise HTTPException(400, "invalid or expired key")
        if account["bound_at"]:
            if account["uuid"] != uuid:
                raise HTTPException(400, "key belongs to another player")
        else:
            other = conn.execute("SELECT * FROM accounts WHERE uuid=?", (uuid,)).fetchone()
            if other and other["user_id"] != account["user_id"]:
                raise HTTPException(400, "player already bound")
            conn.execute("UPDATE accounts SET uuid=?, player_name=?, bound_at=? WHERE id=?",
                         (uuid, player, now().isoformat(), account["id"]))
        conn.execute("UPDATE accounts SET player_name=?, last_login_at=?, last_seen_at=? WHERE id=?",
                     (player, now().isoformat(), now().isoformat(), account["id"]))
        user = conn.execute("SELECT * FROM users WHERE id=?", (account["user_id"],)).fetchone()
        return {"bound": True, "is_admin": bool(account["admin_issued"] or user["is_admin"]),
                "player": player, "uuid": uuid, "user_no": account["user_id"]}


@app.get("/api/game/deliveries", dependencies=[Depends(require_server_key)])
def deliveries(player: str, uuid: str = ""):
    player = validate_player(player)
    uuid = validate_uuid(uuid)
    with db() as conn:
        account = conn.execute("SELECT * FROM accounts WHERE uuid=?", (uuid,)).fetchone()
        if not account:
            return {"bound": False, "orders": []}
        data = account_payload(conn, account)
        if not data["bound"]:
            return {"bound": False, "orders": []}
        rows = conn.execute(
            "SELECT order_no, product_name, grant_command FROM orders "
            "WHERE user_id=? AND status='PAID' AND delivered_at IS NULL ORDER BY id",
            (account["user_id"],),
        ).fetchall()
        return {"bound": True, "player": player, "uuid": uuid,
                "orders": [dict(row) for row in rows]}


@app.post("/api/game/deliveries/{order_no}/confirm", dependencies=[Depends(require_server_key)])
def confirm(order_no: str, payload: SessionRequest):
    player = validate_player(payload.player)
    uuid = validate_uuid(payload.uuid)
    with db() as conn:
        account = conn.execute("SELECT * FROM accounts WHERE uuid=?", (uuid,)).fetchone()
        if not account:
            raise HTTPException(403, "player not bound")
        result = conn.execute(
            "UPDATE orders SET delivered_at=? WHERE order_no=? AND user_id=? "
            "AND status='PAID' AND delivered_at IS NULL",
            (now().isoformat(), order_no, account["user_id"]),
        )
        if result.rowcount != 1:
            raise HTTPException(404, "order not found or already delivered")
        return {"ok": True}


if __name__ == "__main__":
    import uvicorn
    init_db()
    uvicorn.run(app, host="127.0.0.1", port=8090)
