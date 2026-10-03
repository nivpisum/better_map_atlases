"""Composite one continuous fine map while preserving the original book pixels.

Requires Pillow. surface_map.png and cave_map.png contain AI-assisted artwork.
The original coarse map controls geography, not independent drawing units.
"""
from pathlib import Path
from collections import deque
import hashlib
import json
import random
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = Path(__file__).resolve().parent
BASE = ROOT / 'map_atlases_original.png'
OUT = ROOT.parent / 'src/main/resources/logo.png'
WATER, GRASS, FOREST, ROCK = (41, 65, 222), (82, 139, 11), (0, 112, 0), (135, 166, 151)
MAP_COLORS = (WATER, GRASS, FOREST, ROCK)
BOX = (289, 250, 689, 500)


def build():
    base = Image.open(BASE).convert('RGB')
    px = base.load()
    map_mask = Image.new('L', base.size)
    original_water = Image.new('L', base.size)
    for y in range(250, 500):
        for x in range(289, 689):
            if px[x, y] in MAP_COLORS:
                map_mask.putpixel((x, y), 255)
                if px[x, y] == WATER:
                    original_water.putpixel((x, y), 255)
    details = base.copy()
    source = Image.open(ROOT / 'surface_map.png').convert('RGB')
    assert source.size == (400, 250)
    details.paste(source, BOX[:2])
    terrain = details.load()
    art_water = Image.new('L', base.size)
    for y in range(250, 500):
        for x in range(289, 689):
            r, g, b = terrain[x,y]
            if b > 1.3*g and b > 1.5*r:
                art_water.putpixel((x,y), 255)
    water = ImageChops.multiply(art_water, map_mask)
    expanded = art_water.filter(ImageFilter.MaxFilter(41))
    for y in range(250,500,50):
        for x in range(289,689,50):
            box = (x,y,x+50,y+50)
            if px[x+25,y+25] == WATER and water.crop(box).tobytes().count(255) < 1375:
                water.paste(expanded.crop(box), box[:2])
    water = water.filter(ImageFilter.GaussianBlur(3)).point(lambda p: 255 if p >= 128 else 0)
    water = ImageChops.multiply(water, map_mask)
    land = ImageChops.subtract(map_mask, water)
    river = details.copy()
    rp = river.load()
    blue_seeds, blue_seen = deque(), set()
    for y in range(250,500):
        for x in range(289,689):
            if art_water.getpixel((x,y)) and water.getpixel((x,y)):
                blue_seeds.append((x,y)); blue_seen.add((x,y))
    while blue_seeds:
        x,y = blue_seeds.popleft()
        for nx,ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            if 289 <= nx < 689 and 250 <= ny < 500 and (nx,ny) not in blue_seen and water.getpixel((nx,ny)):
                rp[nx,ny] = rp[x,y]
                blue_seen.add((nx,ny)); blue_seeds.append((nx,ny))
    # Fill the two expanded river ends with water grain, avoiding stretched
    # shoreline highlights from nearest-neighbour texture extension.
    extension = ImageChops.subtract(water, art_water)
    patch = Image.new('RGB', base.size)
    pd = ImageDraw.Draw(patch)
    rng = random.Random(17012001)
    for y in range(250,500,3):
        for x in range(289,689,3):
            value = rng.randrange(-4,5)
            pd.rectangle((x,y,x+2,y+2), fill=(4,108+value,230+value))
    river.paste(patch, mask=ImageChops.multiply(extension.filter(ImageFilter.GaussianBlur(3)), water))
    # The art is one landscape. Extend adjacent land texture over source water
    # that lies outside the original river's coarse location.
    seeds, seen = deque(), set()
    for y in range(250, 500):
        for x in range(289, 689):
            r, g, b = terrain[x, y]
            if land.getpixel((x, y)) and g >= r and b <= 1.2*g:
                seeds.append((x, y))
                seen.add((x, y))
    while seeds:
        x, y = seeds.popleft()
        for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            if 289 <= nx < 689 and 250 <= ny < 500 and (nx,ny) not in seen and land.getpixel((nx,ny)):
                terrain[nx,ny] = terrain[x,y]
                seen.add((nx,ny))
                seeds.append((nx,ny))

    details.paste(river, mask=water)
    coast = ImageChops.multiply(water.filter(ImageFilter.MaxFilter(7)), land)
    details.paste((218, 201, 137), mask=coast)
    result = base.copy()
    result.paste(details, mask=map_mask)

    book_palette = {(56, 26, 15), (212, 189, 116), (22, 16, 5), (87, 49, 16),
        (73, 35, 15), (238, 225, 179), (126, 83, 17), (108, 66, 19),
        (250, 248, 235)} | set(MAP_COLORS)
    book_mask = Image.new('L', base.size)
    for y in range(100, 800):
        for x in range(89, 839):
            if px[x, y] in book_palette:
                book_mask.putpixel((x, y), 255)
    cave = Image.open(ROOT / 'cave_map.png').convert('RGBA')
    assert cave.size == base.size
    positioned = Image.new('RGBA', base.size)
    positioned.paste(cave, (10, 45))
    cave = positioned
    cave_alpha = ImageChops.subtract(cave.getchannel('A'), book_mask)
    result.paste(cave.convert('RGB'), mask=cave_alpha)

    channels = ImageChops.difference(base, result).split()
    changed = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2]).point(lambda p: 255 if p else 0)
    allowed = ImageChops.lighter(map_mask, cave_alpha.point(lambda p: 255 if p else 0))
    protected = ImageChops.subtract(book_mask, map_mask)
    assert ImageChops.subtract(changed, allowed).getbbox() is None
    assert ImageChops.multiply(changed, protected).getbbox() is None

    # Actually reduce to the original 50x50-cell grid and compare geography.
    reduced_water = water.crop(BOX).resize((8,5), Image.Resampling.BOX)
    reduced_base_water = original_water.crop(BOX).resize((8,5), Image.Resampling.BOX)
    for actual, expected in zip(reduced_water.tobytes(), reduced_base_water.tobytes()):
        assert (actual >= 128) == (expected >= 128)
    comparison = Image.new('RGB', (1800,900))
    comparison.paste(base, (0,0))
    coarse = result.copy()
    mini = result.crop(BOX).resize((8,5), Image.Resampling.BOX).resize((400,250), Image.Resampling.NEAREST)
    coarse.paste(mini, BOX[:2])
    comparison.paste(coarse, (900,0))
    comparison.save(ROOT / 'coarse_comparison.png')
    OUT.parent.mkdir(parents=True, exist_ok=True)
    result.save(OUT)
    verification = {
        'size': list(base.size), 'continuous_surface_map': True,
        'book_pixels_preserved': True, 'coarse_water_land_layout_matches': True,
        'protected_book_pixels': protected.tobytes().count(255),
        'map_detail_pixels': map_mask.tobytes().count(255),
        'cave_overlay_pixels': sum(v > 0 for v in cave_alpha.tobytes()),
        'original_sha256': hashlib.sha256(BASE.read_bytes()).hexdigest(),
        'logo_sha256': hashlib.sha256(OUT.read_bytes()).hexdigest()
    }
    (ROOT / 'verification.json').write_text(json.dumps(verification, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(verification))


if __name__ == '__main__':
    build()
