#!/usr/bin/env python3
"""Builds the Google Play store listing from tools/store/listing.yaml (M8 §5, D-M8-6).

Writes, under play/listing/<locale>/:
    title.txt, short_description.txt, full_description.txt   the listing text (Play limits 30 / 80 / 4000 characters)
    images/icon.png                                          512 x 512, from the launcher's adaptive-icon vectors
    images/feature_graphic.png                               1024 x 500
    images/phone/light/NN-name.png, images/phone/dark/...    1080 x 1920 (9:16), from the screenshot-test goldens
    images/tablet/light/NN-name.png                          1920 x 1080 (16:9), for both the 7- and 10-inch slots

and checks what Play checks: PNG without alpha, at most 8 MB each, the longest side at most twice the shortest, at
most 8 screenshots per device type; plus the repository's budget of 3 MB for all images.

Phone shots show each golden's whole first screen (D-M9-20, so the app's bottom bar is in the picture): a golden is cut
to one Pixel 7 screenful (1078 x 2399 px; goldens recorded with a taller window keep their top), then scaled down to
the card's height, aspect kept and centred; the three-tier shot scales each screen to its column, top-aligned under
the labels, and closes the cards at the screen's foot. Tablet goldens (1280 x 800) are still pasted 1:1. Scaling uses bicubic resampling.

The icon is drawn from the path data of app/src/main/res/drawable/ic_launcher_foreground.xml on the colour of
@color/ic_launcher_background (a small path rasteriser below: M/L/H/V/C/Z, absolute and relative), rendered at 4x and
downsampled, so no SVG tool or Gradle export is needed. The visible part of an adaptive icon (the middle 72 of its
108 units) fills the 512 px square; Play applies its own mask.

Requirements: Python 3.9+, Pillow 11.3.0 and PyYAML 6.0.3 (`pip3 install --user pillow==11.3.0 pyyaml==6.0.3`), and a
TrueType font: SPROUT_STORE_FONT / SPROUT_STORE_FONT_BOLD, else the first found of Arial (macOS), DejaVu Sans
(Linux) or Roboto. The committed images were made on macOS with Arial; another font gives slightly different images.
The output is deterministic for a given font and Pillow version (PNG metadata is not written).

Usage: python3 tools/store/compose.py [--check]   (--check verifies the committed files instead of writing them)
"""
from __future__ import annotations

import argparse
import io
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Dict, List, Optional, Tuple

try:
    import yaml
    from PIL import Image, ImageDraw, ImageFont
except ImportError as missing:  # pragma: no cover
    sys.exit(f"compose.py needs Pillow and PyYAML: pip3 install --user pillow==11.3.0 pyyaml==6.0.3 ({missing})")

ROOT = Path(__file__).resolve().parents[2]
LISTING = ROOT / "tools" / "store" / "listing.yaml"
FOREGROUND = ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
COLOURS = ROOT / "app" / "src" / "main" / "res" / "values" / "colors.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"

PHONE = (1080, 1920)
# The phone goldens' screen: ScreenshotDevice.PIXEL_7, 411 x 914 dp at 420 dpi, is 1078 x 2399 px. A golden taller than
# that (a test that adds `+h1500dp`) is cut to its first screenful before it is fitted into a phone card (D-M9-20).
PHONE_SCREEN_ASPECT = 2399 / 1078
TABLET = (1920, 1080)
ICON = (512, 512)
FEATURE = (1024, 500)
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 3 * 1024 * 1024
TEXT_LIMITS = {"title": 30, "short_description": 80, "full_description": 4000}

FONT_CANDIDATES = {
    "regular": [
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf",
    ],
    "bold": [
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Bold.ttf",
    ],
}


class ComposeError(Exception):
    pass


def font(kind: str, size: int) -> ImageFont.FreeTypeFont:
    override = os.environ.get("SPROUT_STORE_FONT_BOLD" if kind == "bold" else "SPROUT_STORE_FONT")
    for path in ([override] if override else []) + FONT_CANDIDATES[kind]:
        if path and Path(path).is_file():
            return ImageFont.truetype(path, size)
    raise ComposeError(f"no {kind} TrueType font found; set SPROUT_STORE_FONT{'_BOLD' if kind == 'bold' else ''}")


