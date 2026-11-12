# M9 addendum: the games UI ("Playground") and the Times tables game

Addendum to [M9: Visual redesign](M9.md). M9 restyles every screen with the Morning garden tokens; this addendum covers what M9 §4 left out: the in-game screens of the brain games (start card, rounds, result), which M9 §4 and D-M9-9 handed to a separate brainstorm. It also adds the one feature M9 carries, a fourth game, **Times tables**, which Kiril asked for on 2026-11-11 while the games were being rendered ("similar to Times Table Rockstars, the multiplication table at different complexity levels you can set up in the game").

The brainstorm ran in the same Stitch project as M9 (`10251972321848422943`). Two characters were rendered for the Sprout tier and shown side by side: **A** inherited Morning garden through its design system (`assets/87c1dbeea6a3488c891bc866774156aa`); **B** was a game character of its own described in the prompt. Kiril chose **B, "Playground"** on 2026-11-11 (D-M9-G1): "Playground looks very nice for the smaller kids, while the top one would work well for older kids. In general the bottom one looks a lot cooler." Both sets stay in `.stitch/designs/games-*.png` and `.stitch/metadata-games.json`; the sheet he chose from is `games-sheet-AB.png`, the tier sheet `games-sheet-B-tiers.png`.

## 1. The chosen character and how it relates to Morning garden

Playground is Morning garden turned inside out, not a second brand:

- The bottom navigation bar M9 adds (§5, D-M9-6 revised, D-M9-10) is hidden inside a running game, as that section already says, and the tablet rail with it: from the start card to the result the field owns the whole window; the bar returns on the games hub.
- The play field is Morning garden's pressed Botanical (`#1E4B33`, the `primary` pressed state of M9 §2.1), full bleed from the start card to the result. The games hub stays a Morning garden screen (M9 §4, "games"); the field begins when a game is opened and ends on "Done".
- The typefaces are the same (Outfit for every number and heading, Plus Jakarta Sans for the rest, bundled per D-M9-4), as is Ink `#1A1C19` on coloured tiles.
- Four flat accents replace the three soft tints: Lime `#B8E986`, Marigold `#F5B52A`, Sky `#7CC4EE`, Coral `#FF8A65`, each with a darker edge colour. Tiles are "chunky": a solid 4 dp bottom edge in the edge colour instead of a shadow, pressed by translating down. Still no gradients, blurred shadows, emoji or illustration (M9 §2.7 and D-M9-5 hold).
- Kiril's remark about older kids becomes the colour dial inside Playground (D-M9-G2): Seedling and Sprout get the full four-accent field; Sapling gets a **quiet Playground** (Lime and Sky only, Marigold for the single primary action, smaller tiles, denser, typed answers, a score history) rather than Morning garden. A Sapling game is still unmistakably a game, just a calmer one.

The values were read from the chosen renders' HTML and written to `.stitch/DESIGN-playground.md`, which is the token source for the implementer. Stitch rendered the Playground screens without a design system (every prompt restated the character; `.stitch/prompts-games.md`); a Stitch design system can be created from `DESIGN-playground.md` with `create_design_system_from_design_md` if more game screens are ever rendered.

## 2. Tokens and components

Playground is a games-only theme, not an extension of the app's `ColorScheme` (D-M9-G3): no other screen needs a dark green field, and the Material roles would have to be bent (a dark `surface` next to the app's light one) to express it. So:

### 2.1 `core/designsystem`

