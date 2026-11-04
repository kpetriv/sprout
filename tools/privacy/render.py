#!/usr/bin/env python3
"""Renders the published privacy and security pages from the repository's Markdown (M8 §1, D-M8-1).

    docs/PRIVACY.md   -> <out>/privacy/index.html
    docs/SECURITY.md  -> <out>/security/index.html

security.txt (RFC 9116) is generated from the same properties file by the server's `npm run build:static`
(server/scripts/build-static.ts, D-M8-13), so there is one generator per file (D-M8-31).

<out> defaults to server/public, which the Sprout server serves statically (the serving and the response headers are
the server's; this script only writes the files). Placeholders such as {{CONTACT_EMAIL}} are filled from a properties
file: docs/privacy.properties.example by default (the committed pages always come from it, so they show
example.invalid addresses), or the owner's gitignored docs/privacy.properties with --properties. Blocks between
<!-- repo-only --> and <!-- /repo-only --> are notes for the repository and are left out of the pages.

Python 3.9+, standard library only (runs in CI without installing anything). Deterministic: the same inputs give
byte-identical outputs, which tools/privacy/check.py relies on.

The Markdown supported is the subset the two documents use: ATX headings, paragraphs, "-" and "1." lists, pipe tables,
block quotes, fenced code, and inline code, links, **strong** and *emphasis*. Anything else is rendered as text.

Usage:
    python3 tools/privacy/render.py                       # example values -> server/public (what is committed)
    python3 tools/privacy/render.py --properties docs/privacy.properties --strict
    python3 tools/privacy/render.py --out /tmp/pages      # somewhere else
"""
from __future__ import annotations

import argparse
import datetime as dt
import base64
import hashlib
import html
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import urljoin

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
EXAMPLE_PROPERTIES = DOCS / "privacy.properties.example"
DEFAULT_OUT = ROOT / "server" / "public"

REQUIRED_KEYS = (
    "CONTACT_EMAIL",
    "DEVELOPER_NAME",
    "POSTAL_ADDRESS",
    "EFFECTIVE_DATE",
    "SECURITY_CONTACT",
    "PRIVACY_URL",
)
# Values a real publication must not contain (--strict).
EXAMPLE_MARKERS = ("example.invalid", "(to be filled)")

PLACEHOLDER = re.compile(r"\{\{([A-Z0-9_]+)\}\}")
# Private-use characters stand in for placeholder values while the Markdown is parsed, so a value can never be read as
# Markdown or HTML; they are replaced by the escaped value at the end.
TOKEN_OPEN, TOKEN_CLOSE = chr(0xE000), chr(0xE001)
STASH_OPEN, STASH_CLOSE = chr(0xE002), chr(0xE003)
STASHED = re.compile(STASH_OPEN + r"(\d+)" + STASH_CLOSE)
TOKEN = re.compile(TOKEN_OPEN + r"([A-Z0-9_]+)" + TOKEN_CLOSE)
REPO_ONLY = re.compile(r"^<!-- repo-only -->\n.*?^<!-- /repo-only -->\n?", re.M | re.S)
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)

MONTHS = (
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)


class RenderError(Exception):
    pass


# --------------------------------------------------------------------------------------------------------------------
# Properties


def load_properties(path: Path) -> Dict[str, str]:
    if not path.is_file():
        raise RenderError(f"{path} does not exist (copy docs/privacy.properties.example and fill it in)")
    values: Dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RenderError(f"{path}:{number}: expected KEY=value")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    missing = [key for key in REQUIRED_KEYS if not values.get(key)]
    if missing:
        raise RenderError(f"{path}: missing values for {', '.join(missing)}")
    return values


def derive(values: Dict[str, str], strict: bool) -> Dict[str, str]:
    out = dict(values)
    try:
        effective = dt.date.fromisoformat(values["EFFECTIVE_DATE"])
    except ValueError as error:
        raise RenderError(f"EFFECTIVE_DATE must be YYYY-MM-DD: {error}") from None
    privacy_url = values["PRIVACY_URL"].rstrip("/")
    if not privacy_url.startswith("https://"):
        raise RenderError("PRIVACY_URL must be an https:// URL")
    if "@" not in values["CONTACT_EMAIL"] or ":" in values["CONTACT_EMAIL"]:
        raise RenderError("CONTACT_EMAIL must be a plain e-mail address")
    contact = values["SECURITY_CONTACT"]
    if contact.startswith(("mailto:", "https://")):
        contact_uri = contact
        contact_text = contact[len("mailto:"):] if contact.startswith("mailto:") else contact
    elif "@" in contact and ":" not in contact:
        contact_uri, contact_text = "mailto:" + contact, contact
    else:
        raise RenderError("SECURITY_CONTACT must be an e-mail address, a mailto: URI or an https:// URL")
    if strict:
        bad = sorted(key for key, value in values.items() if any(m in value for m in EXAMPLE_MARKERS))
        if bad:
            raise RenderError(f"--strict: example values left in {', '.join(bad)}")
    out["PRIVACY_URL"] = privacy_url
    out["SECURITY_CONTACT"] = contact_text
    out["SECURITY_CONTACT_URI"] = contact_uri
    out["SECURITY_URL"] = urljoin(privacy_url + "/", "../security")
    out["SECURITY_TXT_URL"] = urljoin(privacy_url, "/.well-known/security.txt")
    out["EFFECTIVE_DATE_LONG"] = f"{effective.day} {MONTHS[effective.month - 1]} {effective.year}"
    return out


