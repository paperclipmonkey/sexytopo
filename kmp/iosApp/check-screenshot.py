#!/usr/bin/env python3
"""Did the app actually draw?

CI boots a simulator, installs the app and photographs it. This is what turns that photograph
into a check.

Two conditions, and the second is the one that took a moment's thought. The first is the app's own
panel green — 0x7F/0xAF/0x7F, from `colors.xml` — which fills the app bar and sits under the
toolbar; an app that crashed on launch shows Springboard and an app that came up with no UI shows
a white canvas, and neither has any of that colour in it.

But the *launch screen* is that same green, deliberately, so an app that hung before Compose drew
its first frame would sail through a green-only check looking exactly like a working one. So the
second condition is that the picture has many distinct colours in it: the launch screen is one
flat colour and a status bar, while the app is eight toolbar swatches, a red centreline, a grid
and two sizes of text.

A PNG decoder in the standard library because a macOS runner has no Pillow and installing one to
count colours would be a slower answer to a smaller question. The image is downscaled by `sips`
first, so this is a few tens of thousands of pixels rather than three million.
"""

import struct
import sys
import zlib

PANEL_GREEN = (0x7F, 0xAF, 0x7F)
# Enough tolerance to survive the rescale, which blends the green with whatever borders it.
TOLERANCE = 14
# The app bar alone is far more than this at 240 pixels wide; Springboard is none of it.
NEEDED = 500
# Distinct colours, quantised to five bits a channel so that antialiasing and the rescale do not
# inflate the count on their own. A flat launch screen with a status bar over it is a handful;
# every rendering of the actual app measured here is in the hundreds.
DISTINCT_NEEDED = 40


def decode(path):
    """Returns (width, height, pixels-as-bytes, bytes-per-pixel)."""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path} is not a PNG")

    position, width, height, channels, compressed = 8, 0, 0, 0, b""
    while position < len(data):
        (length,), kind = struct.unpack(">I", data[position:position + 4]), data[position + 4:position + 8]
        body = data[position + 8:position + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour = struct.unpack(">IIBB", body[:10])
            if depth != 8 or colour not in (2, 6):
                raise SystemExit(f"unexpected PNG format: {depth}-bit, colour type {colour}")
            channels = 3 if colour == 2 else 4
        elif kind == b"IDAT":
            compressed += body
        position += length + 12

    raw = zlib.decompress(compressed)
    stride = width * channels
    out = bytearray()
    previous = bytearray(stride)
    at = 0
    for _ in range(height):
        filter_type = raw[at]
        line = bytearray(raw[at + 1:at + 1 + stride])
        at += stride + 1
        if filter_type:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                b = previous[x]
                c = previous[x - channels] if x >= channels else 0
                if filter_type == 1:
                    line[x] = (line[x] + a) & 0xFF
                elif filter_type == 2:
                    line[x] = (line[x] + b) & 0xFF
                elif filter_type == 3:
                    line[x] = (line[x] + (a + b) // 2) & 0xFF
                elif filter_type == 4:
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    nearest = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                    line[x] = (line[x] + nearest) & 0xFF
                else:
                    raise SystemExit(f"unknown PNG filter {filter_type}")
        out += line
        previous = line
    return width, height, bytes(out), channels


def main(path):
    width, height, pixels, channels = decode(path)
    green = 0
    seen = set()
    for at in range(0, len(pixels), channels):
        r, g, b = pixels[at], pixels[at + 1], pixels[at + 2]
        if abs(r - PANEL_GREEN[0]) < TOLERANCE and abs(g - PANEL_GREEN[1]) < TOLERANCE \
                and abs(b - PANEL_GREEN[2]) < TOLERANCE:
            green += 1
        seen.add((r >> 3, g >> 3, b >> 3))

    print(f"{width}x{height}: {green} pixels of the app's own panel green, "
          f"{len(seen)} distinct colours")

    if green < NEEDED:
        raise SystemExit(
            "the app did not draw — its own green is not on the screen. "
            "Either it crashed on launch, or Compose came up with nothing in it. "
            "The screenshot is uploaded as an artifact."
        )
    if len(seen) < DISTINCT_NEEDED:
        raise SystemExit(
            "the app is showing its launch screen and nothing else — that is the same green, so "
            "the check above cannot tell them apart, but a drawn survey is not one flat colour. "
            "Compose most likely never got to its first frame."
        )


if __name__ == "__main__":
    main(sys.argv[1])
