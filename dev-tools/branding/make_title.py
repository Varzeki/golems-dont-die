"""
Draws a title banner for Golems Don't Die: the plugin's own, or an expansion's.

The lettering is RuneLite's own copy of the game's bold font, drawn at the size it was made for and
scaled up by whole pixels, so it keeps the game's hard pixel edges instead of being smoothed. Text
takes the colours of the game's interface: orange-gold with a one-pixel black shadow for the title,
and the yellow of a quest name for the tagline. A screenshot sits behind it, darkened toward the
lettering, with the golem standing at the right.

The plugin's banner, over the island:

    python make_title.py --title "Golems Don't Die" \
        --tagline "Golems should live forever." \
        --background golem-island.jpg --focus 0.5,0.45 --figure media/golem-render.png \
        --out media/golems-dont-die-title.png

media/golem-render.png is the golem standing, exported from the running client by BrandingExport, with
the red eyes the model does not have:

    python dev-tools/branding/render_export.py ~/.runelite/golem-exports/golem-idle.json media/golem-render.png \
        --yaw 16 --pitch 4 --height 720 --margin 30 --glow "-6,-181,-14;6,-181,-14" --glow-size 2.6

An expansion's, over sea rocks, with the plugin's name above, a purple title and a raft:

    python make_title.py --kicker "Golems Don't Die" --title "Exploration Expansion" \
        --tagline "The Golems have learned to sail..." --title-colour purple --figure media/raft.png \
        --background media/ExplorationBanner1.webp --focus 0.5,0.5 --out media/exploration-title.png

The Personality Expansion's, over the island, in yellow, with a golem doing the Party emote:

    python make_title.py --kicker "Golems Don't Die" --title "Personality Expansion" \
        --tagline "The Golems have found themselves..." --title-colour yellow --tagline-colour gold \
        --figure media/party-golem.png --out media/personality-title.png

media/raft.png is a golem at the helm of its raft, exported from the running client by BrandingExport
(test sources) as the game builds, poses and lights them, then drawn:

    python dev-tools/branding/render_export.py ~/.runelite/golem-exports/crewed-raft-frame4.json media/raft.png --yaw 300 --pitch 16 --height 360

and media/party-golem.png likewise, from the Party emote's first frame, lights and all:

    python dev-tools/branding/render_export.py ~/.runelite/golem-exports/party-frame0.json media/party-golem.png --yaw 20 --pitch 10 --height 360 --fit-part 0 --margin 40

Paths are relative to the plugin's root.
"""
import argparse
import os
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageEnhance

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

WIDTH, HEIGHT = 1600, 420

# The game's interface colours.
TITLE_GOLD = (255, 152, 31)
# An expansion's title: a light purple, bright enough over a dark background with the black shadow.
TITLE_PURPLE = (190, 110, 255)
TITLE_YELLOW = (255, 255, 0)
TAGLINE_YELLOW = (255, 255, 0)
KICKER_WHITE = (255, 255, 255)
SHADOW = (0, 0, 0)
EDGE = (92, 72, 46)


def font_path(font_file):
    """The game's own font, which is not redistributed with this repo.

    Put a copy in dev-tools/branding/fonts, or point GOLEM_FONTS at the folder holding them.
    """
    folder = Path(os.environ.get("GOLEM_FONTS", HERE / "fonts"))
    found = folder / font_file
    if not found.exists():
        raise SystemExit(
            f"{font_file} not found in {folder}. The RuneScape fonts are not shipped with this "
            f"repository; copy them there or set GOLEM_FONTS."
        )
    return found


def pixel_text(text, font_file, size, colour, scale):
    """Text at the font's own size with the game's one-pixel shadow, scaled up by whole pixels."""
    font = ImageFont.truetype(str(font_path(font_file)), size)
    _, top, _, bottom = font.getbbox(text)

    # Letter by letter, so an apostrophe can sit tight against the letter before it: the font gives
    # it a full-width cell with the mark at the right, which opened a gap in "Don't".
    placed = []
    x = 0
    for ch in text:
        advance = font.getlength(ch)
        if ch in "'’":
            ink = glyph_columns(font, ch)
            if ink:
                x -= ink[0]
                advance = ink[1] + 2
        placed.append((ch, x))
        x += advance

    w, h = int(x) + 2, bottom - top + 2
    small = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(small)
    # Drawn without anti-aliasing: a bitmap font should come out as solid pixels.
    draw.fontmode = "1"
    for ch, cx in placed:
        draw.text((cx + 1, 1 - top), ch, font=font, fill=SHADOW + (255,))
    for ch, cx in placed:
        draw.text((cx, -top), ch, font=font, fill=colour + (255,))
    return small.resize((w * scale, h * scale), Image.NEAREST)