- `theme/Playground.kt`: a `PlaygroundColors` `@Immutable` data class with the values of `DESIGN-playground.md` (`field`, `fieldDeep`, `fieldPanel`, `chalk`, `chalkSoft`, `ink`, `lime`, `limeEdge`, `marigold`, `marigoldEdge`, `sky`, `skyEdge`, `coral`, `coralEdge`) and one instance, `PlaygroundColors.Default`. It lives here, not in `feature/games`, because `core/ui` `Confetti` takes its colours from it on the result screen and the hub's game tiles use Lime, Marigold and Sky for their pictograms.
- `PlaygroundTokens(tileCorner: Dp, tileEdge: Dp, tileHeight: Dp, segmentHeight: Dp, accents: AccentLevel)` with `Seedling` (24 / 4 / 112 / 14 dp, `FULL`), `Sprout` (20 / 4 / 72 / 14 dp, `FULL`) and `Sapling` (16 / 3 / 56 / 8 dp, `QUIET`) and `forTier(tier)`. `AccentLevel.FULL` allows all four accents; `QUIET` resolves Marigold and Coral requests to Lime and Sky except for `Accent.Primary` (the one marigold action per screen). `TierTokens` itself is unchanged; `PlaygroundTokens` is read next to it. A `PlaygroundTokensTest` asserts the younger tiers get the larger values and that `QUIET` never returns Coral.
- No new fonts, no new Material roles. `Typography` gains nothing; the 68 sp prompt style is a local `TextStyle` built from `displayLarge`.

### 2.2 `feature/games` (theme wrapper and components, new package `playground`)