# --------------------------------------------------------------------------------------------------------------------
# Markdown


def slug(text: str) -> str:
    """GitHub's heading anchors, so links like PRIVACY.md#on-device-ai work on GitHub and on the page alike."""
    plain = re.sub(r"<[^>]+>", "", text)
    plain = html.unescape(plain).lower().strip()
    plain = re.sub(r"[^\w\- ]", "", plain)
    return plain.replace(" ", "-")


def escape_text(text: str) -> str:
    """Escapes <, > and quotes, and & unless it already starts an entity (the Markdown uses &lt; for GitHub)."""
    text = re.sub(r"&(?![A-Za-z]+;|#[0-9]+;|#x[0-9A-Fa-f]+;)", "&amp;", text)
    return text.replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


class Inline:
    def __init__(self, link_map: Dict[str, Optional[str]]):
        self.link_map = link_map

    def href(self, target: str) -> Optional[str]:
        """Where a link goes on the published page; None drops the link and keeps its text."""
        if target.startswith(("https://", "http://", "mailto:", "#", TOKEN_OPEN)):
            return target
        path, _, anchor = target.partition("#")
        if path in self.link_map:
            base = self.link_map[path]
            if base is None:
                return None
            return base + ("#" + anchor if anchor else "")
        return None  # another repository file: not published

    def render(self, text: str) -> str:
        stash: List[str] = []
        text = self._render(text, stash)
        while STASH_OPEN in text:
            text = STASHED.sub(lambda m: stash[int(m.group(1))], text)
        return text

    def _render(self, text: str, stash: List[str]) -> str:
        """Renders inline Markdown, leaving finished fragments (code, links) as stash markers."""

        def keep(fragment: str) -> str:
            stash.append(fragment)
            return f"{STASH_OPEN}{len(stash) - 1}{STASH_CLOSE}"

        text = re.sub(r"`([^`]+)`", lambda m: keep("<code>" + escape_text(m.group(1)) + "</code>"), text)

        def link(match: re.Match) -> str:
            label, target = match.group(1), match.group(2).strip()
            inner = self._render(label, stash)
            href = self.href(target)
            if href is None:
                return keep(inner)
            return keep(f'<a href="{escape_text(href)}">{inner}</a>')

        text = re.sub(r"\[([^\[\]]+)\]\(([^()\s]+)\)", link, text)
        text = escape_text(text)
        text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
        return re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", text)


def split_row(line: str) -> List[str]:
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|"):
        line = line[:-1]
    cells, current, code = [], [], False
    for char in line:
        if char == "`":
            code = not code
        if char == "|" and not code:
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    cells.append("".join(current).strip())
    return cells


TABLE_RULE = re.compile(r"^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$")
LIST_ITEM = re.compile(r"^(?:(-|\*)|(\d+)\.)\s+(.*)$")


