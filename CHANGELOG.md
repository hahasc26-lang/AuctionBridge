# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] - 2026-10-10

### Added

- **Parent Aggregator Integration**: Integrated with the WebPlugin-Parent root aggregator POM, supporting unified dependency management and one-command Maven multi-module packaging.
- **Dynamic Config Reloading**: Enhanced the `/ab reload` command to reschedule and restart the background delta scanner task on the fly, allowing scan intervals and fallback settings to take effect without a server restart.

### Performance

- **Item Deserialization LRU Cache**: Introduced a 4096-entry thread-safe LRU cache for deserialized `ItemDetails`, eliminating repetitive GZIP decompression and NBT parsing for duplicate auction BLOBs.
- **Eliminated Exception Penalties and Reflection Overhead**: Statically cached Paper's `deserializeBytes` reflection method and added NBT `TAG_Compound (0x0A)` magic byte filtering to prevent throwing repetitive stack-trace exceptions during offset detection.

### Fixed

- **Sold Item Detection**: Resolved a critical issue where the database delta scanner missed auction purchase events because it only checked `id > cursor`. Introduced an active listing tracking set to provide reliable dual-track detection for both newly listed items and subsequent purchases.

### Changed

- **Player Name Negative Caching**: Upgraded the player name resolver with bounded LRU eviction and negative caching, preventing disk-blocking lookups and cache-miss penetrations for offline UUIDs.
- **Count Query Throttling**: Added a short-lived 3-second cache for `SELECT COUNT(*)` pagination queries to mitigate full-table scan overhead during rapid pagination, and improved buyer name fallback resolution.

## [1.0.2] - 2026-10-06

### Added

- **Active Auction Search Shortcuts**: Let players click active listings to copy `/ah search <item name>`, preferring a custom display name with Minecraft formatting codes removed, using the Clipboard API with a legacy HTTP-compatible fallback and a localized auto-dismissing toast.
- **Remembered Dashboard Language**: Save the selected language in a one-year browser cookie and restore it on later visits, including visits to the site root.

## [1.0.1] - 2026-10-05

### Fixed

- **Auction Seller Names**: Resolve seller names from UUIDs with a thread-safe cache and safe fallback names, without performing player lookups on the server thread.
- **Auction Item Details**: Deserialize AuctionHouse item BLOBs in Bukkit object-stream and Paper byte-serialized formats, including GZIP-compressed data, and expose material, display name, amount, lore, and custom model data to the dashboard.
- **Currency Display**: Render Vault currency names as readable suffixes instead of concatenating them with the amount.
- **Vault Currency Symbols**: Convert common Vault currency names such as `Dollar` to their display symbols before sending them to the dashboard.
- **Item Serialization Compatibility**: Add support for AuctionHouse's nested YAML (`item:` root), Base64, JSON/YAML Bukkit item maps, Bukkit object streams, Paper byte-serialized data, GZIP, and version-prefixed item data; log a bounded number of diagnostics when a BLOB format remains unsupported.
- **Dashboard Refresh**: Refresh installed dashboard copies when their revision is outdated so currency and item display fixes are actually deployed.
- **Dashboard Pagination**: Fix translated pagination placeholders so record ranges and page numbers render with their actual values.
- **Dashboard Resource Filtering**: Exclude dashboard JavaScript from Maven resource filtering to preserve template literals, and refresh previously filtered or outdated dashboard copies at startup.
- **Pixel Item Icons**: Load item and block textures from the Minecraft 1.20.4 asset CDN, retry alternate texture paths and a GitHub mirror on errors, then show the text fallback if all image sources fail.

## [1.0.0] - 2026-10-05

### Added

- **Auction Web Gateway**: Added read-only REST endpoints for auction listings, individual auction lookup, server health and metadata, market statistics, player listings, and Vault balances.
- **Real-Time Event Streams**: Added WebSocket and Server-Sent Events broadcasts for auction listing, purchase, cancellation, and expiration events, with WebSocket connection lifecycle handling and heartbeat messages.
- **Database and Event Fallbacks**: Added SQLite, MySQL, and MariaDB read access, configurable database polling, and command-audit fallback detection.
- **Embedded Market Dashboard**: Added a standalone `index.html` dashboard with a dark, information-dense layout, marketplace search and sorting, rarity-colored item names, Minecraft color-code and lore rendering, seller avatars, and a live activity stream.
- **Dashboard Data Integration**: Connected the dashboard to the existing auction and statistics REST endpoints, server metadata, and auction WebSocket events. Added automatic WebSocket reconnection, periodic REST refresh, current-tab API key settings, and local mock data for file-based previews or unavailable APIs.
- **Dashboard Serving**: Added a root dashboard route and SPA fallback to the embedded Javalin server. The bundled page is copied to the plugin data folder and served from disk to avoid class-loader visibility issues with Javalin hosted by HahaWebPlugin.
- **Currency Metadata**: Added the Vault currency symbol to the public `/api/v1/info` response for dashboard display.
- **Project Documentation**: Added bilingual project documentation, setup instructions, REST and event endpoint references, and English and Chinese changelogs.

### Changed

- Updated the README with installation, configuration, authentication, API, event-stream, and dashboard behavior details.
