"""Draws the golem's face as a world map icon, in the game's own icon style.

The game's map icons are about fifteen pixels square, drawn flat with a hard black outline, so a
photograph of a model shrunk down reads as mud. This renders the golem's head face-on, lit as the
game lights it and with the red eyes of the plugin's own branding, then treats it the way those
icons are treated: shrunk to a dozen pixels, contrast lifted so the shapes survive, and wrapped in a
one-pixel black outline that carries the silhouette at that size.

    python dev-tools/branding/make_map_icon.py [export] [out]
        ~/.runelite/golem-exports/golem-idle.json -> src/main/resources/golem-map-icon.png
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

# In the golem's model units: the two dark sockets under its brow.
EYES = "-6,-181,-14;6,-181,-14"

# How much of the figure, from the top, is head. Shoulders are left out: included, the head shrinks
# to a knob on a wide dark mass and the face is lost at this size.
HEAD_SHARE = 0.13

# The eye glow's radius in model units. Enough to leave two red marks under the brow at fifteen
# pixels, no more: at 1.5 the glow spreads across the face and at 2.1 the head is one orange blob.
GLOW_SIZE = "1.0"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("export", nargs="?",
        default=str(Path.home() / ".runelite" / "golem-exports" / "golem-idle.json"))
    parser.add_argument("out", nargs="?", default=str(ROOT / "src/main/resources/golem-map-icon.png"))
    parser.add_argument("--size", type=int, default=15, help="the icon's width and height")
    parser.add_argument("--outline", default="0,0,0,255")
    parser.add_argument("--contrast", type=float, default=1.3)
    parser.add_argument("--saturation", type=float, default=1.25)
    parser.add_argument("--brightness", type=float, default=1.0)
    parser.add_argument("--colours", type=int, default=12, help="0 to keep the render's own shading")
    parser.add_argument("--glow-size", default=GLOW_SIZE, help="the eye glow's radius, in model units")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as scratch:
        rendered = Path(scratch) / "head.png"
        subprocess.run([sys.executable, str(HERE / "render_export.py"), args.export, str(rendered),
            "--yaw", "0", "--pitch", "2", "--height", "1200", "--margin", "0",
            "--glow", EYES, "--glow-size", str(args.glow_size)], check=True)
        big = Image.open(rendered).convert("RGBA")

    # Head and shoulders, then trimmed to what is actually drawn.
    figure = big.crop(big.getbbox())
    head = figure.crop((0, 0, figure.width, round(figure.height * HEAD_SHARE)))
    head = head.crop(head.getbbox())

    # Lifted before shrinking: a dozen pixels of a dim model is a smudge.
    head = ImageEnhance.Color(head).enhance(args.saturation)
    head = ImageEnhance.Brightness(head).enhance(args.brightness)
    head = ImageEnhance.Contrast(head).enhance(args.contrast)

    # Flattened to a handful of colours, as the game's own icons are drawn: smooth shading turns to
    # noise at this size, while flat planes keep the shape of the head.
    if args.colours:
        flat = head.convert("RGB").quantize(colors=args.colours, method=Image.MEDIANCUT).convert("RGB")
        head = Image.merge("RGBA", (*flat.split(), head.getchannel("A")))

    # The outline keeps its weight relative to the icon: one pixel at map size, thicker on a larger
    # copy, so the same icon reads the same drawn big.
    edge = max(1, round(args.size / 15))
    inner = args.size - edge * 2
    scale = min(inner / head.width, inner / head.height)
    small = head.resize((max(1, round(head.width * scale)), max(1, round(head.height * scale))), Image.LANCZOS)

    icon = Image.new("RGBA", (args.size, args.size), (0, 0, 0, 0))
    icon.alpha_composite(small, ((args.size - small.width) // 2, (args.size - small.height) // 2))

    # The outline: everything within a pixel of the silhouette, painted under it. Alpha is made
    # hard first, because a soft edge dilates into a grey halo rather than a line.
    alpha = icon.getchannel("A").point(lambda a: 255 if a > 96 else 0)
    grown = alpha.filter(ImageFilter.MaxFilter(edge * 2 + 1))
    outline = Image.new("RGBA", icon.size, tuple(int(v) for v in args.outline.split(",")))
    outline.putalpha(grown)
    outline.alpha_composite(Image.composite(icon, Image.new("RGBA", icon.size, (0, 0, 0, 0)), alpha))

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    outline.save(args.out)
    print("wrote", args.out, outline.size)


if __name__ == "__main__":
    main()
