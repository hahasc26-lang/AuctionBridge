# Changelog / 更新日志

所有版本的重要变更均记录于此。  
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)。

## [1.0.4] - 2026-10-10

### 修复 / Fixed

- **事件广播防抖去重与虚假误报消除 (Event Deduplication & False-Positive Prevention)**：
  - 在事件总线中引入容量为 1000 的 5 秒 LRU 防抖去重队列，相同物品的同类状态事件自动去重抑制，彻底消除了命令审计降级与后台增量扫描器之间的**双重重复广播**。
  - 在命令审计监视器中增加执行时间戳与物品创建时间校验，杜绝玩家在 `/ah sell` 命令执行失败时误将历史在售旧物品当作新上架广播。
- **GZIP 二进制 BLOB 智能检索回退 (In-Memory Fallback for GZIP Item BLOBs)**：
  - 攻克了由于底层数据库将物品存储为 GZIP 压缩二进制流导致 SQL `AND item LIKE ?` 模糊匹配完全失效的难题。
  - 当 SQL 检索返回为空时，系统自动拉取在售候选条目，并在内存中对反序列化后的展示名称、原生物品材质以及 Lore 文本进行全文字段智能匹配，完美兼顾纯文本与 GZIP 压缩数据。
- **在售追踪队列幽灵 ID 清理与下架感知 (Ghost Slot Purge & Cancellation Detection)**：
  - 引入批量追踪状态探针（`inspectTrackedItems`），主动比对识别在数据库中已被物理删除（玩家取消 `/ah cancel` 或物品过期被清理）的商品 ID。
  - 彻底解决了 300 容量追踪队列因被已删除商品占满而导致的**幽灵槽位泄漏**与后续售出漏报问题，并能在物品被删除时向前端主动推送 `ITEM_CANCELLED` 下架事件。
- **WebSocket 会话多线程并发写保护 (Thread-Safe WebSocket Session Writes)**：
  - 封装安全的同步发送逻辑，在底层 Jetty WebSocket 会话实例上加互斥锁，彻底杜绝了广播线程与定时心跳 Ping 并发向同一客户端写入时触发的 `write already in progress` 异常及误踢正常客户端的问题。
- **CORS 跨域凭据标准合规 (W3C CORS Compliance)**：
  - 解决 `rule.anyHost()` 与 `rule.allowCredentials = true` 同时启用违反 W3C CORS 规范的问题；当未指定域名或包含通配符 `*` 时自动禁用凭据共享，避免现代浏览器在跨域请求时直接拦截报错。
- **配置回退默认值与模板统一 (Aligned Default Configurations)**：
  - 将 `BridgeConfig` 与主类中的缺省回退值（主机 `127.0.0.1`、数据库类型 `SQLITE`、数据表 `listings`、语言 `en_US` 等）与 `config.yml` 资源模板完全对齐，消除缺项时的解析报错。
- **多语言跳转页同步热更新 (Synchronized Localization Entry Refresh)**：
  - 完善仪表盘热更新机制，在重新释放最新的 `web/index.html` 时，同步覆盖刷新 `index_zh_CN.html` 等多语言跳转页，防止旧版跳转脚本滞留失效。

### 变更 / Changed

- **前置依赖声明规范化 (Dependency Lifecycle Alignment)**：
  - 将运行时必需类库宿主 `HahaWebPlugin` 在 `plugin.yml` 中调整为显式硬依赖 `depend: [HahaWebPlugin]`，确保服务端加载器能精确识别前置依赖关系，避免因误当软依赖而产生启动异常。

## [1.0.3] - 2026-10-10

### 新增 / Added

- **父聚合工程统一管理 (Parent Aggregator Integration)**：接入 WebPlugin-Parent 根工程 POM，统一管理依赖版本与编译器插件，支持根目录下单条 Maven 命令完成一键协同打包。
- **配置动态热重载增强 (Dynamic Config Reloading)**：完善 `/ab reload` 指令，执行重载时自动停止并根据最新周期重新编排后台增量扫描器任务，无需重启服务端即可令扫描配置生效。

### 性能 / Performance

- **物品解码 LRU 缓存 (Item Deserialization LRU Cache)**：引入容量为 4096 的线程安全 LRU 缓存，相同物品 BLOB 直接从内存获取解码对象，消除重复翻页与高频广播时的 GZIP 解压及 NBT 解析开销。
- **消除反射与异常雪崩 (Eliminated Exception Penalties and Reflection Overhead)**：静态单例缓存 Paper 的 `deserializeBytes` 反射 Method，并在字节探测中增加 NBT `TAG_Compound (0x0A)` 魔数特征预检，彻底消除遍历探测中因频繁抛出异常带来的 CPU 飙升与 GC 停顿。

### 修复 / Fixed

- **售出事件漏报修复 (Sold Item Detection)**：彻底修复降级数据库扫描器由于仅依赖 `id > cursor` 导致无法检测在售商品被购买（`buyer_uuid` 变更）的严重缺陷；引入活跃在售商品集合追踪，提供上架与售出双轨增量通知。

### 变更 / Changed

