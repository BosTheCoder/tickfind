# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow"]
# ///
"""Draws the Tickfind icon (a magnifier with a tick in it, on TickTick blue to violet) and writes
assets/tickfind.png, windows/assets/tickfind.ico and the Android launcher foreground."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).parent.parent
S = 1024

def gradient(size, top=(71, 114, 250), bottom=(112, 64, 214)):
    g = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / (size - 1)
        g.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return g.resize((size, size))

def glyph(img, scale=1.0, cx=S * 0.46, cy=S * 0.44):
    """The magnifier: a ring, a handle and a tick inside, in white."""
    d = ImageDraw.Draw(img)
    r, w = 250 * scale, 74 * scale
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline="white", width=round(w))
    hx, hy = cx + r * 0.72, cy + r * 0.72
    d.line((hx, hy, hx + 210 * scale, hy + 210 * scale), fill="white", width=round(w * 1.25))
    d.ellipse((hx + 210 * scale - w * 0.62, hy + 210 * scale - w * 0.62, hx + 210 * scale + w * 0.62, hy + 210 * scale + w * 0.62), fill="white")
    t = [(cx - 115 * scale, cy + 5 * scale), (cx - 30 * scale, cy + 90 * scale), (cx + 125 * scale, cy - 80 * scale)]
    d.line(t, fill="white", width=round(w * 0.8), joint="curve")
    for x, y in (t[0], t[2]):
        e = w * 0.4
        d.ellipse((x - e, y - e, x + e, y + e), fill="white")

def tile():
    im = gradient(S).convert("RGBA")
    glyph(im)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=S * 0.22, fill=255)
    im.putalpha(mask)
    return im

big = tile()
big.resize((256, 256), Image.LANCZOS).save(ROOT / "assets/tickfind.png")
frames = [big.resize((s, s), Image.LANCZOS) for s in (16, 24, 32, 48, 64, 128, 256)]
frames[-1].save(ROOT / "windows/assets/tickfind.ico", format="ICO", sizes=[f.size for f in frames], append_images=frames[:-1])

# Android adaptive icon: the glyph alone on transparent, inside the 66% safe zone; the background is a colour resource.
fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
glyph(fg, scale=0.62, cx=S * 0.475, cy=S * 0.465)
out = ROOT / "android/app/src/main/res/mipmap-xxxhdpi"
out.mkdir(parents=True, exist_ok=True)
fg.resize((432, 432), Image.LANCZOS).save(out / "ic_launcher_foreground.png")
print("icons written")
