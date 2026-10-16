# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow"]
# ///
"""Puts phone screenshots side by side with rounded corners: compose.py out.png shot1.png shot2.png ..."""
import sys
from PIL import Image, ImageDraw

W, GAP = 432, 28
shots = [Image.open(p).convert("RGB") for p in sys.argv[2:]]
H = round(W * shots[0].height / shots[0].width)
out = Image.new("RGBA", (len(shots) * W + (len(shots) - 1) * GAP, H), (0, 0, 0, 0))
for i, s in enumerate(shots):
    s = s.resize((W, H), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), radius=36, fill=255)
    out.paste(s, (i * (W + GAP), 0), mask)
out.save(sys.argv[1])
