#!/usr/bin/env python3
"""Fails when the committed privacy and security pages in server/public are not what docs/PRIVACY.md and
docs/SECURITY.md render to with docs/privacy.properties.example (M8 §1, the `check:privacy` step of the CI build job).
security.txt is checked by the server's `npm run check:static` (backend job).

It first runs the renderer's unit tests (tools/privacy/test_render.py), re-renders into a temporary directory with
tools/privacy/render.py and compares byte for byte, then checks the committed pages hold no script, no unfilled
placeholder, no repository-only note and no unconverted Markdown link, and that every anchor resolves: in-page ones
and links from one page to the other's sections. Python 3.9+, standard library only.

Usage: python3 tools/privacy/check.py      (exit 0 when up to date; prints a diff and the fix otherwise)
"""
from __future__ import annotations

import difflib
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render  # noqa: E402  (tools/privacy/render.py)
import test_render  # noqa: E402  (tools/privacy/test_render.py)

OUTPUTS = (
    Path("privacy/index.html"),
    Path("security/index.html"),
)


def main() -> int:
    if not test_render.run():
        print("check.py: the renderer's unit tests fail (python3 tools/privacy/test_render.py)", file=sys.stderr)
        return 1
    problems = []
    pages = {}
    with tempfile.TemporaryDirectory() as scratch:
        fresh_root = Path(scratch)
        try:
            render.render(render.EXAMPLE_PROPERTIES, fresh_root)
        except render.RenderError as error:
            print(f"check.py: the pages do not render: {error}", file=sys.stderr)
            return 1
        for relative in OUTPUTS:
            committed = render.DEFAULT_OUT / relative
            fresh = (fresh_root / relative).read_text(encoding="utf-8")
            if not committed.is_file():
                problems.append(f"server/public/{relative} is missing")
                continue
            current = committed.read_text(encoding="utf-8")
            pages[relative] = current
            if current != fresh:
                diff = difflib.unified_diff(
                    current.splitlines(keepends=True),
                    fresh.splitlines(keepends=True),
                    fromfile=f"server/public/{relative} (committed)",
                    tofile=f"server/public/{relative} (rendered)",
                    n=1,
                )
                problems.append(f"server/public/{relative} is out of date:\n" + "".join(list(diff)[:80]))
            problems += lint(relative, current)
    problems += cross_page_anchors(pages)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        print(
            "\nFix: python3 tools/privacy/render.py   (renders the example values; never commit real ones)",
            file=sys.stderr,
        )
        return 1
    print("privacy pages are up to date: " + ", ".join(f"server/public/{p}" for p in OUTPUTS))
    return 0


def lint(relative: Path, text: str) -> list:
    found = []
    name = f"server/public/{relative}"
    if "<script" in text.lower() or "javascript:" in text.lower():
        found.append(f"{name}: contains a script")
    if re.search(r"\{\{[A-Z0-9_]+\}\}", text):
        found.append(f"{name}: an unfilled placeholder")
    if "](" in text:
        found.append(f"{name}: a Markdown link was left unconverted (a target with parentheses or an unsupported scheme)")
    if "repo-only" in text or "Owner (legal)" in text:
        found.append(f"{name}: a repository-only note leaked into the page")
    if "example.invalid" not in text:
        found.append(f"{name}: the committed page must be rendered from the example values")
    if relative.suffix == ".html":
        ids = set(re.findall(r'\sid="([^"]+)"', text))
        for anchor in re.findall(r'href="#([^"]+)"', text):
            if anchor not in ids:
                found.append(f"{name}: link to #{anchor}, which does not exist")
    return found


def cross_page_anchors(pages: dict) -> list:
    """Links from one page to a section of the other (`<PRIVACY_URL>#x`, `<SECURITY_URL>#x`) must name an existing id."""
    values = render.derive(render.load_properties(render.EXAMPLE_PROPERTIES), strict=False)
    urls = {Path("privacy/index.html"): values["PRIVACY_URL"], Path("security/index.html"): values["SECURITY_URL"]}
    ids = {relative: set(re.findall(r'\sid="([^"]+)"', text)) for relative, text in pages.items()}
    found = []
    for relative, text in pages.items():
        for target, url in urls.items():
            if target not in ids:
                continue
            for anchor in re.findall(r'href="' + re.escape(url) + r'#([^"]+)"', text):
                if anchor not in ids[target]:
                    found.append(f"server/public/{relative}: link to {url}#{anchor}, which does not exist")
    return found


if __name__ == "__main__":
    sys.exit(main())
