"""Command line administration for the standalone bind API."""

from __future__ import annotations

import argparse
import uuid as uuidlib

import app


def get_user(conn, username: str):
    return conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()


def create_user(args):
    with app.db() as conn:
        if get_user(conn, args.username):
            raise SystemExit("user already exists")
        cur = conn.execute(
            "INSERT INTO users(username, is_admin, created_at) VALUES (?, ?, ?)",
            (args.username, 1 if args.admin else 0, app.now().isoformat()),
        )
        app.ensure_account(conn, cur.lastrowid)
    print(f"created user {args.username}")


def create_key(args):
    with app.db() as conn:
        user = get_user(conn, args.username)
        if not user:
            raise SystemExit("user not found")
        account = app.ensure_account(conn, user["id"])
        if account["bound_at"]:
            expires = app.expires_at(account)
            if expires and expires > app.now():
                raise SystemExit("account is still logged in; unbind it first")
        key = app.new_key()
        conn.execute(
            "UPDATE accounts SET license_hash=?, admin_issued=?, key_created_at=? WHERE user_id=?",
            (app.key_hash(key), 1 if args.admin else 0, app.now().isoformat(), user["id"]),
        )
    print(key)


def list_users(_args):
    with app.db() as conn:
        rows = conn.execute(
            "SELECT u.username, u.is_admin, a.uuid, a.player_name, a.admin_issued, "
            "a.bound_at, a.last_login_at, a.license_hash IS NOT NULL AS has_key "
            "FROM users u LEFT JOIN accounts a ON a.user_id=u.id ORDER BY u.id"
        ).fetchall()
    for row in rows:
        state = "bound" if row["uuid"] else ("key" if row["has_key"] else "empty")
        print(f'{row["username"]}\t{state}\tplayer={row["player_name"] or "-"}\t'
              f'admin={row["is_admin"] or row["admin_issued"]}\tlast={row["last_login_at"] or "-"}')


def add_order(args):
    with app.db() as conn:
        user = get_user(conn, args.username)
        if not user:
            raise SystemExit("user not found")
        order_no = args.order_no or "ord-" + uuidlib.uuid4().hex[:16]
        conn.execute(
            "INSERT INTO orders(order_no, user_id, product_name, grant_command, status, created_at) "
            "VALUES (?, ?, ?, ?, 'PAID', ?)",
            (order_no, user["id"], args.product, args.command, app.now().isoformat()),
        )
    print(order_no)


def unbind(args):
    with app.db() as conn:
        user = get_user(conn, args.username)
        if not user:
            raise SystemExit("user not found")
        cur = conn.execute(
            "UPDATE accounts SET uuid=NULL, player_name=NULL, bound_at=NULL, "
            "last_login_at=NULL, last_seen_at=NULL, license_hash=NULL, admin_issued=0 "
            "WHERE user_id=?",
            (user["id"],),
        )
        if cur.rowcount:
            print("unbound")
        else:
            print("nothing to unbind")


def main():
    parser = argparse.ArgumentParser(description="Strawberry Bind standalone admin")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("init", help="create database tables")
    p.set_defaults(func=lambda _args: app.init_db())

    p = sub.add_parser("create-user", help="create a website user")
    p.add_argument("--username", required=True)
    p.add_argument("--admin", action="store_true")
    p.set_defaults(func=create_user)

    p = sub.add_parser("create-key", help="generate a login key (shown once)")
    p.add_argument("--username", required=True)
    p.add_argument("--admin", action="store_true", help="key grants OP")
    p.set_defaults(func=create_key)

    p = sub.add_parser("list", help="list users and bindings")
    p.set_defaults(func=list_users)

    p = sub.add_parser("add-order", help="queue a paid delivery")
    p.add_argument("--username", required=True)
    p.add_argument("--product", required=True)
    p.add_argument("--command", required=True)
    p.add_argument("--order-no")
    p.set_defaults(func=add_order)

    p = sub.add_parser("unbind", help="force-unbind a user")
    p.add_argument("--username", required=True)
    p.set_defaults(func=unbind)

    args = parser.parse_args()
    app.init_db()
    args.func(args)


if __name__ == "__main__":
    main()