def hex_colour(value: str) -> Tuple[int, int, int]:
    value = value.lstrip("#")
    if len(value) == 8:  # Android's AARRGGBB
        value = value[2:]
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


# --------------------------------------------------------------------------------------------------------------------
# Vector icon


def path_polygons(data: str, steps: int = 48) -> List[List[Tuple[float, float]]]:
    """Flattens Android pathData (M, L, H, V, C, Z; upper case absolute, lower case relative) into polygons."""
    tokens = re.findall(r"[MmLlHhVvCcZz]|-?\d*\.?\d+(?:e-?\d+)?", data)
    polygons: List[List[Tuple[float, float]]] = []
    current: List[Tuple[float, float]] = []
    x = y = 0.0
    start = (0.0, 0.0)
    i = 0
    command = ""

    def number() -> float:
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    while i < len(tokens):
        if re.match(r"[A-Za-z]", tokens[i]):
            command = tokens[i]
            i += 1
        relative = command.islower()
        op = command.upper()
        if op == "M":
            if current:
                polygons.append(current)
            nx, ny = number(), number()
            x, y = (x + nx, y + ny) if relative else (nx, ny)
            start = (x, y)
            current = [(x, y)]
            command = "l" if relative else "L"  # further pairs are line-tos
        elif op == "L":
            nx, ny = number(), number()
            x, y = (x + nx, y + ny) if relative else (nx, ny)
            current.append((x, y))
        elif op == "H":
            n = number()
            x = x + n if relative else n
            current.append((x, y))
        elif op == "V":
            n = number()
            y = y + n if relative else n
            current.append((x, y))
        elif op == "C":
            values = [number() for _ in range(6)]
            if relative:
                values = [v + (x if k % 2 == 0 else y) for k, v in enumerate(values)]
            x1, y1, x2, y2, x3, y3 = values
            for step in range(1, steps + 1):
                t = step / steps
                u = 1 - t
                current.append((
                    u ** 3 * x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3,
                    u ** 3 * y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3,
                ))
            x, y = x3, y3
        elif op == "Z":
            if current:
                polygons.append(current)
            current = []
            x, y = start
        else:
            raise ComposeError(f"unsupported path command {command!r} in the launcher icon")
    if current:
        polygons.append(current)
    return polygons


def launcher_layers() -> Tuple[Tuple[int, int, int], List[Tuple[Tuple[int, int, int], List[List[Tuple[float, float]]]]]]:
    background = None
    for colour in ElementTree.parse(COLOURS).getroot().iter("color"):
        if colour.get("name") == "ic_launcher_background":
            background = hex_colour(colour.text or "")
    if background is None:
        raise ComposeError("@color/ic_launcher_background not found")
    vector = ElementTree.parse(FOREGROUND).getroot()
    # The rasteriser fills plain paths only (review L6): a group transform, a fill rule, an alpha or a stroke would
    # compose wrongly without a sign, so they are refused until compose.py learns them.
    unsupported = [element.tag for element in vector.iter() if element.tag not in ("vector", "path")]
    for element in vector.iter("path"):
        unsupported += [
            name.replace(ANDROID, "android:")
            for name in element.attrib
            if name not in (ANDROID + "fillColor", ANDROID + "pathData")
        ]
    if unsupported:
        raise ComposeError(
            f"the launcher foreground uses {', '.join(sorted(set(unsupported)))}, which compose.py does not draw"
        )
    paths = []
    for element in vector.iter("path"):
        paths.append((hex_colour(element.get(ANDROID + "fillColor", "#FF000000")), path_polygons(element.get(ANDROID + "pathData", ""))))
    return background, paths


def draw_foreground(size: int, origin: Tuple[float, float], units: Tuple[float, float, float]) -> Image.Image:
    """The foreground vector on a transparent square of [size] px; [units] = (left, top, width) of the viewport shown."""
    _, paths = launcher_layers()
    scale = 4
    canvas = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    left, top, width = units
    factor = size * scale / width
    for colour, polygons in paths:
        for polygon in polygons:
            draw.polygon([((px - left) * factor, (py - top) * factor) for px, py in polygon], fill=colour + (255,))
    return canvas.resize((size, size), Image.LANCZOS)


