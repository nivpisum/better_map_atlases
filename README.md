# Better Map Atlases

<img src="src/main/resources/logo.png" alt="Better Map Atlases logo" width="160">

**Keep a record of the places you explore—above ground and below.**

Better Map Atlases is a Forge add-on for [Map Atlases](https://modrinth.com/mod/map-atlases-forge). It adds separate cave pages to your atlas, improves map colours using block textures and biome information, and makes the full-screen atlas and HUD easier to read and customise.

Underground maps grow as you explore: nearby floors you can actually see become part of the book's history. Mines, narrow tunnels, broad chambers, slopes, and the Nether use the same cave view. It does not reveal unexplored terrain or provide X-ray vision. Your records stay with the map pages and atlas, so carrying the book matters.

## What it adds

- **Surface and cave pages:** browse each dimension's surface or select **Cave** to see its explored underground history.
- **Height-aware cave maps:** follow the floor under your feet, including half-block heights, or use **Page Up / Page Down** to browse recorded heights one block at a time. Nearby slopes and steps provide route context; stacked floors remain distinct where a recorded solid ceiling separates them. Distant heights fade, and water and lava show their visible surfaces. These are navigation clues from observations, not guaranteed paths.
- **One cave page across heights:** a region's cave history uses one blank map across all Y levels; its surface uses a separate map. Revisiting the region updates those pages instead of spending a new map at each height.
- **Consistent large map and HUD:** both display the same recorded terrain. Zooming and panning change your view; they do not explore new ground.
- **Readable, adjustable controls:** change HUD size, zoom, orientation, position, map-marker size, player-pointer size, coordinate/biome text size, and internal map-page borders. Unknown areas remain blank paper.
- **Book-based markers and editing:** place a pin using a banner's colour and name; remove markers or cut pages with shears. Add blank maps to an existing atlas at a cartography table.
- **Dimension navigation:** Overworld/Nether browsing uses their 8:1 coordinate relationship. Other dimensions use explicitly configured links, or open at the origin when unlinked. Returning to your current dimension resumes following your position.
- **English and Simplified Chinese controls.**

## Install 1.0.1

Install Better Map Atlases on **both client and server** with **Forge and Map
Atlases**. The add-on does not impose version bounds or a separate Moonlight
requirement. Follow your chosen Map Atlases release's own library requirements.

**Verified combination:** Minecraft **1.20.1**, Forge **47.4.23**, Java **17**, Map Atlases **1.20-6.0.20**, Moonlight Lib **1.20-2.16.35-forge**.
Other combinations are untested: they may work or fail, and compatibility is
yours to explore and resolve. These versions are an example known to work,
not installation limits.

Place the release JAR in the instance's `mods` folder. On upgrade, replace the
old Better Map Atlases JAR and update clients and server together. Back up a
world before changing its mod set.

[Download 1.0.1](https://github.com/nivpisum/better_map_atlases/releases/tag/v1.0.1) · [中文项目页](https://mc.nivpisum.com/project/better_map_atlases/)

Two known compatibility settings:

- With **ImmediatelyFast**, set `map_atlas_generation=false` in its configuration.
- With **C2ME**, turn off **Map Atlases' multithreading**.

These settings address known interactions; compatibility with other mod combinations depends on the pack.

## First trip

1. Carry an atlas stocked with blank maps, then explore.
2. Open the atlas and select a dimension tab for its surface, or the deepslate **Cave** bookmark for its cave records.
3. On Cave, press **Page Up / Page Down** to inspect another height. Click **Cave** again to follow the floor under your feet. The bottom coordinate row shows the cursor's X/Z and the height you are viewing.
4. Use the settings button below the pin and shears to adjust the display. The full-screen atlas and HUD have separate viewing controls.
5. Carry banners for pins and shears for editing. A successful pin uses one banner; cutting a page or removing a marker uses one point of shears durability. Creative mode preserves these materials.

## 中文速览

Better Map Atlases 为地图册添加独立洞穴页，并改善地图颜色、大小地图显示与操作。洞穴记录来自你实际探索时看到的附近地面，不会揭示未探索地下；记录随地图页和书保存。

- 客户端与服务端均安装，仅强制 **Forge 和 Map Atlases 存在**，不额外限制版本或强制 Moonlight；Map Atlases 所需库按选用版本配齐。
- **已验证组合**：Minecraft **1.20.1**、Forge **47.4.23**、Java **17**、Map Atlases **1.20-6.0.20**、Moonlight Lib **1.20-2.16.35-forge**。其他环境未经测试，可能正常或异常，请自行探索和处理。
- 携带地图册和空白地图探索；地表与洞穴分别用图，同一区域的各个洞穴高度共用一张地图。
- 点维度标签看地表，点深板岩“洞穴”标签看地下。**PgUp / PgDn** 每次调整一格，再点“洞穴”恢复跟随脚下高度。
- 设置按钮位于图钉、剪刀下方；可调整大小、缩放、朝向、位置、标记、指针、文字与地图页边框。
- 旗帜用于放置带颜色和名称的标记，剪刀用于删标记或剪页；为已有地图册补图请用制图台。
- ImmediatelyFast 需设置 `map_atlas_generation=false`；使用 C2ME 时关闭 Map Atlases 多线程。

## Source, licence, and credits

Build with JDK 17 using `./gradlew build` (`gradlew.bat build` on Windows). Required mods are obtained separately and are not bundled.

Original code and documentation use [CC0-1.0](LICENSE): you may use, modify,
redistribute and use them commercially without attribution. This does not
change third-party asset rights. The Map Atlases-derived logo remains
GPL-3.0-only, and the original Minecraft gear textures retain Mojang/Microsoft
rights. See [NOTICE.md](NOTICE.md) and [logo sources](branding/README.md).

Project design and direction are by **nivpisum**. AI assistance contributed substantially to the code and documentation. Better Map Atlases is an independent add-on, not an official release of Map Atlases, Accurate Maps, or Color Accurate Maps, and is not approved by or associated with Mojang or Microsoft.
