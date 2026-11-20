# Strawberry Bind API

所有游戏接口都需要：

```http
X-Game-Server-Key: <server_key>
Content-Type: application/json
```

## 会话

```http
POST /api/game/session
```

```json
{
  "player": "PlayerName",
  "uuid": "00000000-0000-0000-0000-000000000000"
}
```

已登录：

```json
{
  "bound": true,
  "need_key": false,
  "is_admin": false,
  "player": "PlayerName",
  "uuid": "00000000-0000-0000-0000-000000000000",
  "user_no": 1
}
```

未登录或登录已过期：

```json
{
  "bound": false,
  "need_key": true,
  "login_required": true,
  "player": "PlayerName",
  "uuid": "00000000-0000-0000-0000-000000000000"
}
```

`is_admin` 决定是否给游戏 OP。

## 登录/绑定

```http
POST /api/game/bind
```

```json
{
  "player": "PlayerName",
  "uuid": "00000000-0000-0000-0000-000000000000",
  "key": "sk-..."
}
```

成功：

```json
{
  "bound": true,
  "is_admin": false,
  "player": "PlayerName",
  "uuid": "00000000-0000-0000-0000-000000000000",
  "user_no": 1
}
```

## 待发货订单

```http
GET /api/game/deliveries?player=PlayerName&uuid=...
```

```json
{
  "bound": true,
  "player": "PlayerName",
  "uuid": "...",
  "orders": [
    {
      "order_no": "ord-...",
      "product_name": "VIP",
      "grant_command": "give {player} minecraft:diamond 1"
    }
  ]
}
```

## 确认发货

```http
POST /api/game/deliveries/{order_no}/confirm
```

```json
{
  "player": "PlayerName",
  "uuid": "00000000-0000-0000-0000-000000000000"
}
```

## 可选：网站接入

如果你们有自己的网站，可以让网站直接操作同一套用户/密钥数据，或额外提供：

```text
POST   /api/user/game-key
DELETE /api/user/game-key
```

这些接口不属于独立后端的最小实现。独立后端通过 `manage.py` 生成密钥。

## 管理员密钥

```bash
python manage.py create-key --username admin --admin
```

使用该密钥登录后，`is_admin` 为 `true`，模组会给予 OP。