def icon() -> Image.Image:
    background, _ = launcher_layers()
    image = Image.new("RGB", ICON, background)
    image.paste(draw_foreground(ICON[0], (0, 0), (18, 18, 72)), (0, 0), draw_foreground(ICON[0], (0, 0), (18, 18, 72)))
    return image


def feature_graphic(colours: Dict[str, Tuple[int, int, int]]) -> Image.Image:
    background, _ = launcher_layers()
    image = Image.new("RGB", FEATURE, background)
    mark = draw_foreground(360, (0, 0), (18, 18, 72))
    image.paste(mark, (60, 70), mark)
    draw = ImageDraw.Draw(image)
    draw.text((450, 150), "Sprout", font=font("bold", 120), fill=colours["brand_dark"])
    tagline = font("regular", 40)
    draw.text((456, 300), "Chores, pocket money and", font=tagline, fill=colours["caption_light"])
    draw.text((456, 350), "screen time for families", font=tagline, fill=colours["caption_light"])
    return image


# --------------------------------------------------------------------------------------------------------------------
# Screenshots


def golden(path: str) -> Image.Image:
    file = ROOT / path
    if not file.is_file():
        raise ComposeError(f"golden not found: {path}")
    image = Image.open(file).convert("RGB")
    return trim_bottom(image)


def trim_bottom(image: Image.Image) -> Image.Image:
    """Drops the empty background below the content (tall goldens have their window's height)."""
    width, height = image.size
    background = image.getpixel((2, height - 2))
    pixels = image.load()
    for y in range(height - 1, 0, -1):
        if any(pixels[x, y] != background for x in range(0, width, 3)):
            return image.crop((0, 0, width, min(height, y + 24)))
    return image


def wrap(text: str, typeface: ImageFont.FreeTypeFont, width: int) -> List[str]:
    lines: List[str] = []
    for word in text.split():
        candidate = (lines[-1] + " " + word) if lines else word
        if lines and typeface.getlength(candidate) <= width:
            lines[-1] = candidate
        else:
            lines.append(word)
    return lines


def caption(draw: ImageDraw.ImageDraw, text: str, box: Tuple[int, int, int, int], size: int, colour) -> None:
    left, top, right, bottom = box
    typeface = font("bold", size)
    lines = wrap(text, typeface, right - left)
    while len(lines) > 2 and size > 24:
        size -= 4
        typeface = font("bold", size)
        lines = wrap(text, typeface, right - left)
    if len(lines) > 2:
        raise ComposeError(f"caption too long for two lines: {text!r}")
    line_height = int(size * 1.22)
    y = top + (bottom - top - line_height * len(lines)) // 2
    for line in lines:
        x = left + (right - left - typeface.getlength(line)) / 2
        draw.text((x, y), line, font=typeface, fill=colour)
        y += line_height


def card(image: Image.Image, screen: Image.Image, box: Tuple[int, int, int, int], radius: int, outline, closed: bool = False) -> None:
    """Scales [screen] to the box width, crops it to the box height and pastes it with rounded top corners (all four
    when [closed]; otherwise it runs off the bottom edge)."""
    tail = 0 if closed else radius * 4
    left, top, right, bottom = box
    width, height = right - left, bottom - top
    scale = width / screen.size[0]
    source_height = min(screen.size[1], round(height / scale))
    part = screen.crop((0, 0, screen.size[0], source_height))
    if part.size[0] != width:  # 1:1 where the layout allows: sharper text and smaller files
        # Bicubic, not Lanczos: a little softer, but about 11 % smaller files, which keeps the scaled phone shots
        # (D-M9-20) inside the 3 MB budget (Lanczos made 3.08 MB).
        part = part.resize((width, round(source_height * scale)), Image.BICUBIC)
    fill = screen.getpixel((2, screen.size[1] - 2))
    panel = Image.new("RGB", (width, height), fill)
    panel.paste(part, (0, 0))
    mask = Image.new("L", (width * 2, height * 2), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width * 2 - 1, height * 2 - 1 + tail), radius * 2, fill=255)
    mask = mask.resize((width, height), Image.LANCZOS)
    extra = 4 if closed else 2
    border = Image.new("RGB", (width + 4, height + extra), outline)
    border_mask = Image.new("L", ((width + 4) * 2, (height + extra) * 2), 0)
    ImageDraw.Draw(border_mask).rounded_rectangle(
        (0, 0, (width + 4) * 2 - 1, (height + extra) * 2 - 1 + tail), (radius + 2) * 2, fill=255
    )
    image.paste(border, (left - 2, top - 2), border_mask.resize((width + 4, height + extra), Image.LANCZOS))
    image.paste(panel, (left, top), mask)


