#!/usr/bin/env python3
"""Fails when the committed privacy and security pages in server/public are not what docs/PRIVACY.md and
docs/SECURITY.md render to with docs/privacy.properties.example (M8 §1, the `check:privacy` step of the CI build job).
security.txt is checked by the server's `npm run check:static` (backend job).

It re-renders into a temporary directory with tools/privacy/render.py and compares byte for byte, then checks the
committed pages hold no script, no unfilled placeholder and no repository-only note, and that every in-page anchor
resolves. Python 3.9+, standard library only.

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

OUTPUTS = (
    Path("privacy/index.html"),
    Path("security/index.html"),
)


def main() -> int:
    problems = []
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


if __name__ == "__main__":
    sys.exit(main())
