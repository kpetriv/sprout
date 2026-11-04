#!/usr/bin/env python3
"""Unit tests of tools/privacy/render.py's Markdown subset (review M3 and L1). tools/privacy/check.py runs them first.

Each case runs under a 5-second alarm where the platform has one, so a renderer that stops advancing fails the test
instead of hanging CI. Python 3.9+, standard library only.

Usage: python3 tools/privacy/test_render.py
"""
from __future__ import annotations

import signal
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render  # noqa: E402  (tools/privacy/render.py)


def body(markdown: str) -> str:
    return render.markdown_to_html("# Title\n\n" + markdown, {})[1]


class Timeout(Exception):
    pass


class RenderTest(unittest.TestCase):
    def setUp(self) -> None:
        if hasattr(signal, "SIGALRM"):
            def expire(_signum, _frame):
                raise Timeout("the renderer did not finish within 5 s")

            signal.signal(signal.SIGALRM, expire)
            signal.alarm(5)

    def tearDown(self) -> None:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)

    def test_lines_starting_with_a_hash_that_are_not_headings_are_text(self) -> None:
        self.assertIn("<p>#hashtag in a line</p>", body("#hashtag in a line\n"))
        self.assertIn("<p>#</p>", body("#\n"))
        self.assertIn("<p>####### seven</p>", body("####### seven\n"))
        # In the middle of a paragraph too.
        self.assertIn("<p>first #second</p>", body("first\n#second\n"))

    def test_headings_still_end_a_paragraph(self) -> None:
        html = body("a paragraph\n## Next\n")
        self.assertIn("<p>a paragraph</p>", html)
        self.assertIn('<h2 id="next">Next</h2>', html)

    def test_nested_list_items_are_refused(self) -> None:
        with self.assertRaisesRegex(render.RenderError, "nested"):
            body("- a\n  - b\n")
        with self.assertRaisesRegex(render.RenderError, "nested"):
            body("- a\n - b\n")
        with self.assertRaisesRegex(render.RenderError, "nested"):
            body("a paragraph\n  - b\n")

    def test_ordered_lists_keep_their_numbers(self) -> None:
        self.assertIn("<ol>\n<li>a</li>\n<li>b</li>\n</ol>", body("1. a\n2. b\n"))
        # A loose list continues its numbering.
        html = body("1. a\n\n2. b\n")
        self.assertIn('<ol start="2">\n<li>b</li>\n</ol>', html)
        self.assertIn('<ol start="3">', body("3. c\n4. d\n"))
        with self.assertRaisesRegex(render.RenderError, "out of sequence"):
            body("1. a\n3. b\n")

    def test_continuation_lines_join_the_item(self) -> None:
        self.assertIn("<li>a long item</li>", body("- a long\n  item\n"))


def run() -> bool:
    """Runs the tests quietly; True when they all pass (check.py)."""
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RenderTest)
    result = unittest.TextTestRunner(stream=sys.stderr, verbosity=0).run(suite)
    return result.wasSuccessful()


if __name__ == "__main__":
    unittest.main()