def render_blocks(lines: List[str], inline: Inline, headings: List[Tuple[int, str]]) -> List[str]:
    out: List[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            i += 1
            continue
        if stripped.startswith("```"):
            body = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                body.append(lines[i])
                i += 1
            i += 1
            out.append("<pre><code>" + escape_text("\n".join(body)) + "</code></pre>")
            continue
        heading = re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", stripped)
        if heading:
            level = len(heading.group(1))
            content = inline.render(heading.group(2))
            anchor = slug(content)
            headings.append((level, anchor))
            out.append(f'<h{level} id="{anchor}">{content}</h{level}>')
            i += 1
            continue
        if stripped.startswith(">"):
            quoted = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                quoted.append(re.sub(r"^\s*>\s?", "", lines[i]))
                i += 1
            out.append("<blockquote>\n" + "\n".join(render_blocks(quoted, inline, headings)) + "\n</blockquote>")
            continue
        if stripped.startswith("|") and i + 1 < len(lines) and TABLE_RULE.match(lines[i + 1].strip()):
            header = split_row(stripped)
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            table = ['<div class="table"><table>', "<thead><tr>"]
            table += [f'<th scope="col">{inline.render(cell)}</th>' for cell in header]
            table.append("</tr></thead><tbody>")
            for row in rows:
                row = (row + [""] * len(header))[: len(header)]
                table.append("<tr>" + "".join(f"<td>{inline.render(cell)}</td>" for cell in row) + "</tr>")
            table.append("</tbody></table></div>")
            out.append("\n".join(table))
            continue
        item = LIST_ITEM.match(stripped)
        if item:
            ordered = item.group(2) is not None
            tag = "ol" if ordered else "ul"
            items: List[str] = []
            while i < len(lines):
                current = LIST_ITEM.match(lines[i].strip()) if lines[i].strip() else None
                if current is None or (current.group(2) is not None) != ordered or lines[i].startswith(" "):
                    break
                text = [current.group(3)]
                i += 1
                while i < len(lines) and lines[i].strip() and lines[i].startswith("  "):
                    text.append(lines[i].strip())
                    i += 1
                items.append("<li>" + inline.render(" ".join(text)) + "</li>")
            out.append(f"<{tag}>\n" + "\n".join(items) + f"\n</{tag}>")
            continue
        paragraph = []
        while i < len(lines):
            current = lines[i].strip()
            if (
                not current
                or current.startswith(("#", ">", "```"))
                or LIST_ITEM.match(current)
                or (current.startswith("|") and i + 1 < len(lines) and TABLE_RULE.match(lines[i + 1].strip()))
            ):
                break
            paragraph.append(current)
            i += 1
        out.append("<p>" + inline.render(" ".join(paragraph)) + "</p>")
    return out


def markdown_to_html(markdown: str, link_map: Dict[str, Optional[str]]) -> Tuple[str, str]:
    """Returns (title, body HTML). The title is the first level-1 heading."""
    markdown = REPO_ONLY.sub("", markdown)
    if "repo-only" in markdown:
        raise RenderError("an unbalanced <!-- repo-only --> block")
    markdown = HTML_COMMENT.sub("", markdown)
    headings: List[Tuple[int, str]] = []
    blocks = render_blocks(markdown.splitlines(), Inline(link_map), headings)
    titles = [b for b in blocks if b.startswith("<h1")]
    if len(titles) != 1:
        raise RenderError("a page needs exactly one level-1 heading")
    anchors = [anchor for _, anchor in headings]
    duplicates = sorted({a for a in anchors if anchors.count(a) > 1})
    if duplicates:
        raise RenderError(f"duplicate heading anchors: {', '.join(duplicates)}")
    title = re.sub(r"<[^>]+>", "", titles[0])
    return title, "\n".join(blocks)


# --------------------------------------------------------------------------------------------------------------------
# Pages

STYLE = """
:root{color-scheme:light dark;--bg:#fbfdf7;--fg:#1a1c19;--muted:#43483f;--line:#c3c8bb;--accent:#2e7d32;--quote:#eef3e6}
@media (prefers-color-scheme:dark){:root{--bg:#121411;--fg:#e2e3dc;--muted:#c3c8bb;--line:#43483f;--accent:#9ad795;--quote:#1e211c}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:1rem/1.6 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif}
main,footer{max-width:46rem;margin:0 auto;padding:0 1rem}
main{padding-top:2rem}
h1{font-size:1.9rem;line-height:1.25;margin:0 0 .25rem}
h2{font-size:1.35rem;margin:2.25rem 0 .5rem;padding-top:.5rem;border-top:1px solid var(--line)}
h3{font-size:1.1rem;margin:1.5rem 0 .5rem}
a{color:var(--accent)}
a:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.9em;overflow-wrap:anywhere}
.updated{color:var(--muted);margin:0 0 1.5rem}
.table{overflow-x:auto;margin:1rem 0}
table{border-collapse:collapse;width:100%;font-size:.95rem}
th,td{text-align:left;vertical-align:top;padding:.5rem .6rem;border-bottom:1px solid var(--line)}
th{font-weight:600}
blockquote{margin:1rem 0;padding:.5rem 1rem;background:var(--quote);border-left:4px solid var(--accent)}
footer{margin-top:3rem;padding-top:1rem;padding-bottom:2rem;border-top:1px solid var(--line);color:var(--muted);font-size:.9rem}
footer ul{list-style:none;padding:0;margin:0;display:flex;flex-wrap:wrap;gap:.5rem 1.5rem}
""".strip()


STYLE_ELEMENT_TEXT = "\n" + STYLE + "\n"


def csp() -> str:
    """Allows exactly the inline style below (by hash) and nothing else: no scripts, images, frames or forms."""
    digest = base64.b64encode(hashlib.sha256(STYLE_ELEMENT_TEXT.encode("utf-8")).digest()).decode("ascii")
    return (
        "default-src 'none'; style-src 'sha256-" + digest + "'; img-src 'none'; base-uri 'none'; form-action 'none'"
    )


def page(title: str, body: str, canonical: str) -> str:
    # The "Last updated" line goes right under the title.
    body = re.sub(
        r"(</h1>)",
        r'\1\n<p class="updated">Last updated: <time datetime="' + TOKEN_OPEN + "EFFECTIVE_DATE" + TOKEN_CLOSE + '">'
        + TOKEN_OPEN + "EFFECTIVE_DATE_LONG" + TOKEN_CLOSE + "</time></p>",
        body,
        count=1,
    )
    t = lambda key: TOKEN_OPEN + key + TOKEN_CLOSE  # noqa: E731
    return "\n".join(
        [
            "<!doctype html>",
            '<html lang="en-GB">',
            "<head>",
            '<meta charset="utf-8">',
            '<meta name="viewport" content="width=device-width, initial-scale=1">',
            '<meta name="color-scheme" content="light dark">',
            '<meta name="referrer" content="no-referrer">',
            f'<meta http-equiv="Content-Security-Policy" content="{csp()}">',
            f"<title>{title}</title>",
            f'<link rel="canonical" href="{canonical}">',
            f"<style>{STYLE_ELEMENT_TEXT}</style>",
            "</head>",
            "<body>",
            "<main>",
            "<article>",
            body,
            "</article>",
            "</main>",
            '<footer>',
            '<nav aria-label="Sprout policies"><ul>',
            f'<li><a href="{t("PRIVACY_URL")}">Privacy policy</a></li>',
            f'<li><a href="{t("SECURITY_URL")}">Security and disclosure</a></li>',
            f'<li><a href="{t("SECURITY_TXT_URL")}">security.txt</a></li>',
            "</ul></nav>",
            "</footer>",
            "</body>",
            "</html>",
            "",
        ]
    )


def tokenise(markdown: str) -> str:
    return PLACEHOLDER.sub(lambda m: TOKEN_OPEN + m.group(1) + TOKEN_CLOSE, markdown)


def fill(text: str, values: Dict[str, str]) -> str:
    def value(match: re.Match) -> str:
        key = match.group(1)
        if key not in values:
            raise RenderError(f"unknown placeholder {{{{{key}}}}}")
        return html.escape(values[key], quote=True)

    filled = TOKEN.sub(value, text)
    if TOKEN_OPEN in filled or TOKEN_CLOSE in filled:
        raise RenderError("a placeholder was split by Markdown formatting")
    return filled


def render(properties: Path, out: Path, strict: bool = False) -> List[Path]:
    values = derive(load_properties(properties), strict)
    link_map: Dict[str, Optional[str]] = {
        "PRIVACY.md": TOKEN_OPEN + "PRIVACY_URL" + TOKEN_CLOSE,
        "SECURITY.md": TOKEN_OPEN + "SECURITY_URL" + TOKEN_CLOSE,
    }
    written: List[Path] = []
    for source, target, canonical in (
        ("PRIVACY.md", out / "privacy" / "index.html", "PRIVACY_URL"),
        ("SECURITY.md", out / "security" / "index.html", "SECURITY_URL"),
    ):
        title, body = markdown_to_html(tokenise((DOCS / source).read_text(encoding="utf-8")), link_map)
        document = fill(page(title, body, TOKEN_OPEN + canonical + TOKEN_CLOSE), values)
        if "<script" in document.lower() or "{{" in document:
            raise RenderError(f"{target}: a script or an unfilled placeholder in the output")
        written.append(write(target, document))
    return written


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return path


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--properties", type=Path, help="properties file (default: docs/privacy.properties.example)")
    source.add_argument("--example", action="store_true", help="use docs/privacy.properties.example (the default)")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="output directory (default: server/public)")
    parser.add_argument("--strict", action="store_true", help="refuse example.invalid and '(to be filled)' values")
    args = parser.parse_args(argv)
    properties = args.properties or EXAMPLE_PROPERTIES
    try:
        written = render(properties, args.out, strict=args.strict)
    except RenderError as error:
        print(f"render.py: {error}", file=sys.stderr)
        return 1
    for path in written:
        try:
            shown = path.resolve().relative_to(ROOT)
        except ValueError:
            shown = path
        print(f"wrote {shown}")
    if properties.resolve() != EXAMPLE_PROPERTIES.resolve() and args.out.resolve() == DEFAULT_OUT.resolve():
        print(
            "note: server/public now holds the real values. Deploy, then restore the committed example pages with "
            "`python3 tools/privacy/render.py` (docs/RELEASE_CHECKLIST.md item 4).",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