def phone_image(entry: dict, theme: str, colours) -> Image.Image:
    dark = theme == "dark"
    image = Image.new("RGB", PHONE, colours["surface_dark"] if dark else colours["brand_light"])
    draw = ImageDraw.Draw(image)
    caption(draw, entry["caption"], (70, 60, PHONE[0] - 70, 290), 64, colours["caption_dark"] if dark else colours["caption_light"])
    outline = (60, 66, 58) if dark else colours["brand"]
    if "tiers" in entry:
        tiers = entry["tiers"]
        gap, margin = 24, 48
        width = (PHONE[0] - 2 * margin - gap * (len(tiers) - 1)) // len(tiers)
        label = font("bold", 34)
        # Each tier's whole screen at the column width (D-M9-20), top-aligned under its label; when the screens are
        # shorter than the space under the labels, the cards close at the screen's foot.
        screens = [fit_screen(golden(tier["image"])) for tier in tiers]
        space = PHONE[1] - 400
        card_height = min(space, max(round(screen.size[1] * width / screen.size[0]) for screen in screens))
        closed = card_height < space
        for index, (tier, screen) in enumerate(zip(tiers, screens)):
            left = margin + index * (width + gap)
            text = tier["label"]
            draw.text((left + (width - label.getlength(text)) / 2, 330), text, font=label, fill=colours["caption_light"])
            card(image, screen, (left, 400, left + width, 400 + card_height), 28, outline, closed=closed)
        return image
    source = fit_screen(golden(entry["dark"] if dark else entry["light"]))
    height = PHONE[1] - 320
    # The whole screen, scaled down to the card's height (aspect kept, centred), so the bottom bar shows (D-M9-20).
    width = min(source.size[0], round(source.size[0] * height / source.size[1]))
    left = (PHONE[0] - width) // 2
    card(image, source, (left, 320, left + width, PHONE[1]), 44, outline)
    return image


def fit_screen(screen: Image.Image) -> Image.Image:
    """[screen] cut to one phone screenful (PHONE_SCREEN_ASPECT) from the top; shorter goldens are kept whole."""
    screenful = min(screen.size[1], round(screen.size[0] * PHONE_SCREEN_ASPECT))
    return screen if screenful == screen.size[1] else screen.crop((0, 0, screen.size[0], screenful))


def tablet_image(entry: dict, colours) -> Image.Image:
    image = Image.new("RGB", TABLET, colours["brand_light"])
    draw = ImageDraw.Draw(image)
    caption(draw, entry["caption"], (120, 24, TABLET[0] - 120, 150), 56, colours["caption_light"])
    screen = golden(entry["light"])  # 1280 x 800 dp at density 1: pasted 1:1, not scaled
    width, height = screen.size[0], min(screen.size[1], TABLET[1] - 190)
    left = (TABLET[0] - width) // 2
    card(image, screen, (left, 190, left + width, 190 + height), 28, colours["brand"], closed=True)
    return image


# --------------------------------------------------------------------------------------------------------------------
# Output and checks