def glyph_columns(font, ch):
    """The first and last columns a glyph actually marks, or None for a blank one."""
    probe = Image.new("L", (32, 32), 0)
    draw = ImageDraw.Draw(probe)
    draw.fontmode = "1"
    draw.text((0, 0), ch, font=font, fill=255)
    box = probe.getbbox()
    return None if box is None else (box[0], box[2] - 1)


def background(path, focus):
    """A screenshot cropped to the banner, pulled back into a dark, warm band so the lettering reads over it."""
    shot = Image.open(path).convert("RGB")
    scale = max(WIDTH / shot.width, HEIGHT / shot.height)
    shot = shot.resize((round(shot.width * scale), round(shot.height * scale)), Image.LANCZOS)
    left = min(max(0, round(shot.width * focus[0] - WIDTH / 2)), shot.width - WIDTH)
    top = min(max(0, round(shot.height * focus[1] - HEIGHT / 2)), shot.height - HEIGHT)
    band = shot.crop((left, top, left + WIDTH, top + HEIGHT))
    band = band.filter(ImageFilter.GaussianBlur(3))
    band = ImageEnhance.Brightness(band).enhance(0.7)

    # Darker toward the left, where the lettering sits, and at the top and bottom edges.
    shade = Image.new("L", (WIDTH, HEIGHT))
    px = shade.load()
    for x in range(WIDTH):
        across = max(0.0, 1.0 - x / (WIDTH * 0.62))
        for y in range(HEIGHT):
            edge = abs(y - HEIGHT / 2) / (HEIGHT / 2)
            px[x, y] = int(255 * min(1.0, 0.8 * across ** 1.4 + 0.5 * edge ** 3))
    black = Image.new("RGB", (WIDTH, HEIGHT), (12, 9, 6))
    return Image.composite(black, band, shade)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--title", default="Exploration Expansion")
    parser.add_argument("--tagline", default="The Golems have learned to sail...")
    parser.add_argument("--kicker", default="", help="small line above the title, such as the plugin's name")
    parser.add_argument("--background", required=True, help="the picture behind the title")
    parser.add_argument("--focus", default="0.5,0.5", help="where in the background to keep, as fractions")
    parser.add_argument("--out", default="media/exploration-title.png")
    parser.add_argument("--figure", required=True, help="the picture standing at the right")
    parser.add_argument("--title-colour", default="gold", choices=["gold", "purple", "yellow"])
    parser.add_argument("--tagline-colour", default="yellow", choices=["yellow", "gold"],
        help="gold for a yellow title, so the two lines do not run together")
    args = parser.parse_args()

    focus = tuple(float(v) for v in args.focus.split(","))
    canvas = background(ROOT / args.background, focus).convert("RGBA")

    # The figure at the right — the golem, or a raft — centred top to bottom.
    golem = Image.open(ROOT / args.figure).convert("RGBA")
    golem_height = HEIGHT - 60
    golem = golem.resize((int(golem.width * golem_height / golem.height), golem_height), Image.LANCZOS)
    gx = WIDTH - golem.width - 90
    gy = (HEIGHT - golem.height) // 2
    glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    halo = Image.new("RGBA", golem.size, (255, 170, 60, 0))
    halo.putalpha(golem.getchannel("A").point(lambda a: 110 if a else 0))
    glow.alpha_composite(halo, (gx, gy))
    glow = glow.filter(ImageFilter.GaussianBlur(28))
    canvas.alpha_composite(glow)
    canvas.alpha_composite(golem, (gx, gy))

    x = 90
    parts = []
    if args.kicker:
        parts.append((pixel_text(args.kicker, "runescape_bold.ttf", 16, KICKER_WHITE, 3), 6, 18))
    title_colour = {"purple": TITLE_PURPLE, "yellow": TITLE_YELLOW}.get(args.title_colour, TITLE_GOLD)
    parts.append((pixel_text(args.title, "runescape_bold.ttf", 16, title_colour, 6), 0, 22))
    if args.tagline:
        tagline_colour = TITLE_GOLD if args.tagline_colour == "gold" else TAGLINE_YELLOW
        parts.append((pixel_text(args.tagline, "runescape.ttf", 16, tagline_colour, 3), 6, 0))

    block = sum(image.height + gap for image, _, gap in parts)
    y = (HEIGHT - block) // 2
    for image, indent, gap in parts:
        canvas.alpha_composite(image, (x + indent, y))
        y += image.height + gap

    # A thin rule along the bottom, like the edge of a game interface.
    ImageDraw.Draw(canvas).rectangle((0, HEIGHT - 4, WIDTH, HEIGHT), fill=EDGE + (255,))

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(out, optimize=True)
    print("wrote", out)


if __name__ == "__main__":
    main()
