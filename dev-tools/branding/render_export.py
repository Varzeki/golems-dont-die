"""
Draws a model exported from the running client by BrandingExport (test sources) as a transparent PNG.

The export carries the game's own lighting: three colours per face, one at each corner, already lit
as the client lights them. They are blended across each face, as the game blends them, and faces are
sorted by a depth buffer rather than painted in order, so nothing near shows through anything far.

    python render_export.py ~/.runelite/golem-exports/crewed-raft-frame4.json media/raft.png \
        --yaw 300 --pitch 16 --height 360

Yaw turns the model about its upright axis and pitch tips the view down onto it, both in degrees.
Drawn at three times the size and scaled down, for clean edges.
"""
import argparse
import json
import math
import numpy as np
from PIL import Image, ImageFilter

SUPERSAMPLE = 3
BRIGHTNESS = 0.8


def hsl_to_rgb(hsl):
    """The game's 16-bit colour, as RuneLite's JagexColor.HSLtoRGB unpacks it."""
    hue = ((hsl >> 10) & 63) / 64.0 + 0.0078125
    saturation = ((hsl >> 7) & 7) / 8.0 + 0.0625
    luminance = (hsl & 127) / 128.0
    chroma = (1.0 - abs(2.0 * luminance - 1.0)) * saturation
    x = chroma * (1 - abs(((hue * 6.0) % 2.0) - 1.0))
    lightness = luminance - chroma / 2
    sector = int(hue * 6.0) % 6
    r, g, b = [(chroma, x, 0), (x, chroma, 0), (0, chroma, x), (0, x, chroma), (x, 0, chroma), (chroma, 0, x)][sector]
    return [max(0.0, min(1.0, c + lightness)) ** BRIGHTNESS for c in (r, g, b)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("export")
    parser.add_argument("out")
    parser.add_argument("--yaw", type=float, default=300)
    parser.add_argument("--pitch", type=float, default=16)
    parser.add_argument("--height", type=int, default=360)
    parser.add_argument("--margin", type=int, default=10)
    parser.add_argument("--fit-part", type=int, default=-1,
        help="size the image to this part alone, cropping the rest; the golem rather than its confetti")
    parser.add_argument("--glow", default="",
        help="glowing points in model units, 'x,y,z;x,y,z' — a golem's red eyes, which the model does not have")
    parser.add_argument("--glow-colour", default="255,40,30")
    parser.add_argument("--glow-size", type=float, default=2.2, help="the bright core's radius, in model units")
    args = parser.parse_args()

    data = json.load(open(args.export))
    yaw = math.radians(args.yaw)
    pitch = math.radians(args.pitch)

    vertices = []
    triangles = []  # (indices, corner colours, alpha)
    fit = []
    for number, part in enumerate(data["parts"]):
        base = len(vertices)
        if number == args.fit_part:
            fit = list(range(base, base + len(part["vertices"])))
        vertices.extend(part["vertices"])
        for a, b, c, c1, c2, c3, alpha, _ in part["faces"]:
            if c3 == -2:
                continue  # hidden in the game
            if c3 == -1:
                c2 = c3 = c1  # flat shaded
            triangles.append(((base + a, base + b, base + c), (c1, c2, c3), alpha))

    v = np.array(vertices, dtype=np.float64)
    x1 = v[:, 0] * math.cos(yaw) + v[:, 2] * math.sin(yaw)
    z1 = -v[:, 0] * math.sin(yaw) + v[:, 2] * math.cos(yaw)
    y2 = v[:, 1] * math.cos(pitch) - z1 * math.sin(pitch)
    z2 = v[:, 1] * math.sin(pitch) + z1 * math.cos(pitch)

    used = fit or sorted({i for t in triangles for i in t[0]})
    min_x, max_x = x1[used].min(), x1[used].max()
    min_y, max_y = y2[used].min(), y2[used].max()
    s = SUPERSAMPLE
    scale = (args.height - 2 * args.margin) / (max_y - min_y) * s
    width = int((max_x - min_x) * scale / s) + 2 * args.margin
    W, H = width * s, args.height * s
    sx = (x1 - min_x) * scale + args.margin * s
    sy = (y2 - min_y) * scale + args.margin * s

    colour = np.zeros((H, W, 3))
    coverage = np.zeros((H, W))
    depth = np.full((H, W), np.inf)

    rgb_cache = {}

    def rgb(hsl):
        if hsl not in rgb_cache:
            rgb_cache[hsl] = np.array(hsl_to_rgb(hsl & 0xffff))
        return rgb_cache[hsl]

    def raster(tri, colours, opacity, write_depth):
        i, j, k = tri
        xs = np.array([sx[i], sx[j], sx[k]])
        ys = np.array([sy[i], sy[j], sy[k]])
        x0, x1_ = max(0, int(xs.min())), min(W - 1, int(math.ceil(xs.max())))
        y0, y1_ = max(0, int(ys.min())), min(H - 1, int(math.ceil(ys.max())))
        if x0 > x1_ or y0 > y1_:
            return
        area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0])
        if abs(area) < 1e-9:
            return
        gx, gy = np.meshgrid(np.arange(x0, x1_ + 1) + 0.5, np.arange(y0, y1_ + 1) + 0.5)
        w0 = ((xs[1] - gx) * (ys[2] - gy) - (xs[2] - gx) * (ys[1] - gy)) / area
        w1 = ((xs[2] - gx) * (ys[0] - gy) - (xs[0] - gx) * (ys[2] - gy)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not inside.any():
            return
        d = w0 * z2[i] + w1 * z2[j] + w2 * z2[k]
        region = depth[y0:y1_ + 1, x0:x1_ + 1]
        visible = inside & (d < region)
        if not visible.any():
            return
        ca, cb, cc = rgb(colours[0]), rgb(colours[1]), rgb(colours[2])
        pixel = w0[..., None] * ca + w1[..., None] * cb + w2[..., None] * cc
        target = colour[y0:y1_ + 1, x0:x1_ + 1]
        cover = coverage[y0:y1_ + 1, x0:x1_ + 1]
        target[visible] = target[visible] * (1 - opacity) + pixel[visible] * opacity
        cover[visible] = cover[visible] * (1 - opacity) + opacity
        if write_depth:
            region[visible] = d[visible]

    # Solid faces through the depth buffer; see-through ones after, far to near, blended over them.
    solid = [t for t in triangles if t[2] == 0]
    clear = [t for t in triangles if t[2] != 0]
    for tri, colours, _ in solid:
        raster(tri, colours, 1.0, True)
    clear.sort(key=lambda t: -(z2[t[0][0]] + z2[t[0][1]] + z2[t[0][2]]))
    for tri, colours, alpha in clear:
        raster(tri, colours, 1.0 - alpha / 255.0, False)

    rgba = np.dstack([np.clip(colour, 0, 1) * 255, np.clip(coverage, 0, 1) * 255]).astype(np.uint8)
    image = Image.fromarray(rgba, "RGBA")

    if args.glow:
        # Each point: a soft halo that spills past the model's edge, then a hot core over it. Only where
        # the point is in front of the model, so an eye does not shine through the back of the head.
        glow_rgb = tuple(int(c) for c in args.glow_colour.split(","))
        core = Image.new("L", (W, H), 0)
        halo = Image.new("L", (W, H), 0)
        from PIL import ImageDraw
        cd, hd = ImageDraw.Draw(core), ImageDraw.Draw(halo)
        for point in args.glow.split(";"):
            gx_, gy_, gz_ = (float(c) for c in point.split(","))
            px = gx_ * math.cos(yaw) + gz_ * math.sin(yaw)
            pz1 = -gx_ * math.sin(yaw) + gz_ * math.cos(yaw)
            py = gy_ * math.cos(pitch) - pz1 * math.sin(pitch)
            pz = gy_ * math.sin(pitch) + pz1 * math.cos(pitch)
            u = (px - min_x) * scale + args.margin * s
            w = (py - min_y) * scale + args.margin * s
            iu, iw = int(u), int(w)
            if 0 <= iu < W and 0 <= iw < H and pz > depth[iw, iu] + 6:
                continue
            r = args.glow_size * scale
            cd.ellipse((u - r, w - r, u + r, w + r), fill=255)
            hd.ellipse((u - r * 3, w - r * 3, u + r * 3, w + r * 3), fill=160)
        halo = halo.filter(ImageFilter.GaussianBlur(args.glow_size * scale * 2))
        core = core.filter(ImageFilter.GaussianBlur(max(1, args.glow_size * scale * 0.35)))
        glow_layer = Image.new("RGBA", (W, H), glow_rgb + (0,))
        glow_layer.putalpha(halo)
        image.alpha_composite(glow_layer)
        hot = Image.new("RGBA", (W, H), (255, 130, 110, 0))
        hot.putalpha(core)
        image.alpha_composite(hot)

    image = image.resize((width, args.height), Image.LANCZOS)
    image.save(args.out)
    print("wrote", args.out, image.size, len(triangles), "faces")


if __name__ == "__main__":
    main()
