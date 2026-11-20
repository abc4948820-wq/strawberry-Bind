# Strawberry Bind

一个可自托管的 NeoForge 服务端模组：玩家必须用网站/管理员发放的密钥登录游戏，未登录会锁定移动；OP 和商城发货由后端权限决定。

项目不依赖 Strawberry 官方服务器，任何人都可以部署到自己的服务器。

- NeoForge 21.1.228 / Minecraft 1.21.1
- Java 21+
- 仅服务端安装
- 许可证：GNU AGPL v3.0 or later
- 附带一个独立 FastAPI + SQLite 后端，无需外部数据库

## 功能

- 玩家首次进入必须输入密钥
- 未登录玩家无法移动或交互
- 登录有效期 30 天，到期后重新输入密钥
- 支持离线模式服务器
- `/login <key>` 和 `/bind <key>` 所有玩家可用
- 管理员密钥登录后获得 OP
- 普通密钥不获得 OP
- 原有 `ops.json` 不作为权限来源
- 商城订单可按命令发货
- 网站/管理员解绑后，在线玩家 60 秒内重新校验
- 直接发送 `sk-` 到聊天框会被拦截

## 目录

```text
.
├── LICENSE
├── README.md
├── build.gradle
├── settings.gradle
├── gradle.properties
├── config.example.json
├── dist/
│   └── StrawberryBind-1.0.0.jar
├── docs/
│   └── API.md
├── backend/
│   ├── app.py
│   ├── manage.py
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── .env.example
└── src/
    └── main/
        ├── java/com/strawberry/bind/StrawberryBind.java
        └── resources/META-INF/neoforge.mods.toml
```

## 一、启动独立后端

### 直接运行

需要 Python 3.11+。

```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
```

设置服务端密钥（只用于模组和后端通信，不是游戏密钥）：

```bash
# Linux/macOS
export BIND_SERVER_KEY="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
export BIND_DB_PATH="./bind.db"

# Windows PowerShell
$env:BIND_SERVER_KEY = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
$env:BIND_DB_PATH = ".\bind.db"
```

初始化并创建用户：

```bash
python manage.py init
python manage.py create-user --username admin --admin
```

给用户生成登录密钥：

```bash
# 管理员密钥：进入游戏后获得 OP
python manage.py create-key --username admin --admin

# 普通用户密钥：不获得 OP
python manage.py create-key --username player1
```

启动：

```bash
uvicorn app:app --host 127.0.0.1 --port 8090
```

### Docker

```bash
cd backend
cp .env.example .env
# 修改 .env 中的 BIND_SERVER_KEY
docker compose up -d --build
docker compose exec bind-api python manage.py create-user --username admin --admin
docker compose exec bind-api python manage.py create-key --username admin --admin
```

生产环境请用 Nginx/Caddy 反代并启用 HTTPS，不要直接公开 8090。

## 二、安装模组

把以下文件放入服务端 `mods/`：

```text
dist/StrawberryBind-1.0.0.jar
```

复制配置：

```text
config.example.json → config/strawberry_bind.json
```

修改：

```json
{
  "api_base": "https://your-api.example",
  "server_key": "与后端 BIND_SERVER_KEY 相同",
  "key_url": "https://your-site.example/game-key",
  "account_name": "网站",
  "freeze": true,
  "fail_open": false,
  "release_gamemode": "SURVIVAL",
  "verify_spawn": ""
}
```

- `api_base`：后端地址
- `server_key`：后端和模组共享的服务端密钥，必须一致
- `key_url`：告诉玩家去哪里获取密钥；没有网页就留空
- `account_name`：提示中显示的名称
- `freeze`：未登录时是否锁移动
- `fail_open`：后端不可用时是否放行；建议 `false`

如果 `key_url` 为空，游戏内会提示“请联系服务器管理员获取”。

## 三、玩家使用

首次进入：

```text
/login sk-你的密钥
```

也支持：

```text
/bind sk-你的密钥
```

密钥由管理员通过 `manage.py create-key` 生成；如果你们有自己的网站，也可以把网站的密钥生成逻辑接入同样的数据库/API。

登录 30 天后需要重新验证。管理员执行 `unbind` 或网站调用解绑后，玩家下次进入需要重新登录。

## 四、管理命令

```bash
python manage.py list
python manage.py create-user --username player2
python manage.py create-key --username player2
python manage.py create-key --username player2 --admin
python manage.py unbind --username player2
```

### 商城发货

先把已支付订单加入队列：

```bash
python manage.py add-order \
  --username player1 \
  --product "VIP" \
  --command "give {player} minecraft:diamond 1"
```

玩家登录后，模组会领取订单、执行命令并确认发货。

支持变量：

```text
{player}
{uuid}
{order_no}
{product}
```

## 后端接口

所有游戏接口需要：

```http
X-Game-Server-Key: <server_key>
```

```text
POST /api/game/session
POST /api/game/bind
GET  /api/game/deliveries?player=...&uuid=...
POST /api/game/deliveries/{order_no}/confirm
```

完整示例见 [docs/API.md](docs/API.md)。

## 构建模组

需要 Gradle 8.x 和 JDK 21：

```bash
gradle build
```

输出：

```text
build/libs/strawberrybind-1.0.0.jar
```

也可以直接使用 `dist/StrawberryBind-1.0.0.jar`。

## 安全

- 密钥只保存 SHA-256 哈希
- 一个 Minecraft UUID 只能绑定一个账号
- 管理员密钥才能获得 OP
- 普通用户不能通过游戏内 `/op` 获得持久权限
- 服务端密钥不能提交到 Git
- 生产环境必须使用 HTTPS
- 后端 SQLite 文件需要定期备份

## License

Copyright (C) 2026 Strawberry Team

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