- `PlaygroundTheme(tier) { … }`: provides `LocalPlaygroundColors` and `LocalPlaygroundTokens`, and wraps the content in a `MaterialTheme` whose `colorScheme` is the app's dark Morning garden scheme with `background`/`surface` set to Field, `onSurface` to Chalk and `primary` to Lime, so stock Material pieces inside a game (the Sapling `Switch`, `OutlinedTextField`, `CircularProgressIndicator`, the top bar) pick up the field without per-call colours. Every game screen composes it inside `ProvideKidTier`; the hub does not.
- `PlayField(title, onBack, backEnabled, content)`: the full-bleed scaffold: Field background behind the system bars (edge to edge, light status-bar icons), the transparent top bar with the back tile, the title in Outfit 800 and the Lime sprout mark; replaces `GamesTopBar` + `Scaffold` in `GamePlayScreen`.
- `PlayTile(accent, onClick, enabled, modifier, content)`: the chunky tile. Draws the edge as a second rounded rect below the fill; on press, translates down by the edge height (80 ms, skipped under `LocalReducedMotion`); `enabled = false` dims to 40% and drops the edge; `Outline` accent is a 2 dp Chalk-60% border with no edge. Minimum size `TierTokens.minTouchTarget`.
- `SegmentProgress(done, total)`: fat segments, Lime done, Chalk-15% to come, plus the "3 of 8" label; keeps the existing `contentDescription` "Round 3 of 8".
- `CountdownRing(roundKey, limitMs)`: replaces `TimeBar`; a 48 dp ring whose Sky stroke sweeps away over `limitMs` (same `Animatable` driven by `roundKey`), the whole seconds left in Outfit inside; `contentDescription` "Time left". Still informational only.
- `SequencePanel(slots, size)`: the memory sequence; filled slots show the shape glyph on its accent with an index badge, empty ones a dashed Chalk outline; the live-region semantics of today's `SequenceCard` move here unchanged.
- `ShapeGlyph(symbol, size)` and `PictureGlyph(symbol, size)`: vector drawings for `Symbol.id` (colours and shapes for Sprout and Sapling, the twelve Seedling pictures), replacing `Symbol.glyph` emoji on screen (D-M9-5 applied to the games; `glyph` stays in `core/games` for the wire and tests, unused by the UI). Shapes map red → Coral, green → Lime, blue → Sky, yellow → Marigold; the spoken `label` is unchanged ("red circle" is still what TalkBack says).
- `RewardCard(lines)`, `Rosette(perfect)`, `StreakPill(count)`, `NumberPad(onDigit, onBackspace, onGo)`, `ReadAloudTile` (the Sky `volume_up` tile that replaces `ReadAloudButton`'s "🔊").
- `Confetti` in `core/ui` takes a `colors: List<Color>` parameter (default the Morning garden three) and the games pass Marigold, Sky and Coral.

Each component gets a `@Preview` per tier and a Roborazzi golden per tier in `feature/games` (`playground_<component>_<tier>`), `checkAccessibility = true`.

## 3. Screen by screen

Stitch screen ids are in project `10251972321848422943`; local renders in `.stitch/designs/`. "Content" names what the render shows; the app shows the seeded content of `GamesPreviewData`, so goldens will differ in numbers, never in layout.

### 3.1 Chosen renders (Playground)

| Screen | Tier | Stitch screen | Local render | Notes for the implementer |
|---|---|---|---|---|
| Numbers round | Sprout | `1489d6d6f61946aebc01e457623cea5d` | `games-B1-numbers.png` | Segmented progress, the sum at 68 sp, 2 by 2 Marigold tiles, Streak pill. The countdown ring in this render is decorative: timed mode is Sapling only (and Times tables), so Sprout Numbers shows no ring. |
| Memory, showing | Sprout | `8dc9e42b2e3c41ec8571fc50d67b42b9` | `games-B1-memory-showing.png` | "Watch carefully!", sequence panel with index badges, four shape tiles dimmed and disabled; the "Gold / Sky / Coral / Lime" captions under the tiles are dropped (the shapes carry their own names for TalkBack). |
| Memory, answering | Sprout | `12b68c39506f45408ea666ccd514300f` | `games-B1-memory-answering.png` | Tapped slots get a Lime tick badge; "2 of 4 tapped" caption keeps today's plural string. |
| Word scramble | Sprout | `45740e013dbb43adb129550efd5b0faf` | `games-B1-word.png` | Hint as a Lime pill, Sky answer slots, Marigold letter tiles that dim when used, outline "Clear" pill. |
| Result, credited | Sprout | `18f35350ab8f43c5bfb5c14887971b7f` | `games-B1-result.png` | Rosette, "You got 7 of 8!", Lime reward card, outline "Play again" and Marigold "Done" tiles, flat confetti. The clamped, capped, refused and unknown states reuse the card with today's copy; no rosette for capped or nothing-earned (D-M5-7). |
| Times tables setup | Sprout | `62140d5fe08543d080b1eb91c5ca55fc` | `games-B-tables-setup.png` | Round number tiles 2 to 12 plus Mix, three level tiles, "Best this week" from the ledger, Marigold "Start · 20 questions". Drop the "Sprout level · Age 9" chip and the "+25p for 80% accuracy" line (not in the app). |
| Times tables play | Sprout | `c3eefdb75a1c476cbd6f5ce1ce4d8bbc` | `games-B-tables-play.png` | Setup chip in the top bar, 20-segment progress, countdown ring, question at 68 sp, white answer box, Right/Streak line, Sky number pad with Coral backspace and Marigold Go. |
| Numbers, counting | Seedling | `744a2e04222d46a0aa59828db1271c91` | `games-B-seedling-numbers.png` | "How many?" with a Sky read-aloud tile, the dots as Lime circles on a panel, four 112 dp picture tiles (numeral over that many dots). |
| Memory, answering | Seedling | `042730d8a54841eeb15fb09c607a7127` | `games-B-seedling-memory-answering.png` | Picture glyphs (flower, sun, apple) on white 104 dp tiles; three choices, three slots. |
| Result, credited | Seedling | `e574521be18344f486f6961f988f77f7` | `games-B-seedling-result.png` | "+8 pennies" alone on the reward card (D-M5-10), a read-aloud tile under it, 80 dp buttons. |
| Start card, timed, with history | Sapling | `e2363262b0154f2fa2325fe63b9db7a8` | `games-B-sapling-start-timed.png` | Quiet Playground: panel card with the description, rounds chip, Timed switch, Marigold "Start"; "Your last plays" rows from the ledger (date, score, credit); the "Best streak" footer is dropped (no data). |
| Numbers, timed, typed | Sapling | `64686840544743b5a7ee4fcf9d87beb1` | `games-B-sapling-numbers-timed.png` | 10 thin segments, Sky ring, expression, ± tile and answer field, Marigold "Check", Field-outline number pad. The render lost its icon font (icon names show as text); the layout stands. |
| Memory, answering, distractors | Sapling | `9c40ac32d99646729ed043056adde917` | `games-B-sapling-memory-answering.png` | Five slots, seven outline shape tiles (two distractors unmarked), "Timed · bonus if every round beats the clock" caption. |
| Word scramble, typed | Sapling | `1fa5a59bbb6548118083bc33ecff5824` | `games-B-sapling-word.png` | No hint, seven slots and tiles, "Type the word" field, outline "Clear"; the Marigold "Submit word" tile is dropped (a round ends when the word is complete, as today). |
| Times tables play | Sapling | `c721dab768f7418788093ea24fa20575` | `games-B-sapling-tables-play.png` | Rock star level: 20 thin segments, 4 s ring, Field-outline pad, "Right 10 · Streak 6". |
| Numbers, tablet landscape | Sprout | `8294857cf2eb4c3a879b2bdd0b578e76` | `games-B-tablet-numbers-landscape.png` | Two columns: prompt left, 2 by 2 tiles right (see §5). |
| Times tables play, tablet portrait | Sprout | `8bab141b97fc4fbda11610936b9fd930` | `games-B-tablet-tables-portrait.png` | One 640 dp column on the full-bleed field (see §5). |

### 3.2 The other renders (kept for the record)

| Set | Screens | Local renders |
|---|---|---|
| A, Morning garden (not chosen) | Numbers `5dfaaad7d66a4b4b81d7489cb6be9153`, Memory showing `b9919531a1c54bc4b3bca0eac370eb5e`, Memory answering `70115db82eb44e37a2a53a1eabceda89`, Word `bb96c51e985645b48aaacbf13e06acd8`, Result `275404d68bcd43f78e4d01506169c90d`, Times tables setup `d4b64c3abd1a4ae893c5f9f55ec136da`, Times tables play `c48c3ef57b7f4626875c81c06049fcf3` | `games-A-*.png` |
| B, first pass before the clean-up edit | Numbers `2fd491c9badb4fd4b9318b0f25ba5cc3`, Memory showing `d7d7cf5dc8ad4d649644bdf5eff71436`, Memory answering `d64cdb42ad7b45139519bfc62cbee8e7`, Word `0ed51d9e24d84f9fa42d7e37fdcee36a`, Result `ec7cfa73420d436fbd0c460f39fc2211` | `games-B-*.png` |

Stitch invented a few things in every set that the app does not have and the implementer must not add: a "Sprout level · Age 9" chip, "Daily set" and "Quick subtraction" labels, "Complete all 8 to earn 25p" footers (a play pays at most 15 minor units, `GameCredit.MAX_PER_PLAY`), "87% accuracy · added to your wallet" lines, a "Best streak" and a best time. The clean-up edit removed most of them from the Sprout set.

### 3.3 Screens with no render

The hub (Morning garden, M9 §4), the Start card for Seedling and Sprout (the Sapling render without the Timed row and history, a Marigold "Start"), Memory showing for Seedling and Sapling (the answering renders with the tiles dimmed), Word scramble showing for Sprout before any letter (the Sprout render with empty slots), the clamped / capped / refused / unknown results (the credited render with the copy of M5 as built), the `Unavailable` and `Loading` states (PlayField with the existing `ErrorContent` / `LoadingContent` in Chalk).

## 4. Per-tier rules

| | Seedling | Sprout | Sapling |
|---|---|---|---|
| Accents | all four | all four | Lime and Sky; Marigold for one primary action; no Coral |
| Tiles | 112 dp tall, 24 dp corners, 4 dp edge, 2 by 2 at most | 72 dp, 20 dp, 4 dp | 56 dp, 16 dp, 3 dp; number pad and text fields |
| Type | prompt 68 sp, nothing under 18 sp, type scale 1.3 as today | prompt 68 sp, scale 1.15 | prompt 48 sp, scale 1.0 |
| Progress | 5 fat segments | 8 fat segments | 10 (20 for Times tables) thin segments |
| Timer | none | Times tables only | countdown ring in timed mode and Times tables |
| Read-aloud | Sky tile beside every heading and under the result | none | none |
| Memory | picture glyphs, 3 to 5 slots, choices = slots | shape glyphs, 4 to 7, choices = slots | shape glyphs, 5 to 9 plus 2 distractors |
| Numbers | counting and sums with picture tiles | four number tiles | typed with ± and the number pad |
| Word scramble | not offered (D-M5-6) | tiles with a hint pill | tiles plus a text field, no hint |
| Start card | description, rounds, Start | same | plus Timed switch and "Your last plays" |
| Result | rosette or Lime star, "+8 pennies" card, read-aloud tile | rosette or star, reward card with the cap line | same as Sprout, plus "Timed bonus!" in Lime |

Content rules unchanged from M5: a tier plays its own difficulty; the Seedling read-aloud triggers and the memory live region stay as built; every choice keeps its content description; the Sapling back is disabled while "Saving…".

## 5. Tablets (portrait and landscape)

Kiril asked for tablets to be accounted for. Today the games are phone layouts capped at 640 dp (`contentWidth`, D-V1-22) and only the hub has tablet goldens (`games_hub_tablet_*`, the 1280 by 800 `ScreenshotWindow.TABLET`). Playground rules:

- The field is always full bleed: Field green to every edge of the window, in both orientations, with the content column centred. Nothing stretches to the window width; tiles keep their phone sizes and a maximum width (answer tiles 180 dp, letter and shape tiles their phone size, the number pad 420 dp).
- **Portrait tablet (Medium width class, 600 to 840 dp):** the phone layout in one 640 dp column, larger gutters, the number pad centred at 420 dp. Render: `games-B-tablet-tables-portrait.png`.
- **Landscape tablet (Expanded, 840 dp and up):** two columns under one top bar and one progress row. Left (55%): the prompt (sum, expression, "Watch carefully!", the word slots), vertically centred. Right: the answers (the 2 by 2 tiles, the shape tiles, the letter tiles, the number pad), vertically centred, with the streak pill or Clear under them. Memory keeps the sequence panel on the left and the choices on the right so the kid's eyes move once. The result stays one column. Render: `games-B-tablet-numbers-landscape.png`.
- No navigation rail inside a game (M9 §5.2 hides the bar and rail for `GamePlayKey`); the hub keeps the rail on Expanded windows, which its tablet goldens show.
- The layout switches on `LocalWindowWidthClass` (already provided by `captureWidthClassTiers`), never on orientation alone, so a foldable in its unfolded state gets the two-column layout too.
- Goldens: `core/testing` `ScreenshotWindow` gains `TABLET_PORTRAIT` (800 by 1280 dp, Medium, suffix `tablet_portrait`); the games screenshot test captures every play screen and the result in `TABLET` and `TABLET_PORTRAIT` for every tier (`games_<screen>_tablet_<tier>`, `games_<screen>_tablet_portrait_<tier>`), on top of the phone goldens. The hub keeps its tablet goldens. `docs/SCREENSHOT_TESTS.md` lists the new window.

## 6. The Times tables game

A fourth `GameKind`, `TABLES`, offered to Sprout and Sapling (Seedling keeps two games, D-M5-6). Modelled on Times Table Rock Stars: the kid picks which tables to practise and how fast the clock runs, then answers 20 questions by typing. It is the only feature work in M9 (D-M9-G4); everything below lives in the same modules as M5.

### 6.1 `core/games`

- `TablesSetup(tables: Set<Int>, level: TablesLevel)`: `tables` is a non-empty subset of 2..12, or `TablesSetup.MIX` (all of them); `TablesLevel { GENTLE(20 s), STEADY(10 s), ROCK_STAR(5 s) }` with `secondsPerQuestion`. Default setup per difficulty: Sprout `{2, 5, 10}` Gentle, Sapling `{6, 7, 8, 9}` Steady. Wire form of the setup is **not** part of `GameId` (the ledger sees `tables.sprout` and `tables.sapling`); it is kept locally (§6.3).
- `GameSession.create(gameId, seed, timed, setup: TablesSetup? = null)`: for `TABLES`, 20 `TablesRound(a: Int, b: Int, answer: Int, prompt: String, spokenPrompt: String)` drawn evenly across the chosen tables, `b` in 1..12, no question repeated in a session, the order shuffled by the seed; `require(setup != null)` for `TABLES`, `require(setup == null)` otherwise. `GameCatalogue.rounds(tables.*) = 20`; `supportsTimed(TABLES)` is true at both difficulties: `ROCK_STAR` sets `timed = true` so the existing `timedBonus` rule applies, with `timeLimitMs` read from `TablesLevel.secondsPerQuestion` instead of the per-kind constant (Gentle and Steady are not timed: the ring is informational and there is no bonus).
- Scoring and credit are unchanged (`GameScorer`, `GameCredit.earned`: 1 per correct, +3 perfect, +2 timed bonus, capped at 15), so a perfect Rock star play earns 15 and a perfect Gentle play 15 too (20 + 3 capped); the cap makes the level a choice of difficulty, not of pay. `GameCredit.earned(wire, correct)` and `LedgerRules` accept the new ids because `GameId.parse` does; `score ∈ 0..20`.
- Tests: generator (seeded replay, every question from a chosen table, no repeats, Mix covers all tables over a few seeds), catalogue rounds and timed rules, credit table rows for `tables.*`.

### 6.2 `core/domain-ledger`, `core/database`

No schema or rules change: `GamePlayed` carries `gameId = "tables.sprout"`, `score ≤ 20`, `amount ≤ earned`. `LedgerPropertyTest`, `CanonicalEncoderTest` and `HashChainTest` gain the new ids in their fixtures. The Firestore rules already bound `gameId` by length only.

- `GameHistoryReader.observeRecent(kidId, kind, limit = 3): Flow<List<GamePlaySummary(playedOn, gameId, score, amount)>>` in `core/database`, projection-backed like `GameCapReader`, for the Sapling start card ("Your last plays") and the Times tables setup ("Best this week": the highest `score` of the last seven days). No time per play is recorded anywhere, so there is no "in 1:42"; the render's best time is dropped (D-M9-G6).

### 6.3 `feature/games`

- `GamesPorts` gains `GameHistorySource` (the reader above) and `TablesSetupStore { observe(kidId): Flow<TablesSetup?>; suspend save(kidId, setup) }`, bound in `app` `di/GamesModule` to a small per-kid DataStore preference (local, not synced, cleared by the wipe like other prefs). The hub tile reads "Times tables · 3, 4, 6" from it.
- `GamePlayUiState.Start` gains `setup: TablesSetup?`, `history: List<GamePlaySummary>` and `bestThisWeek: Int?`; `RoundUi.Tables(round, typed)`; events `TablesChanged(tables)`, `LevelChanged(level)`, `Digit(d)`, `Backspace`. The `GameViewModel` keeps the setup in `SavedStateHandle` next to the seed, so rotation and process death resume the same 20 questions.
- Screens: `TablesSetupContent` (the start card for `TABLES`: the number tiles, the level tiles, best this week, Start) and `TablesGame` (the round: prompt, answer box, number pad, Right/Streak line). The streak is per session and lives in the UI state only. The Sprout version uses Marigold tiles and a Sky pad; the Sapling version the quiet set.
- Strings: "Times tables", "Which tables?", "Mix", "Gentle · 20 seconds a question", "Steady · 10 seconds a question", "Rock star · 5 seconds, bonus credit", "Start · 20 questions", "Best this week: 18 of 20", "Right 6", "Streak 4", "Go". Read-aloud is not offered (no Seedling).
- Goldens: `games_tables_setup_{sprout,sapling}`, `games_tables_{sprout,sapling}`, `games_tables_timed_sapling`, `games_start_history_sapling`, and the hub goldens re-recorded with the fourth tile (phone, tablet and tablet portrait).

### 6.4 `app` and docs

`GamePlayKey` carries `kind = TABLES` unchanged; the hub lists it from `GameCatalogue.forTier`. Docs: ADR-0009 gets an amendment (new kind, setup kept local, level is difficulty not pay), `docs/THREAT_MODEL.md` T4 notes the 20-question score range, `docs/PRIVACY.md` games row notes the local setup preference, M5.md "As built" gets a pointer here, `tools/store/listing.yaml` showcase 06 is re-shot from the new hub.

## 7. Implementation slices (one Opus implementer each, in this order; two writers never touch the same files)

- **G-A. Times tables logic** (`core/games`, the test fixtures in `core/domain-ledger` and `core/database`, `GameHistoryReader` in `core/database`): §6.1 and §6.2. Independent of the visual work; can start before M9 slice A. Done: `./gradlew :core:games:test :core:domain-ledger:test :core:database:test` green, an API summary for G-C.
- **G-B. Playground tokens and components** (`core/designsystem` `theme/Playground.kt`, `core/ui` `Confetti` colours, `core/testing` `ScreenshotWindow.TABLET_PORTRAIT`, `feature/games/playground/*`): §2 with previews and per-tier goldens; the glyph drawings for the twelve pictures and twelve shapes. After M9 slice A (it needs the bundled fonts). Done: `./gradlew :core:designsystem:test :core:ui:test :core:testing:test :feature:games:test` green with the component goldens recorded locally for review; no game screen changed yet.
- **G-C. Game screens** (`feature/games` screens, strings, `GamesPreviewData`, `GamesScreenshotTest`; `app` `di/GamesModule` and `TablesSetupStore`): every play screen, the start card, the result and the hub tile on Playground per §3 and §4, the tablet layouts per §5, the Times tables screens per §6.3. Replaces the games part of M9 §6 slice D (chat and time bank stay there). Done: every games screen keeps its screenshot tests per tier, with the tablet and tablet-portrait windows added for play and result screens and the new Times tables goldens; `ScreenshotTest(checkAccessibility = true)` stays on; `./gradlew :feature:games:test :app:test` green; goldens recorded on Linux by `record-screenshots.yml` (D-M9-7).
- **G-D. Docs and listing**: §6.4, this file's "As built", `docs/SCREENSHOT_TESTS.md`, `docs/ACCESSIBILITY.md` (Playground contrast table from `DESIGN-playground.md`). Folds into M9 §6 slice F.

Reviewer after every slice; no security-audited module is touched (the ledger rules do not change), but G-A's fixture changes in `core/domain-ledger` go past the security auditor as a courtesy read.

## 8. Done condition

- Kiril chose the character from rendered screens (done, 2026-11-11) and saw the Seedling, Sapling and tablet variants before the implementer started.
- `DESIGN-playground.md` values are what the components use; any later change goes through a Stitch render first.
- Every games golden exists per tier (and per window where §5 says so), the accessibility rule passes, contrast holds (§2 of the design file), no screen lost a variant.
- Times tables plays round-trip through the ledger on two devices (JVM convergence test) with `tables.sprout` and `tables.sapling` and scores up to 20.
- `./gradlew build detekt lint test verifyRoborazziDebug --no-daemon` green on Linux CI.

## 9. Out of scope

Leaderboards or sharing of Times tables results, time-per-play records, per-table statistics, a parent-set table selection, Seedling Times tables, sound effects, a Playground treatment for the hub, dark theme for Playground (the field is already dark; the app's dark theme does not change it).

## 10. Decisions

D-M9-G1 Playground chosen by Kiril over the Morning garden re-skin; D-M9-G2 Sapling gets a quiet Playground, not Morning garden; D-M9-G3 games-only theme wrapper in `feature/games` with the colours in `core/designsystem`; D-M9-G4 Times tables is the one feature in M9; D-M9-G5 memory symbols drawn as vector shapes and pictures, emoji kept only on the wire; D-M9-G6 no time-per-play record, "best" is the best score this week from the ledger; D-M9-G7 Times tables setup is a local per-kid preference, not a ledger field; D-M9-G8 the level changes difficulty, not pay (the 15-unit cap stays); D-M9-G9 tablets get full-bleed field, 640 dp column in portrait, two columns in landscape, and a new portrait tablet screenshot window. Details in `docs/DECISIONS-PENDING.md`.

## As built

(Nothing yet.)
