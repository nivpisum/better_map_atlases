# Logo

The Better Map Atlases logo is a modified version of the Map Atlases project
icon, with a more detailed surface map and a secondary cave map.

- Original project: Map Atlases, by pepjebs and MehVahdJukaar.
- Original icon: `map_atlases_original.png`, from
  [the reviewed upstream source](https://github.com/MehVahdJukaar/mapatlases-neoforge/blob/3d1935c30310ebffb033b35783ad5a28d09fcc7a/forge/src/main/resources/icon.png).
- Modified icon: `src/main/resources/logo.png`.
- Modifications and direction: nivpisum, with generative-AI assistance.
- Logo licence: **GPL-3.0-only**; full text is in `LICENSES/GPL-3.0-only.txt`.

The logo is not an official Map Atlases or Minecraft logo. The Java source
and documentation use CC0-1.0 separately, without an attribution requirement.
The compositing script is CC0; the image assets retain the logo's GPL-3.0-only
terms. Original logo pixels and the surface map's water/land
layout are the reference for the derivative.

`composite_logo.py` reproduces the final icon with Pillow. It paints only the
original cover area and places `cave_map.png` behind the unchanged book.
`surface_map.png` is one continuous fine terrain painting, rather than drawings
per coarse cell. The coarse grid's water/land layout is preserved;
`coarse_comparison.png` shows the original beside the actual downsampled map.
`verification.json` records the pixel checks and hashes. Both terrain assets
were cropped from AI-assisted artwork directed by nivpisum.
