# AuctionBridge

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.16%2B-brightgreen?style=flat-square&logo=minecraft" alt="Minecraft Version">
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Build-Maven-blue?style=flat-square&logo=apache-maven" alt="Maven">
  <img src="https://img.shields.io/badge/API-REST%20%7C%20WebSocket-purple?style=flat-square" alt="REST and WebSocket">
  <img src="https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Spigot-5965a8?style=flat-square&logo=minecraft" alt="Server Platform">
</p>

<p align="center">
  <a href="https://ko-fi.com/X5Z326V65O"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="ko-fi"></a>
</p>

<p align="center">
  <a href="#chinese">中文</a> | <a href="#english">English</a> | <a href="./CHANGELOG.md">Changelog</a> | <a href="./CHANGELOG_Chinese.md">更新日志</a>
</p>

<a name="english"></a>

> Read-only web gateway for Minecraft AuctionHouse, with a dashboard, REST API, WebSocket, and SSE.

## Features

- Embedded dashboard for auction listings, statistics, and live activity
- Read-only REST API for auctions, player listings, balances, and service health
- WebSocket and SSE event streams
- SQLite, MySQL, and MariaDB support; optional Vault economy integration
- Database polling and command-audit fallbacks

**Requirements:** Java 17+, Paper/Purpur/Spigot 1.16+, [HahaWebPlugin](https://modrinth.com/plugin/hahawebplugin), and an AuctionHouse-compatible SQLite/MySQL/MariaDB database. Vault and an economy provider are optional.

## Install

Install HahaWebPlugin (restart once if newly installed), place `AuctionBridge-x.x.x.jar` in `plugins/`, then start the server. Configure the database in `plugins/AuctionBridge/config.yml`, restart, and open `http://<server-ip>:8085/`. An API key is generated on first startup; the dashboard does not require it.

## Database schema

AuctionBridge does not create or migrate tables. Required columns: `id`, `world`, `seller_uuid`, `buyer_uuid`, `bidder_uuid`, `price`, `reserve`, `bid`, `listing_type`, `item`. Empty `buyer_uuid` means active; non-empty means sold. Search uses `item`, seller filtering uses `seller_uuid`. Cancelled and expired listings cannot be distinguished. Use a read-only database account where supported; relative SQLite paths resolve from `plugins/`.

## API and events

Base URL: `http://<server-ip>:8085/api/v1`

| Endpoint | Description |
|---|---|
| `GET /health`, `GET /info` | Public service status and server information |
| `GET /auctions` | Search and page through listings |
| `GET /auctions/{id}` | Get a listing |
| `GET /stats` | Listing and sales totals |
| `GET /players/{uuid}/balance` | Vault balance |
| `GET /players/{uuid}/listings` | Player's active listings |

`/auctions` parameters: `status` (`ACTIVE`, `SOLD`, `CANCELLED`, `EXPIRED`, `ALL`), `search`, `minPrice`, `maxPrice`, `sellerUuid`, `sort` (`price_asc`, `price_desc`, `id_desc`), `page`, `pageSize` (max 100). Cancelled/expired filters return no results with the schema above.

Regular `/api/v1/*` routes require `X-API-Key`, except `/health` and `/info`. Public dashboard routes (`/api/dashboard/v1/*`, `/ws/dashboard/auctions`) do not require a key. WebSocket: `/ws/auctions` (authenticated); SSE: `/api/v1/stream` (authenticated).

## Commands and build

`/ab status` · `/ab reload` (restart after database/host/port changes) · Permission: `auctionbridge.admin` (operators by default) · Build: `mvn clean package` → `target/AuctionBridge-x.x.x.jar`

---

<a name="chinese"></a>

## 中文

AuctionBridge 是 Minecraft AuctionHouse 的只读 Web 网关，提供市场仪表盘、REST API、WebSocket 和 SSE。需要 Java 17+、Paper/Purpur/Spigot 1.16+、[HahaWebPlugin](https://modrinth.com/plugin/hahawebplugin) 及兼容的 SQLite/MySQL/MariaDB 数据库；Vault 与经济插件为可选。

### 功能特性

- 仪表盘展示拍卖列表、统计和实时动态
- 只读 REST API：拍卖、玩家物品、余额与服务状态
- WebSocket 和 SSE 实时事件推送
- 支持 SQLite、MySQL、MariaDB 和可选的 Vault 经济集成
- 数据库轮询和命令审计补偿检测

### 安装

安装 HahaWebPlugin（首次安装后重启一次），将 `AuctionBridge-x.x.x.jar` 放入 `plugins/` 并启动。编辑 `plugins/AuctionBridge/config.yml` 配置数据库，重启后访问 `http://<服务器IP>:8085/`。首次启动会生成 API Key；仪表盘无需密钥。

### 数据库结构

插件不会创建或迁移数据表。必需字段：`id`、`world`、`seller_uuid`、`buyer_uuid`、`bidder_uuid`、`price`、`reserve`、`bid`、`listing_type`、`item`。`buyer_uuid` 为空表示在售，非空表示已成交；搜索使用 `item`，卖家筛选使用 `seller_uuid`。无法区分已取消或已过期的拍卖。建议使用只读账号；SQLite 相对路径基于 `plugins/` 目录。

### API 与实时推送

API 地址：`http://<服务器IP>:8085/api/v1`

| 接口 | 说明 |
|---|---|
| `GET /health`、`GET /info` | 公开的服务状态和服务器信息 |
| `GET /auctions` | 搜索和分页查询拍卖 |
| `GET /auctions/{id}` | 查询单条拍卖 |
| `GET /stats` | 拍卖和成交统计 |
| `GET /players/{uuid}/balance` | 查询 Vault 余额 |
| `GET /players/{uuid}/listings` | 查询玩家在售物品 |

`/auctions` 参数：`status`（`ACTIVE`、`SOLD`、`CANCELLED`、`EXPIRED`、`ALL`）、`search`、`minPrice`、`maxPrice`、`sellerUuid`、`sort`（`price_asc`、`price_desc`、`id_desc`）、`page`、`pageSize`（最大 100）。使用上述表结构时，已取消/过期筛选不会返回结果。

常规 `/api/v1/*` 需要请求头 `X-API-Key`（`/health`、`/info` 除外）。公开仪表盘接口 `/api/dashboard/v1/*`、`/ws/dashboard/auctions` 无需密钥；可访问服务器的人都能查看市场数据。WebSocket：`/ws/auctions`（需鉴权）；SSE：`/api/v1/stream`（需鉴权）。

### 命令与构建

`/ab status` · `/ab reload`（修改数据库/地址/端口后需重启）· 权限：`auctionbridge.admin`（默认仅 OP）· 构建：`mvn clean package` → `target/AuctionBridge-x.x.x.jar`
