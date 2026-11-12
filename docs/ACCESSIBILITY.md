# Accessibility

How Sprout keeps its screens usable with TalkBack, large text, small fingers and weak colour vision, and how that is checked. Decisions: D-M7-5, D-M7-32 to D-M7-34, and for colour D-M9-2, D-M9-3 and D-M9-G2 (`docs/DECISIONS-PENDING.md`).

## What is checked automatically

`AccessibilityRule` (`:core:testing`, `dev.kpetriv.sprout.core.testing.accessibility`) walks the semantics tree of every Compose root on screen (the screen plus any dialog or sheet window) and fails the test when:

1. an actionable node (clickable, long-clickable, toggleable, editable or adjustable) has no text, no content description and no labelled child, so TalkBack would say only "button";
2. two enabled actionable siblings have the same label, so a TalkBack user cannot tell "Approve" from "Approve";
3. an actionable node other than a text field is smaller than the minimum touch target in either direction: 72 dp Seedling, 56 dp Sprout, 48 dp Sapling and parent screens. The size is the node's unclipped size extended to the view configuration's minimum touch target, as Compose's hit testing does. Because that extension alone can never fail a placed node, two actionable nodes of one window whose extended areas overlap while their own bounds do not also fail (move them apart or make them larger; a node and its ancestor or descendant are exempt, D-M7-54);
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
- [ ] State is never colour alone: add text or an icon (shop tiles show a check-circle icon with "You can buy this now" or a piggy-bank icon with "Keep saving"; chips carry their state as text and a check icon; a selected row is announced as selected).
- [ ] Colours come from the theme roles and the design-system components, never from hex values in a screen; text on a tint uses that tint's `on…` role (see [Colour](#colour)).
- [ ] Kid-mode UI sits inside `ProvideKidTier`, which raises both Material's minimum interactive size and the view configuration's minimum touch target to the tier's. A dialog or bottom sheet is a new window that resets the view configuration: keep its controls at the tier size and leave out Material's 48 dp sheet drag handle in kid sheets.
- [ ] Status that changes while the screen is open (errors, "Reading the receipt…", the recording countdown, the time bank's remaining time) is a polite live region, and announces at most every few seconds; nothing announces on every recomposition.
- [ ] Text scales: no fixed heights around text; check the font-scale 2.0 golden if the screen has one.
- [ ] Scrolling screens cap their content width (640 dp) so lines stay readable on a tablet, and the side gutters scroll with the content: a lazy list fills the width and adds the `ContentWidth` gutters to its `contentPadding` (`PaddingValues(16.dp).withGutters(gutters)`); a scrolling column puts `Modifier.contentWidth()` after `verticalScroll` (D-V1-22).
- [ ] The screen's screenshot test has `checkAccessibility = true`, and its screen test (if any) has the `AccessibilityRule`.

## Live regions reviewed (M7)

Polite live regions are used for: load and save errors, empty states that appear after an action, the wallet's receipt-scan progress and "Preparing the scanner…", the weekly summary after the parent switches weeks (D-M6-48), chat's kindness-check spinner and nudge, Seedling's recording countdown (every 5 s), the time bank's remaining time (changes once a minute), the games hub's remaining credit, the memory round's sequence and progress, PIN errors and hints (lock, signing setup, onboarding), the chore celebration and "Suggest chores" loading, and the shop's dialog body. None is attached to a value that changes every frame or second.

## Colour

The Morning garden palette (M9 §2.1, `.stitch/DESIGN-morning-garden.md`) and the games' Playground palette (M9-games §1, `.stitch/DESIGN-playground.md`) follow these rules:

- **Text keeps 4.5:1 on every surface and tint**, in both themes: Ink on Cream, and each tint's `on…` role on the tint (Botanical deep on Leaf tint, Honey deep on Honey tint, Sky deep on Sky tint, on-error on the error container). `ColorSchemeContrastTest` (`core/designsystem`) checks those container pairs and `onSurface` on `surface` in the light and dark schemes, and that the tertiary containers are Sprout's, not Material's defaults.
- **Honey `#C4922A` is never text.** It measures 2.8:1 on white (2.6:1 on Cream; the spec's 3.2:1 was optimistic), under the 3:1 that large text and graphics need, so in the light theme it only decorates: the sun, the screen-time icon beside its minutes, the price dot beside a price in Ink. Every one of them sits next to text that carries the meaning. Honey figures and labels use Honey deep `#735C00` on Honey tint (5.9:1). The dark theme's Honey `#F0C56A` reads at 9.8:1 or better on the dark cards.
- **State is never colour alone.** A shop tile says "You can buy this now" or "Keep saving" with an icon; a selected chip has a check; chore and approval states are pills with words and an icon; a quarantined or excluded ledger entry says "Not counted" and why; in the games a used letter tile dims and loses its edge, a disabled tile dims to 40 %, the selected level and tables carry a check.
- **The colour dial removes colour, never meaning.** `TierTokens.colourLevel` (FULL for Seedling, ACCENTED for Sprout, RESTRAINED for Sapling and parents, D-M9-3) decides how much tint a component shows; whatever a tint signals on Seedling is also said in text or an icon, so a Sapling or parent screen with no tint carries the same information. The quiet Playground of Sapling games (D-M9-G2) works the same way: Marigold and Coral fall back to Lime and Sky, and the memory symbols keep their own colours because the colour is the content (D-M9-G16) and each symbol also has a spoken name ("red circle").
- **No pure black and no gradients.** Dynamic colour stays off, so the checked pairs are the pairs on screen.

Playground pairs (the field is the same in the light and dark theme). `PlaygroundTokensTest.textPairsOfTheDesignFileMeetWcagAa` fails the build if any of them drops below 4.5:1; the ratios below use the WCAG formula the test uses (the design file's rounded figures differ by up to 0.9 and all pass):

| Text | On | Ratio | Where |
|---|---|---|---|
| Ink `#1A1C19` | Lime `#B8E986` | 12.3:1 | reward card, selected level, done tiles |
| Ink | Marigold `#F5B52A` | 9.4:1 | answer tiles, selected tables, the primary action |
| Ink | Sky `#7CC4EE` | 9.0:1 | number pad, read-aloud tile, placed answers |
| Ink | Coral `#FF8A65` | 7.4:1 | backspace tile |
| Chalk `#FFFFFF` | Field `#1E4B33` | 10.0:1 | headings, prompts, outline tiles |
| Chalk | Field panel `#27583F` | 8.2:1 | panels, answer box, Sapling pad tiles |
| Chalk | Field deep `#133322` | 13.8:1 | countdown ring, index badges |
| Chalk soft (60 %) | Field | 4.7:1 | captions on the field only |
| Caption (Chalk 85 % over the panel, opaque) | Field panel | 6.5:1 | captions on panels, where Chalk soft would be 4.1:1 |
| Caption | Field | 7.8:1 | captions on the field |
| Lime | Field | 7.1:1 | "Perfect!", progress label |
| Lime | Field panel | 5.9:1 | streak pill, the quiet backspace |
| Sky | Field | 5.2:1 | countdown seconds |

## Large text

Font scale 2.0 goldens: the parent dashboard (`parent_home_busy_font200`), the kid wallet per tier (`wallet_ready_kid_font200_<tier>`) and the parent chat thread (`chat_parent_thread_font200`). Since M9 the shared components have their own: the bottom navigation bar (`component_navigation_bar_font200_<tier>`, `component_navigation_bar_parent_font200`), the top bar (`component_top_bar_font200_<tier>`, `component_top_bar_parent_font200`), the `GoalBar` (`component_goal_bar_font200_<tier>`; it stacks its "31% saved" chip under the amounts at large scales) and the avatar (`component_avatar_font200_<tier>`) in `core/designsystem`, and the games' sequence panel (`playground_sequence_panel_font200_<tier>`). Kid tiers already scale type (`TierTokens.typeScale`); the system font scale multiplies on top. Body text is never below 14 sp: `bodySmall` is 14 sp on Sapling and parent screens, 16.1 sp on Sprout and 18.2 sp on Seedling (D-M9-14, `SproutTypographyTest`).

## Manual checks

TalkBack is a manual smoke test (D-M7-5), done in the M7 verification walk: the PIN pad (digits and "Delete digit" announced, dots read as "3 of 6 digits entered"), a game round, the kid wallet and a chat thread. If `adb shell settings put secure enabled_accessibility_services …` is not available on the emulator, the walk notes it.

M7 walk result (`docs/milestones/M7.md` "As built", step 9): TalkBack enabled on the API 33 emulator through `settings put secure enabled_accessibility_services`; the PIN screen's tree exposed the heading, "0 of 6 digits entered", every digit and "Delete digit", and the games hub "Play Numbers", "Play Memory" and "Play Word scramble". Spoken output was not observable, because adb's injected taps bypass TalkBack's touch exploration; a hands-on pass with TalkBack on a device is left for M8. The home screen widget's content is plain text; its blank state carries the description "Sprout".
