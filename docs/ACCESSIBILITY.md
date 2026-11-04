# Accessibility

How Sprout keeps its screens usable with TalkBack, large text and small fingers, and how that is checked. Decisions: D-M7-5, D-M7-32 to D-M7-34 (`docs/DECISIONS-PENDING.md`).

## What is checked automatically

`AccessibilityRule` (`:core:testing`, `dev.kpetriv.sprout.core.testing.accessibility`) walks the semantics tree of every Compose root on screen (the screen plus any dialog or sheet window) and fails the test when:

1. an actionable node (clickable, long-clickable, toggleable, editable or adjustable) has no text, no content description and no labelled child, so TalkBack would say only "button";
2. two enabled actionable siblings have the same label, so a TalkBack user cannot tell "Approve" from "Approve";
3. an actionable node other than a text field is smaller than the minimum touch target in either direction: 72 dp Seedling, 56 dp Sprout, 48 dp Sapling and parent screens. The size is the node's unclipped size extended to the view configuration's minimum touch target, as Compose's hit testing does;
4. a `clearAndSetSemantics` hides an actionable node from accessibility services.

It runs in two places:

- **Every screenshot variant**: screenshot tests extend `ScreenshotTest(checkAccessibility = true)`, so each golden (every tier, light and dark, tablet, font scale 2.0, dialogs captured with `screen = true`) is also checked, against that variant's tier minimum. This covers every state in the matrix in `docs/SCREENSHOT_TESTS.md`.
- **Screen tests**: each Robolectric screen test adds the rule next to its Compose rule, so whatever the test leaves on screen (after clicks, with a sheet open) is checked at the end:

```kotlin
@get:Rule(order = 0) val compose = createComposeRule()
@get:Rule(order = 1) val a11y = AccessibilityRule(compose)
```

`a11y.forTier(tier)` raises the touch-target minimum for a kid screen; `a11y.check()` checks mid-test; `compose.assertAccessible(minTouchTarget)` and `assertAccessible(root)` are the same checks as functions.

## Checklist for a new or changed screen

- [ ] The screen title (top bar title or the screen's first headline) has `Modifier.semantics { heading() }`; section titles too.
- [ ] Every icon-only control has a content description that says what it does ("Delete digit", "Play voice note"), not what it looks like.
- [ ] Decorative glyphs and drawings are hidden (`clearAndSetSemantics { }` on a non-interactive node, or `hideFromAccessibility()`); never wrap an interactive node in `clearAndSetSemantics`.
- [ ] Composite widgets read as one sentence: a card or row merges its parts (`semantics(mergeDescendants = true)`) or carries one description; a graphic states its value ("7 coins", "45 of 120 minutes", "3 of 5 stars"), never a bare percentage.
- [ ] Repeated controls are distinguishable: group each card or row (`semantics { isTraversalGroup = true }`) or include the item in the label ("Archive Tidy room", "Letter R, 3 of 6").
- [ ] State is never colour alone: add text or an icon (shop tiles show "✓ You can buy this now" / "🐷 Keep saving"; chips carry their state as text; a selected row is announced as selected).
- [ ] Kid-mode UI sits inside `ProvideKidTier`, which raises both Material's minimum interactive size and the view configuration's minimum touch target to the tier's. A dialog or bottom sheet is a new window that resets the view configuration: keep its controls at the tier size and leave out Material's 48 dp sheet drag handle in kid sheets.
- [ ] Status that changes while the screen is open (errors, "Reading the receipt…", the recording countdown, the time bank's remaining time) is a polite live region, and announces at most every few seconds; nothing announces on every recomposition.
- [ ] Text scales: no fixed heights around text; check the font-scale 2.0 golden if the screen has one.
- [ ] Scrolling screens cap their content width (`ContentWidth` / `Modifier.contentWidth()`, 640 dp) so lines stay readable on a tablet.
- [ ] The screen's screenshot test has `checkAccessibility = true`, and its screen test (if any) has the `AccessibilityRule`.

## Live regions reviewed (M7)

Polite live regions are used for: load and save errors, empty states that appear after an action, the wallet's receipt-scan progress and "Preparing the scanner…", the weekly summary after the parent switches weeks (D-M6-48), chat's kindness-check spinner and nudge, Seedling's recording countdown (every 5 s), the time bank's remaining time (changes once a minute), the games hub's remaining credit, the memory round's sequence and progress, PIN errors and hints (lock, signing setup, onboarding), the chore celebration and "Suggest chores" loading, and the shop's dialog body. None is attached to a value that changes every frame or second.

## Large text

Font scale 2.0 goldens: the parent dashboard (`parent_home_busy_font200`), the kid wallet per tier (`wallet_ready_kid_font200_<tier>`) and the parent chat thread (`chat_parent_thread_font200`). Kid tiers already scale type (`TierTokens.typeScale`); the system font scale multiplies on top.

## Manual checks

TalkBack is a manual smoke test (D-M7-5), done in the M7 verification walk: the PIN pad (digits and "Delete digit" announced, dots read as "3 of 6 digits entered"), a game round, the kid wallet and a chat thread. If `adb shell settings put secure enabled_accessibility_services …` is not available on the emulator, the walk notes it.