def png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def check_image(name: str, data: bytes, expected: Tuple[int, int], problems: List[str]) -> None:
    image = Image.open(io.BytesIO(data))
    if image.size != expected:
        problems.append(f"{name}: {image.size}, expected {expected}")
    if image.mode != "RGB":
        problems.append(f"{name}: mode {image.mode}; Play wants 24-bit PNG without alpha")
    if len(data) > MAX_IMAGE_BYTES:
        problems.append(f"{name}: {len(data)} bytes, over 8 MB")
    longest, shortest = max(image.size), min(image.size)
    if name.endswith(("icon.png", "feature_graphic.png")):
        return  # fixed sizes, checked above
    if longest > 2 * shortest or shortest < 320 or longest > 3840:
        problems.append(f"{name}: {image.size} is outside Play's 320..3840 px and 2:1 limits")


def build(listing: dict) -> Dict[str, Tuple[bytes, Tuple[int, int]]]:
    colours = {key: hex_colour(value) for key, value in listing["colours"].items()}
    files: Dict[str, Tuple[bytes, Tuple[int, int]]] = {}
    files["images/icon.png"] = (png_bytes(icon()), ICON)
    files["images/feature_graphic.png"] = (png_bytes(feature_graphic(colours)), FEATURE)
    if not 2 <= len(listing["phone"]) <= 8 or not 1 <= len(listing["tablet"]) <= 8:
        raise ComposeError("Play takes 2 to 8 phone screenshots and up to 8 tablet screenshots")
    for entry in listing["phone"]:
        files[f"images/phone/light/{entry['name']}.png"] = (png_bytes(phone_image(entry, "light", colours)), PHONE)
        if "dark" in entry:
            files[f"images/phone/dark/{entry['name']}.png"] = (png_bytes(phone_image(entry, "dark", colours)), PHONE)
    for entry in listing["tablet"]:
        files[f"images/tablet/light/{entry['name']}.png"] = (png_bytes(tablet_image(entry, colours)), TABLET)
    return files


def texts(listing: dict) -> Dict[str, str]:
    out = {}
    for key, limit in TEXT_LIMITS.items():
        value = listing[key].strip()
        if len(value) > limit:
            raise ComposeError(f"{key} is {len(value)} characters; Play allows {limit}")
        out[f"{key}.txt"] = value + "\n"
    return out


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="compare with the committed files instead of writing")
    args = parser.parse_args(argv)
    try:
        listing = yaml.safe_load(LISTING.read_text(encoding="utf-8"))
        out = ROOT / "play" / "listing" / listing["locale"]
        text_files = texts(listing)
        images = build(listing)
    except (ComposeError, KeyError) as error:
        print(f"compose.py: {error}", file=sys.stderr)
        return 1
    problems: List[str] = []
    total = 0
    for name, (data, size) in sorted(images.items()):
        check_image(name, data, size, problems)
        total += len(data)
    if total > MAX_TOTAL_BYTES:
        problems.append(f"images total {total} bytes, over the 3 MB budget")
    if args.check:
        for name, data in list(text_files.items()) + [(n, d) for n, (d, _) in images.items()]:
            path = out / name
            current = path.read_bytes() if path.is_file() else None
            expected = data.encode("utf-8") if isinstance(data, str) else data
            if current != expected:
                problems.append(f"{path.relative_to(ROOT)} is out of date (python3 tools/store/compose.py)")
        image_dir = out / "images"
        for extra in sorted(image_dir.glob("**/*.png")) if image_dir.is_dir() else []:
            if str(extra.relative_to(out)) not in images:
                problems.append(f"{extra.relative_to(ROOT)} is not produced by listing.yaml (python3 tools/store/compose.py removes it)")
    else:
        image_dir = out / "images"
        for stale in sorted(image_dir.glob("**/*.png")) if image_dir.is_dir() else []:
            if str(stale.relative_to(out)) not in images:
                stale.unlink()
        for name, data in text_files.items():
            write(out / name, data.encode("utf-8"))
        for name, (data, _) in images.items():
            write(out / name, data)
    for name, (data, size) in sorted(images.items()):
        print(f"{len(data) / 1024:8.1f} KB  {size[0]}x{size[1]}  play/listing/{listing['locale']}/{name}")
    print(f"{total / 1024:8.1f} KB  total ({len(images)} images)")
    for name, data in text_files.items():
        print(f"{len(data.strip()):8d} ch  play/listing/{listing['locale']}/{name}")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    return 0


def write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


if __name__ == "__main__":
    sys.exit(main())
