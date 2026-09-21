"""
Draws the Plugin Hub icon: a golem standing, with the red eyes the model does not have.

The golem is the idle pose exported from the running client by BrandingExport, lit as the game lights
it. It is drawn large, then shrunk to fit the Hub's limit of 48 by 72 pixels, standing on the bottom
edge and centred across. The eyes glow a little larger than life so they survive the shrinking.

    python dev-tools/branding/make_icon.py [export] [out]
        ~/.runelite/golem-exports/golem-idle.json -> icon.png
"""
import subprocess
import sys
import tempfile
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

LIMIT_WIDTH, LIMIT_HEIGHT = 48, 72

# In the golem's model units: the two dark sockets under its brow.
EYES = "-6,-181,-14;6,-181,-14"


def main():
    export = sys.argv[1] if len(sys.argv) > 1 else str(Path.home() / ".runelite" / "golem-exports" / "golem-idle.json")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "icon.png"

    with tempfile.TemporaryDirectory() as scratch:
        big_path = Path(scratch) / "golem.png"
        subprocess.run([sys.executable, str(HERE / "render_export.py"), export, str(big_path),
            "--yaw", "16", "--pitch", "4", "--height", "1080", "--margin", "16",
            "--glow", EYES, "--glow-size", "3.2"], check=True)
        big = Image.open(big_path)
        big = big.crop(big.getbbox())

    height = LIMIT_HEIGHT
    width = round(big.width * height / big.height)
    if width > LIMIT_WIDTH:
        width = LIMIT_WIDTH
        height = round(big.height * width / big.width)
    small = big.resize((width, height), Image.LANCZOS)
    icon = Image.new("RGBA", (LIMIT_WIDTH, LIMIT_HEIGHT), (0, 0, 0, 0))
    icon.alpha_composite(small, ((LIMIT_WIDTH - width) // 2, LIMIT_HEIGHT - height))
    icon.save(out)
    print("wrote", out, icon.size)


if __name__ == "__main__":
    main()
