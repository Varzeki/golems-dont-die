"""
Tiles screenshots into one mosaic, with no text on it, and puts a banner on top as one image.

Screenshots go in media/shots/. A shots.txt beside them lists them in order, one per line:

    sailing.png | 0.5,0.4 | 3
    stones.png

Both fields after the file are optional. The first is the focus: where in the shot to keep when it
is cropped to its tile, as fractions across and down (0.5,0.5 is the middle). The second is how much
of the mosaic the shot takes; without one, the first shot is the large feature and the rest vary.

    python make_mosaic.py
        media/shots + media/exploration-title.png -> media/exploration-expansion.jpg
    python make_mosaic.py --shots <dir> --banner <image or ""> --out <image>
"""
import argparse
import sys
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

WIDTH = 1600
HEIGHT = 450
GUTTER = 6
BACKDROP = (22, 17, 12)
EDGE = (92, 72, 46)

# How much of the mosaic each shot takes, in order, when shots.txt gives no weight. The first shot is
# the feature; the rest vary on purpose, so no two neighbours come out the same size and shape.
DEFAULT_WEIGHTS = [3.2, 1.0, 1.7, 0.8, 1.3, 1.9, 0.9, 1.2, 1.5]

# The shape a single tile looks best at, width over height. Screenshots are wide.
IDEAL_ASPECT = 1.55


def split(items, x, y, w, h):
    """
    Cuts a rectangle into one tile per shot, sized by weight.

    Each cut divides the shots into a leading and a trailing group, and the rectangle across or down
    in proportion to their weights; each side is then cut the same way. Every way of cutting is
    scored on the finished tiles — how near each is to a screenshot's own shape — and the best
    whole layout is kept, so no early cut can leave a later tile a sliver. The result is a set of
    rectangles of different sizes and proportions rather than a grid.
    """
    return best_layout(tuple(items), x, y, w, h, {})[1]


def best_layout(items, x, y, w, h, memo):
    key = (id(items[0]), len(items), round(w), round(h))
    if key in memo:
        cost, relative = memo[key]
        return cost, [(item, x + rx, y + ry, rw, rh) for item, rx, ry, rw, rh in relative]

    if len(items) == 1:
        result = (shape_cost(items[0], w, h), [(items[0], x, y, w, h)])
    else:
        total = sum(weight for _, weight in items)
        result = None
        for k in range(1, len(items)):
            share = sum(weight for _, weight in items[:k]) / total
            for across in (True, False):
                if across:
                    first = best_layout(items[:k], x, y, w * share, h, memo)
                    second = best_layout(items[k:], x + w * share, y, w * (1 - share), h, memo)
                else:
                    first = best_layout(items[:k], x, y, w, h * share, memo)
                    second = best_layout(items[k:], x, y + h * share, w, h * (1 - share), memo)
                cost = first[0] + second[0]
                if result is None or cost < result[0]:
                    result = (cost, first[1] + second[1])

    memo[key] = (result[0], [(item, rx - x, ry - y, rw, rh) for item, rx, ry, rw, rh in result[1]])
    return result


def shape_cost(item, w, h):
    """How far a tile is from a screenshot's own shape; slivers are refused outright."""
    import math
    if w <= 0 or h <= 0:
        return float("inf")
    aspect = w / h
    sliver = 0 if 0.8 <= aspect <= 2.4 else 10
    return abs(math.log(aspect / IDEAL_ASPECT)) + sliver


def read_manifest(shots):
    manifest = shots / "shots.txt"
    entries = []
    if manifest.exists():
        for line in manifest.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = [p.strip() for p in line.split("|")]
            focus = (0.5, 0.5)
            if len(parts) > 1 and parts[1]:
                fx, fy = parts[1].split(",")
                focus = (float(fx), float(fy))
            weight = float(parts[2]) if len(parts) > 2 and parts[2] else None
            entries.append((shots / parts[0], focus, weight))
    else:
        for path in sorted(shots.glob("*")):
            if path.suffix.lower() in (".png", ".jpg", ".jpeg"):
                entries.append((path, (0.5, 0.5), None))
    return entries


def crop_to(image, width, height, focus):
    """Scales a shot to cover the tile and crops it there, keeping the focus point in view."""
    scale = max(width / image.width, height / image.height)
    image = image.resize((round(image.width * scale), round(image.height * scale)), Image.LANCZOS)
    left = min(max(0, round(image.width * focus[0] - width / 2)), image.width - width)
    top = min(max(0, round(image.height * focus[1] - height / 2)), image.height - height)
    return image.crop((left, top, left + width, top + height))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shots", default=str(ROOT / "media" / "shots"))
    parser.add_argument("--banner", default=str(ROOT / "media" / "exploration-title.png"),
        help="image put above the mosaic; empty for the mosaic alone")
    parser.add_argument("--out", default=str(ROOT / "media" / "exploration-expansion.jpg"))
    args = parser.parse_args()

    entries = read_manifest(Path(args.shots))
    if not entries:
        sys.exit("no screenshots in " + args.shots)

    weighted = []
    for i, entry in enumerate(entries):
        weight = entry[2] if entry[2] is not None else DEFAULT_WEIGHTS[i % len(DEFAULT_WEIGHTS)]
        weighted.append((entry, weight))

    mosaic = Image.new("RGBA", (WIDTH, HEIGHT), BACKDROP + (255,))
    half = GUTTER / 2
    for ((path, focus, _), _), tx, ty, tw, th in split(weighted, half, half, WIDTH - GUTTER, HEIGHT - GUTTER):
        x0, y0 = round(tx + half), round(ty + half)
        x1, y1 = round(tx + tw - half), round(ty + th - half)
        tile = crop_to(Image.open(path).convert("RGB"), x1 - x0, y1 - y0, focus).convert("RGBA")
        mosaic.alpha_composite(tile, (x0, y0))
        ImageDraw.Draw(mosaic).rectangle((x0 - 1, y0 - 1, x1, y1), outline=EDGE + (255,))

    result = mosaic.convert("RGB")
    if args.banner:
        # The banner across the top, the mosaic straight beneath: one image. The banner's own rule
        # along its bottom edge is the seam.
        banner = Image.open(args.banner).convert("RGB")
        if banner.width != WIDTH:
            banner = banner.resize((WIDTH, round(banner.height * WIDTH / banner.width)), Image.LANCZOS)
        result = Image.new("RGB", (WIDTH, banner.height + mosaic.height), BACKDROP)
        result.paste(banner, (0, 0))
        result.paste(mosaic.convert("RGB"), (0, banner.height))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    result.save(out, quality=90, optimize=True)
    print("wrote", out, result.size)


if __name__ == "__main__":
    main()
