# Attribution and third-party software

Better Map Atlases is an independent Forge add-on by nivpisum. Its source is
dedicated to the public domain under CC0-1.0; see `LICENSE`. Original code and
documentation may be used, modified, redistributed and used commercially
without attribution to nivpisum. This dedication does not change third-party
rights in the assets listed below. The ideas behind
its texture-based map colours were inspired by [Accurate Maps by lucaargolo](https://github.com/lucaargolo/accurate-maps/tree/8fb8de5dfa7fc23e9ab5c4abd6ec7422a3942731)
and [Color Accurate Maps by Syconn](https://github.com/Syconn/Color-Accurate-Maps/tree/1b1eaea3e0536d89c6d2296bebbe28680c7a3f07).
This is not an official release by either author. Their code, binaries, and
assets are not included.

The following mods are installed separately. Their dependency binaries and
runtime code are not bundled with Better Map Atlases. The derived project logo
is documented separately below:

- [Map Atlases](https://github.com/MehVahdJukaar/mapatlases-neoforge/tree/3d1935c30310ebffb033b35783ad5a28d09fcc7a)
  by pepjebs and MehVahdJukaar, required for atlas features. The reviewed
  source snapshot carries the GNU GPL version 3 license.
- [Moonlight Lib](https://github.com/MehVahdJukaar/Moonlight/tree/1.20)
  by MehVahdJukaar, used by the verified Map Atlases build. Dependencies of other
  Map Atlases releases follow those releases' own requirements. See its
  [upstream license](https://github.com/MehVahdJukaar/Moonlight/blob/1.20/license.md).

Forge, Mixin, Minecraft, and build dependencies are obtained separately.

## Legacy Minecraft gear textures

`assets/better_map_atlases/textures/gui/legacy_gear.png` and
`assets/better_map_atlases/textures/gui/legacy_gear_middle.png` are the original,
unmodified `misc/gear.png` and `misc/gearmiddle.png` from Mojang's Minecraft
Java Edition client `inf-20100618`. The official
[version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
identifies the [version metadata](https://piston-meta.mojang.com/v1/packages/51a5c512af384d3d2a79a3efb93f7d4b9a1c6ec2/inf-20100618.json)
and [client JAR](https://launcher.mojang.com/v1/objects/89eab2c1a353707cc00f074dffba9cb7a4f5e304/client.jar).

- Client JAR SHA-1: `89eab2c1a353707cc00f074dffba9cb7a4f5e304`.
- Outer gear PNG SHA-256: `a1cf16ff7ffbac149704065231da200add4d8ec959c108c219b3b2f1a7fee46f`.
- Middle gear PNG SHA-256: `b7188fd2d8edbed7b742b15ab1a94424ffeb9625b63a043c56691566b8e2dc92`.
- Source rectangles: the complete 32×32 outer PNG and complete 16×16 middle
  PNG, with no cropping, recolouring, or pixel edits. Their alpha bounds are
  `(0, 0)` through `(32, 32)` and `(4, 4)` through `(12, 12)`, respectively
  (exclusive upper coordinates).
- Original composition evidence: the client JAR's
  `net.minecraft.client.a.c.c` constructor loads both images. Its texture
  update method samples the outer gear into a 16×16 output and replaces
  each output pixel with the corresponding middle-image pixel when that
  pixel's alpha exceeds 128. The middle thus uses the same output canvas;
  its visible center is half the outer gear's width.
- The settings button renders both complete source rectangles into the same
  12×12 destination, inset by `(1, 2)` in the existing 16×16 button. The
  middle's alpha bounds become `(3, 3)` through `(9, 9)` in this destination,
  giving a 6×6 center. This preserves the original relative composition.
  The 12×12 outer size matches the alpha-visible size of the native selected
  pin and shears sprites in Map Atlases 1.20-6.0.20's
  `assets/map_atlases/textures/gui/screen/atlas_background.png`: the pin's
  local bounds are `(1, 1)` through `(13, 13)` in its `(30, 168)` sprite,
  and the shears' are `(0, 2)` through `(12, 14)` in its `(47, 168)` sprite.
  The native pin retains its original drawing position and the shears move
  one pixel right, so all three function icons have common horizontal bounds
  `[column X + 1, column X + 13)` without changing their 16×16 interactive
  boxes. The native unselected sprites at `(30, 152)` and `(47, 152)` are tab
  strips, each with alpha bounds `(0, 0)` through `(16, 15)`; their full source
  canvases render 12 pixels wide at the same common horizontal bounds.

These textures are copyright Mojang Studios / Microsoft and are not licensed
under this add-on's CC0 dedication. They retain their original names, pixels,
source attribution and rights; their use is subject to the Minecraft EULA and
Usage Guidelines. Better Map Atlases is not an official Minecraft product and
is not approved by or associated with Mojang or Microsoft.

## Project logo

`logo.png` is derived from the Map Atlases project icon in the source snapshot
linked above. Original project: pepjebs and MehVahdJukaar. Modifications and
direction: nivpisum, with generative-AI assistance. The modified logo is licensed
under **GPL-3.0-only**, separately from the CC0 Java source and documentation. The full logo
licence is included in `LICENSES/GPL-3.0-only.txt`; the original icon and
modification notes are available with the source under `branding/`.
