#!/usr/bin/env python3
"""Lays play/listing/<locale>/ out the way Gradle Play Publisher reads a store listing, so `:app:publishReleaseListing`
can upload it (D-REL-67). Standard library only: it copies and checks, it renders nothing.

    python3 tools/store/layout_play.py            # writes app/src/main/play/listings/<locale>/ (gitignored)
    python3 tools/store/layout_play.py --clean    # removes it again

Gradle Play Publisher's layout (version 4.x):
    listings/<locale>/title.txt, short-description.txt, full-description.txt
    listings/<locale>/graphics/icon/*.png, feature-graphic/*.png, phone-screenshots/*.png,
                      tablet-screenshots/*.png (7-inch), large-tablet-screenshots/*.png (10-inch)

The phone set is the light one, in file-name order; the dark set is not uploaded (play/listing/README.md). Both
tablet slots get the same images. Release notes are not part of a listing (they go with a release) and are left out.
"""
from __future__ import annotations

import argparse
import shutil
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LISTING = ROOT / "play" / "listing"
OUT = ROOT / "app" / "src" / "main" / "play" / "listings"
TEXTS = {"title.txt": "title.txt", "short_description.txt": "short-description.txt", "full_description.txt": "full-description.txt"}
IMAGES = {
    "images/icon.png": "graphics/icon",
    "images/feature_graphic.png": "graphics/feature-graphic",
    "images/phone/light": "graphics/phone-screenshots",
    "images/tablet/light": "graphics/tablet-screenshots",
    "images/tablet/light/": "graphics/large-tablet-screenshots",
}
EXPECTED = {"graphics/icon": (512, 512), "graphics/feature-graphic": (1024, 500)}


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        sys.exit(f"{path}: not a PNG")
    width, height = struct.unpack(">II", header[16:24])
    return width, height


def layout(locale_dir: Path) -> int:
    target = OUT / locale_dir.name
    if target.exists():
        shutil.rmtree(target)
    count = 0
    for source, name in TEXTS.items():
        text = (locale_dir / source).read_text(encoding="utf-8").rstrip("\n")
        target.mkdir(parents=True, exist_ok=True)
        (target / name).write_text(text + "\n", encoding="utf-8")
    for source, folder in IMAGES.items():
        src = locale_dir / source.rstrip("/")
        files = sorted(src.glob("*.png")) if src.is_dir() else [src]
        if not files or not all(f.is_file() for f in files):
            sys.exit(f"{src}: missing; run tools/store/compose.py first")
        if len(files) > 8:
            sys.exit(f"{src}: {len(files)} screenshots, Play takes at most 8")
        dest = target / folder
        dest.mkdir(parents=True, exist_ok=True)
        for file in files:
            size = png_size(file)
            if folder in EXPECTED and size != EXPECTED[folder]:
                sys.exit(f"{file}: {size}, expected {EXPECTED[folder]}")
            shutil.copyfile(file, dest / file.name)
            count += 1
    print(f"{locale_dir.name}: {count} images and {len(TEXTS)} texts in {target.relative_to(ROOT)}")
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--clean", action="store_true", help="remove the generated layout")
    args = parser.parse_args()
    if args.clean:
        shutil.rmtree(OUT.parent, ignore_errors=True)
        print(f"removed {OUT.parent.relative_to(ROOT)}")
        return
    locales = [d for d in LISTING.iterdir() if d.is_dir() and (d / "title.txt").is_file()]
    if not locales:
        sys.exit(f"no locale folder with a title.txt under {LISTING}")
    for locale_dir in sorted(locales):
        layout(locale_dir)


if __name__ == "__main__":
    main()