- **玩家名称解析防穿透 (Player Name Negative Caching)**：升级玩家 UUID 名称解析缓存为标准 LRU 淘汰模式，并加入负缓存机制，彻底解决离线或不存在的 UUID 频繁穿透导致主/异步线程磁盘 IO 阻塞的问题。
- **分页总数缓存与买家回退 (Count Query Throttling)**：增加 3 秒短时 COUNT(*) 缓存，减轻快速分页时的全表扫描压力；增强买家名称解析回退，在数据库未记录 buyer_name 时自动补全买家显示名。

## [1.0.2] - 2026-10-06

### 新增 / Added

- **在售物品搜索快捷指令 (Active Auction Search Shortcuts)**：点击在售记录即可复制 `/ah search <物品名称>`，优先使用清除 Minecraft 颜色代码后的自定义名称；使用 Clipboard API 并在 HTTP 等不安全上下文回退到传统复制方式，同时显示自动消失的本地化提示。
- **记住仪表盘语言 (Remembered Dashboard Language)**：将所选语言保存在有效期一年的浏览器 Cookie 中，后续访问（包括访问网站根路径）时自动恢复。

## [1.0.1] - 2026-10-05

### 修复 / Fixed

- **拍卖卖家名称 (Auction Seller Names)**：通过 UUID 解析卖家名称，加入线程安全缓存和安全回退；避免在服务器主线程执行玩家查询。
- **拍卖物品信息 (Auction Item Details)**：反序列化 AuctionHouse 物品 BLOB（支持 Bukkit 对象流、Paper 字节序列化和 GZIP 压缩数据），并向仪表盘提供材质、展示名称、数量、Lore 和自定义模型数据。
- **币种显示 (Currency Display)**：将 Vault 币种名称以可读后缀显示，避免与金额直接拼接。
- **Vault 币种符号 (Vault Currency Symbols)**：在返回仪表盘前，将 `Dollar` 等常见 Vault 币种名称转换为显示符号。
- **物品序列化兼容 (Item Serialization Compatibility)**：支持 AuctionHouse `item:` 根节点嵌套 YAML、Base64、JSON/YAML Bukkit 物品映射、Bukkit 对象流、Paper 字节序列化、GZIP 及带版本前缀的数据；对仍无法识别的 BLOB 限量记录诊断日志。
- **仪表盘刷新 (Dashboard Refresh)**：检测并刷新已安装的旧版仪表盘副本，确保币种与物品显示修复实际生效。
- **仪表盘分页 (Dashboard Pagination)**：修复国际化分页占位符，正确显示记录范围和页码。
- **仪表盘资源过滤 (Dashboard Resource Filtering)**：避免 Maven 资源过滤破坏仪表盘 JavaScript 模板字符串，并在启动时刷新此前被过滤或已过期的仪表盘副本。
- **物品像素图标 (Pixel Item Icons)**：从 Minecraft 1.20.4 资源 CDN 加载物品/方块贴图，图片失败时尝试备用贴图路径和 GitHub 镜像，全部失败后显示文字缩写。

## [1.0.0] - 2026-10-05

### 新增 / Added

- **拍卖 Web 网关 (Auction Web Gateway)**：新增只读 REST 接口，支持拍卖列表、单条拍卖查询、服务健康与元信息、市场统计、玩家在售列表和 Vault 余额查询。
- **实时事件推送 (Real-Time Event Streams)**：新增 WebSocket 与 Server-Sent Events 拍卖事件广播，覆盖上架、成交、下架和过期，并支持 WebSocket 生命周期管理与心跳消息。
- **数据库与事件补偿 (Database and Event Fallbacks)**：新增 SQLite、MySQL、MariaDB 只读访问、可配置数据库轮询以及命令审计补偿检测。
- **内嵌市场仪表盘 (Embedded Market Dashboard)**：新增独立 `index.html` 仪表盘，采用深色高密度信息布局，支持拍卖大厅搜索与排序、稀有度颜色标记、Minecraft 颜色代码与 Lore 显示、卖家头像和实时动态流。
- **仪表盘数据接入 (Dashboard Data Integration)**：对接现有拍卖列表、统计 REST API、服务器元信息和拍卖 WebSocket 事件；新增 WebSocket 自动重连、REST 定时刷新、当前标签页 API Key 设置，以及文件预览或 API 不可用时的本地 Mock 数据。
- **仪表盘静态托管 (Dashboard Serving)**：将仪表盘根路由与 SPA 回退接入嵌入式 Javalin 服务。启动时把内置页面复制到插件数据目录并从磁盘托管，规避 HahaWebPlugin 提供 Javalin 时的 ClassLoader 资源可见性问题。
- **币种元信息 (Currency Metadata)**：在公开的 `/api/v1/info` 响应中增加 Vault 币种标识，供仪表盘显示。
- **项目文档 (Project Documentation)**：新增中英双语项目说明、安装配置指南、REST 与实时事件接口说明，以及中英文更新日志。

### 变更 / Changed

- 更新 README，补充安装、配置、鉴权、API、实时推送和仪表盘行为说明。
