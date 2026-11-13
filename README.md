# Sprout

Sprout is an Android app for running a small "family economy". Kids earn credits by doing chores and playing short brain games, then spend them on pocket money, screen time, or rewards the parent has set up. Parents run allowances, savings goals with optional interest, a rewards catalogue, and a ledger that records every credit and debit.

It is one app with two roles. The parent area sits behind a PIN or biometric gate. Kid mode runs on the kid's own device with a UI that adapts to the kid's age. Devices sync through Firebase, chat and chore photos are end-to-end encrypted, and all AI features run on the device. The project is a portfolio piece built to the standard of a production app: multi-module, tested, threat-modelled, and aimed at a Play Store release.

**Status:** milestones M0 to M9 are done (see [Roadmap](#roadmap)). 1.0.0 is the feature-complete v1; 1.1.0 (M9) is a visual redesign with a bottom navigation bar and a fourth brain game, Times tables; 1.1.1 lets a parent restore the household on a wiped or new phone with the parent PIN, or after a seven-day wait ([ADR-0013](docs/adr/0013-account-recovery.md)). What is left before the Play internal track is the owner's console, real-project and device work, step by step in the [release checklist](docs/RELEASE_CHECKLIST.md).

## Features

### Parent mode

- **Household setup:** add kids (first name, birth month and year, avatar) and link a kid device with a short single-use code.
- **Chores:** one-off or recurring, rewarded in money and/or minutes; approve or reject submissions, with an optional photo.
- **Allowance and savings:** weekly or monthly auto-credit per kid, savings goals, optional interest to teach compounding.
- **Dashboard:** per-kid balances (money and time), spend by category, goal progress, charts, and an on-device AI weekly summary.
- **Rewards catalogue:** screen time, treats, outings, priced in credits.
- **Chat and audit:** one encrypted thread per kid; an audit view of every ledger event with author and device.
- **Data controls:** keep chat messages forever or for 30, 90 or 365 days on every phone and the server, delete the chat history now, and export the household with the chat in readable form (each behind the parent's fingerprint or PIN).
- **Tablets and foldables:** list and detail side by side for settings, chat and chores, two dashboard columns, and content capped to a readable width.
- **Navigation:** a bottom bar with Home, Chores, Chat and Settings (a navigation rail on tablets); each tab keeps its own back stack, and approvals, the shop and a kid's wallet open inside their tab.

### The look

The "Morning garden" design (1.1.0, [M9](docs/milestones/M9.md)): cream pages, white cards with hairline borders, one botanical green with soft leaf, honey and sky tints, the Outfit display face for titles and amounts and Plus Jakarta Sans for text, both bundled. Icons are Material Symbols and chores, rewards and games use a set of flat pictograms instead of emoji. The colour follows the kid's age: Seedling gets the full garden, Sprout gets colour only where it means something, and Sapling is as calm as the parent side. The games have a character of their own, "Playground" ([M9 games](docs/milestones/M9-games.md)): a deep green play field with chunky tiles in four flat colours, quieter for Sapling.

### Kid mode

Every kid screen reads a `KidUiTier`. The tier is suggested from the kid's age and the parent can override it. Same data, different presentation; lower tiers hide functionality, they never break it.

| | Seedling (~5–7) | Sprout (~8–11) | Sapling (~12+) |
|---|---|---|---|
| Balance | Coin jar, whole numbers, read-aloud | Real currency, goal progress, last 10 entries | Full ledger with filters, interest explained, CSV export |
| Chores | Up to 3 picture cards, one-tap "I did it" | List with due dates, note and photo | Can propose a chore and rate for approval |
| Navigation | Bottom bar with Wallet, Chores and Shop; giant Chat, Games and Screen time pills under the wallet | Bottom bar with Wallet, Chores, Shop, Games and Chat | Same as Sprout |
| Shop | Picture tiles that say "You can buy this now" or "Keep saving" | Prices and "days until affordable" | Full catalogue, wishlist with savings plan |
| Chat | Presets, emoji, voice notes | Free text with kindness check | Free text, kindness nudge only |
| Games | Counting, simple sums, picture memory | Multiplication, patterns, word scramble, Times tables | Harder arithmetic, logic, optional timed mode, Times tables |

On the kid's own phone a home screen widget shows their balance, screen time and the chores still to do today; on any other phone it only says "Open Sprout" (the home screen is not protected from screenshots, [ADR-0012](docs/adr/0012-widget-data-exposure.md)).

**Times tables** (1.1.0) is the fourth game, modelled on Times Table Rock Stars: the kid picks the tables to practise (2 to 12, or Mix) and a level (Gentle, 20 seconds a question; Steady, 10 seconds; Rock star, 5 seconds with the timed bonus), then types the answers to 20 questions. The level changes the difficulty, not the pay: credit follows the same rules and daily cap as the other games ([ADR-0009](docs/adr/0009-games-scoring-and-credit.md#amendment-m9-110-2026-11-12)).

Screen-time purchases go into a visible time bank the kid starts and stops, from the screen-time badge on the wallet. Enforcement is on the honour system in v1; the app requests no Accessibility, UsageStats, overlay, or device-admin permissions.

## Architecture

Multi-module Gradle build with convention plugins, Compose-only UI, Hilt, Coroutines and Flow. Each ViewModel exposes one `StateFlow<UiState>` and takes events through one `onEvent` function. Feature modules depend only on `core/*`, never on each other. The ledger and shared models are pure Kotlin so they can move to Kotlin Multiplatform without rewrites. Writes land in Room and an outbox in one transaction; a sync engine drains the outbox through a `SyncTransport` interface, and Firebase is one implementation of it.

```
app/                         — thin shell, Hilt entry, Navigation 3 with a bottom bar and one back stack per tab, role switch, lock screen
build-logic/                 — convention plugins (android application/library/compose/feature/test, jvm library, hilt)
core/
  domain-ledger/             — pure Kotlin: events, projections, Money, hash chain
  model/                     — shared models (Kid, Chore, Reward, Message, KidUiTier)
  crypto/                    — Keystore-backed device keys, household key, signing, AES-GCM, Tink wrappers
  database/                  — Room (SQLCipher) entities, DAOs, projection cache, outbox, audit log
  sync/                      — JVM: SyncEngine, DocumentEnvelope, ConflictResolver, SyncTransport interface
  sync-firebase/             — Firestore/Auth/AppCheck transport, client for the Sprout server
  ai/                        — AiGateway interface + Gemini Nano impl + rules-based fallback
  designsystem/              — Morning garden theme, bundled fonts, tier-scaled tokens and colour dial, components (cards, buttons, bottom bar), pictograms, Playground game tokens
  ui/                        — shared composables (charts, balance jar/ring), window-size helpers (width classes, content width, adaptive grids)
  testing/                   — screenshot and accessibility test infra (fakes live in each module's testFixtures)
  time/                      — pure Kotlin: Clock over kotlin.time.Instant, FakeClock test fixture
  common/                    — dispatchers, Result, logging (PII-safe, M1)
feature/
  onboarding/                — sign-in (Credential Manager), household setup, device linking
  lock/                      — parent PIN/biometric gate, kid-mode exit gate
  parent-home/               — dashboard, charts, AI summary
  chores/                    — parent CRUD, kid today (3 tiers), submission/approval
  wallet/                    — balances (3 tiers), savings goals, allowance, shop
  games/                     — 4 games (Numbers, Memory, Word scramble, Times tables) on the Playground theme, tier difficulty, scoring → ledger events
  chat/                      — E2EE threads, kindness check, presets/voice for Seedling
  timebank/                  — kid time bank timer
  settings/                  — kids & tiers, devices, PIN, chat retention, readable data export, delete household
server/                      — the Sprout server (TypeScript, Hono on Vercel): households, devices, link codes, parent keys, revocation, export, delete
firestore/                   — security rules, indexes, rules tests
widget/                      — Glance widget: kid balance + chores due, on the kid's own phone only
benchmark/                   — Macrobenchmark + Baseline Profile generator
docs/adr/                    — architecture decision records
```

The reasoning behind this layout is in [ADR-0001](docs/adr/0001-architecture.md).

## Security and privacy

- **Tamper-evident ledger.** Every ledger event is signed by the authoring device and chained to that author's previous event by hash. Receivers verify signature and chain before applying; failures are quarantined and shown in the parent audit view. See [ADR-0002](docs/adr/0002-ledger-as-events.md).
- **Parent authorisation.** The parent area sits behind a PIN, and every money event a parent authors (chore rewards, allowance, interest, adjustments) is also signed with a parent key that only a strong biometric can use (on devices that have one), one fingerprint prompt per event, each naming the kid, the amount and what it pays for. The key is created only after the parent enters the PIN, so knowing the phone's screen lock is not enough to award credits. Scheduled allowance and interest wait for the parent to tap "Sign now". See [ADR-0004](docs/adr/0004-parent-authorisation-and-device-identity.md) and [ADR-0005](docs/adr/0005-scheduled-payments-and-money-flow-atomicity.md).
- **End-to-end encrypted chat and photos.** One random AES-256-GCM key per kid thread and epoch, wrapped for each household device with X25519 + HKDF; every device's encryption key is bound to its registered device key, and a parent device rotates the thread key when a device is removed. Messages, voice notes and photos (chat and chore photos) reach Firestore as signed ciphertext; there is no Cloud Storage. See [ADR-0008](docs/adr/0008-chat-encryption-and-thread-keys.md).
- **Keystore-backed keys.** Each device holds a P-256 key pair in Android Keystore (StrongBox where available). Kid devices get their own Firebase identity; they never see parent credentials.
- **Server-side enforcement.** Firestore rules check household membership, author identity, role, an active registered device, the conflict order, and ledger immutability. Identity changes (households, device registration and approval, link codes, parent keys, revocation, export, deletion) go through the Sprout server, which verifies key attestation, device-signed requests and App Check, and rate-limits every call. See [ADR-0006](docs/adr/0006-sync-protocol-and-document-envelope.md) and [ADR-0007](docs/adr/0007-identity-linking-and-device-registry.md).
- **Encrypted at rest.** Room runs on SQLCipher with a Keystore-wrapped passphrase. The parent PIN is hashed with Argon2id (PBKDF2 fallback) and protected by exponential lockout. Parent and chat screens use `FLAG_SECURE`.
- **No analytics, no ads.** No analytics, advertising or crash-reporting SDKs ship in v1 (the release classpath is checked against the [data-safety inventory](docs/DATA_SAFETY.md) in CI). Kids are a first name, an avatar, and a birth month and year.
- **Published policies.** The [privacy policy](docs/PRIVACY.md) and the [security and disclosure policy](docs/SECURITY.md) are rendered from this repository to static pages the server hosts (`/privacy`, `/security`, `/.well-known/security.txt`); CI fails when the pages drift from the Markdown.

The full design is in [ADR-0003](docs/adr/0003-security-model.md) and the [threat model](docs/THREAT_MODEL.md).

## On-device AI

All AI runs on the phone through one `AiGateway` (`core/ai`, [ADR-0010](docs/adr/0010-on-device-ai.md)). `AiRouter` uses **Gemini Nano** (ML Kit GenAI Prompt API on AICore, structured output) where the device has it and the parent has not switched it off, and a deterministic **rules** implementation everywhere else and whenever the model is busy, slow or wrong. The rules path is the reference behaviour: every feature works the same on the emulator and in CI, and Nano only improves the wording.

- **Kindness check:** before a Sprout or Sapling kid sends a message, it is checked on device; an unkind one gets a gentle nudge offering "Send anyway" and a kinder message ("Use this") side by side. It never blocks and never reports (2.5 s, then it sends), and it never nudges a kid reporting what someone else said or did, or asking for help.
- **Weekly parent summary:** facts computed deterministically from the ledger (`WeeklyFactsUseCase`) and written up in 2 to 4 sentences, "This week so far" or "Last week", with an "On-device" chip. Every number in a model-written summary must be one of the facts, or the template text is shown.
- **Chore suggestions** by age band in the chore editor: three to five ideas from a curated catalogue (or the model, validated against it) that fill the form; the parent saves.
- **Receipt to manual adjustment:** on-device text recognition (Google Play services) reads a picked receipt photo; the total and shop prefill the adjustment, which the parent reviews and signs. Never the model, and only while the On-device AI switch is on.

Model output is untrusted: user text goes in as data, answers are validated (`AiOutputRules`) and replaced by the rules result when they fail, and nothing AI produces is stored or reaches the ledger without a parent. The model is downloaded only after a parent's consent in Settings → On-device AI (by the system, usually on Wi-Fi). No message, chore, figure or photo leaves the device for AI, and ML Kit's own metrics uploader is removed; Google Play services does record that the receipt scanner was used (never the receipt), which Sprout cannot switch off, as the [privacy policy](docs/PRIVACY.md#on-device-ai) says. The Nano path is unverified on hardware until a run on a supported Pixel.

## Build and run

Requirements: JDK 17 (enough for Gradle 9.8.2, AGP 9.4.1, and Kotlin 2.4.21) and the Android SDK with the API 37 platform. The app targets API 37 and runs on Android 9 (API 28, minSdk) and later. Use the Gradle wrapper; no global Gradle install is needed.

```
./gradlew build                 # compile everything
./gradlew test                  # JVM unit tests
./gradlew lint detekt           # static analysis
./gradlew :app:installDebug     # install on a running emulator or device
```

### Backend

The backend is Firebase (Auth and Firestore, on the free Spark plan) plus the Sprout server in `server/`: a small TypeScript service (Hono, `firebase-admin`) on Vercel that speaks the Firebase callable protocol, because Spark has no Cloud Functions. Debug builds talk to the **Firebase Emulator Suite** (project `demo-sprout`, which cannot reach any real project) and to the server running locally. Requirements: Node 24 and Java 21 for the emulators. From the repository root, in two terminals:

```
npm --prefix firestore ci && npm --prefix server ci
npx --no-install --prefix firestore firebase emulators:start --only auth,firestore --project demo-sprout
npm --prefix server run dev        # http://127.0.0.1:8787, reached from an Android emulator as 10.0.2.2:8787
```

Backend tests: `cd firestore && npm test` (rules) and `cd server && npm test` (every callable over HTTP against the emulators). Production setup, deployment and the rules summary are in [docs/FIREBASE.md](docs/FIREBASE.md). A release build needs your own `app/google-services.json` and signing key (see [Release](#release)); neither is committed. Keystores, service-account files, `.env` files and `local.properties` are never committed either.

## Release

A Play release is built from a `v*` tag; nothing secret is in the repository (M8, [release checklist](docs/RELEASE_CHECKLIST.md)).

- **Version:** `versionName` from the latest `v*` tag (`0.1.0-dev` without one, which a Play bundle refuses), `versionCode` from the commit count, or `-PversionCode=<n>` (the workflow's `version_code` input; D-M8-4, D-M8-47). Release from `main` only: the commit count grows along one branch. Play refuses a versionCode it has already seen, so rebuilding a tag needs a higher `version_code` (or a new commit and tag). A shallow clone builds versionCode 1; debug builds carry the same numbers. Details: [release checklist "Versioning"](docs/RELEASE_CHECKLIST.md#versioning).
- **Signing:** the upload key comes from the environment (`SPROUT_UPLOAD_KEYSTORE`, `SPROUT_UPLOAD_KEYSTORE_PASSWORD`, `SPROUT_UPLOAD_KEY_ALIAS`, `SPROUT_UPLOAD_KEY_PASSWORD`) or the same values as `sprout.upload.*` properties in `~/.gradle/gradle.properties`. The owner creates it once with `tools/release/create-upload-keystore.sh`, which prints the SHA-1 and SHA-256 to register in Firebase; Play App Signing holds the app signing key (D-M8-3).
- **Configuration:** `sprout.functionsBaseUrl` (the server's `https://` URL), `sprout.googleServerClientId` (the OAuth web client id for Google sign-in), `sprout.privacyUrl` (the hosted privacy page, by default `<functionsBaseUrl>/privacy`), and `app/google-services.json`. `./gradlew :app:bundleRelease` (and every task that signs or publishes the bundle) runs `checkReleaseConfiguration` first and fails with a list of whatever is missing. CI never builds the bundle (its `build` runs `assembleRelease` and the R8 and App Check release checks); `-Psprout.allowUnconfiguredRelease=true` waives the configuration check for a local test bundle that is never shipped.
- **Build:** `./gradlew :app:bundleRelease` locally, or the manual `release.yml` workflow on a tag of `main` with the values as secrets of the protected `release` environment (main only, owner approval, D-M8-42); it uploads the AAB and the R8 mapping as artifacts, and publishes to the internal track only when a Play service-account secret is set (D-M8-5).
- **Privacy page:** `python3 tools/privacy/render.py` renders `docs/PRIVACY.md` and `docs/SECURITY.md` into `server/public/`; the committed pages carry example values, and the owner renders the real ones from `docs/privacy.properties` (gitignored) just before deploying the server.
- **Play listing:** the data-safety answers are in [docs/DATA_SAFETY.md](docs/DATA_SAFETY.md); the store text and images are generated from the screenshot goldens by `tools/store/compose.py` into [play/listing](play/listing/README.md).
- **Before production:** the device and real-project checks in the [security review](docs/SECURITY_REVIEW.md) (network capture including a receipt scan, Play Integrity and App Check, Google sign-in, attestation, Gemini Nano).

## Performance

> **Re-run pending (D-V1-11).** The table above predates `ReportDrawnWhen` (D-M7-43) and the accessibility-off start. To refresh it on an idle machine (no other emulator or Gradle build): boot a lock-free API 33 AVD with `-gpu host -port 5580`, then `ANDROID_SERIAL=emulator-5580 ./gradlew :app:generateBaselineProfile -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR --no-daemon` (commit the regenerated `app/src/main/generated/baselineProfiles/*.txt`), then `:benchmark:connectedBenchmarkReleaseAndroidTest` with the same flags, and replace the table with the new P50/P90 values.

Startup and scrolling are measured with Macrobenchmark (`benchmark/`) against the `benchmarkRelease` build, and the app ships a Baseline Profile and a startup profile (`app/src/main/generated/baselineProfiles/`, used by every release build) generated from the same journeys (D-M7-3, D-M7-41). The benchmark build types are release code under R8, debug-signed, with the demo household seeded offline: no Firebase, no sync, no App Check, and a placeholder server URL (`BenchmarkSeed`). The journeys choose the parent PIN on the first run and enter it after that: cold start to the parent dashboard, then in kid mode Zoe's wallet history (a lazy list of 60 seeded rows), today's chores, and the games hub to an arithmetic game.

Results, 4 November 2026, 10 iterations each, on an **emulator** (`Sprout_Benchmark` AVD: Pixel 7 profile, API 33 Google Play arm64 image, 4 cores, 4 GB, host GPU via `-gpu host`) on an Apple M1 Pro laptop that was also running Gradle builds:

| Benchmark | Metric | P50 | P90 |
|---|---|---|---|
| Cold start to first frame, no compilation (`CompilationMode.None`) | `timeToInitialDisplayMs` | 204.5 ms | 224.2 ms |
| Cold start to first frame, Baseline Profile (`Partial(Require)`) | `timeToInitialDisplayMs` | 222.6 ms | 239.8 ms |
| Kid wallet scroll, Baseline Profile | `frameDurationCpuMs` | 2.9 ms | 4.1 ms (P99 19.0 ms) |
| Kid wallet scroll, Baseline Profile | `frameOverrunMs` | -11.3 ms | -7.6 ms (P99 4.7 ms) |

APK size: `benchmarkRelease` 19.9 MB as measured; a `release` build of the same tree is 20.7 MB unsigned, of which the compiled profile (`assets/dexopt/baseline.prof`) is 21 KB.

Read these with care. Emulator numbers are not device numbers (the emulator's CPU, storage and compilation behave differently from a phone's), and a second startup-only run gave 230.0 / 246.6 ms (no compilation) against 235.3 / 263.4 ms (profile), so the two startup modes are within noise of each other here. The first frame Macrobenchmark times is the shell's loading frame, drawn before the database and the PIN screen; the work the profile covers (Hilt graph, Room, the PIN screen, the dashboard) comes after it and is not in this metric, so the profile cannot show its value until the app reports when it is fully drawn (D-M7-43). With software rendering (the AVD's default `swangle` mode) the same scroll measured 41.5 ms P50 and 79.8 ms P99 of CPU per frame, so frame timing is only meaningful with the host GPU. A Pixel run is the next step for publishable figures.

To reproduce (any API 33+ emulator or device without a lock screen, English locale, nothing else using UiAutomator):

```
./gradlew :app:generateBaselineProfile               # regenerates both profiles (about 10 minutes)
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Results land in `benchmark/build/outputs/connected_android_test_additional_output/`. On a Google Play image, Play Protect asks to scan each reinstall; answer "Don't send" or the run stalls. `.github/workflows/benchmark.yml` runs the benchmarks on a Gradle Managed Device; it is manual and optional, not a required check.

## Roadmap

Each milestone ends with green CI and a tagged build.

| Milestone | Scope | Status |
|---|---|---|
| M0 Scaffold | Build logic, version catalog, module skeleton, Hilt, theme and tier tokens, CI, Renovate, ADRs, threat model | Done |
| M1 Ledger core and crypto | Event-sourced ledger with hash chain, Keystore keys and signing, SQLCipher Room, outbox, audit log, wallet (3 tiers) | Done |
| M2 Chores, rewards, tiers | Parent CRUD, kid today and shop (3 tiers), submit/approve, allowance, savings and interest, PIN/biometric lock with fingerprint-signed money events | Done |
| M3 Sync and identity | Sync engine, Firebase transport, App Check, Credential Manager, the Sprout server (Spark plan), Firestore rules and emulator tests, device registry with key attestation, controls matrix, runbook | Done |
| M4 Chat and time bank | E2EE threads, key rotation, Seedling presets and voice notes, chore photo upload, time bank with an ongoing notification | Done |
| M5 Games | Three games, tier difficulty, daily cap, signed scoring events | Done |
| M6 AI | Gemini Nano integration, fallbacks, kindness check, suggestions, weekly summary, receipt prefill ([spec and as built](docs/milestones/M6.md), [ADR-0010](docs/adr/0010-on-device-ai.md)) | Done |
| M7 Showcase polish | Glance widget, adaptive layouts, Baseline Profiles, full screenshot coverage, accessibility, chat retention, readable export, structural `FLAG_SECURE` ([spec and as built](docs/milestones/M7.md)) | Done |
| M8 Play release | Published privacy and disclosure policies, data-safety answers checked against the build, security review, release signing and workflow, store listing, operator script ([spec and as built](docs/milestones/M8.md), [release checklist](docs/RELEASE_CHECKLIST.md)) | Done |
| M9 Visual redesign and Times tables | The Morning garden redesign of every screen with an age-tiered colour dial, a bottom navigation bar (rail on tablets), the Playground game screens with tablet layouts, the Times tables game, new goldens and store images; ships as 1.1.0 ([spec and as built](docs/milestones/M9.md), [games spec and as built](docs/milestones/M9-games.md)) | Done |

## Documentation

- [Project plan](docs/PLAN.md): architecture, security design and the milestones
- [Pending decisions](docs/DECISIONS-PENDING.md): defaults taken during the build, for the owner to confirm
- [Architecture decision records](docs/adr/README.md)
  - [ADR-0001 Architecture](docs/adr/0001-architecture.md)
  - [ADR-0002 Ledger as events](docs/adr/0002-ledger-as-events.md)
  - [ADR-0003 Security model](docs/adr/0003-security-model.md)
  - [ADR-0004 Parent authorisation and device identity](docs/adr/0004-parent-authorisation-and-device-identity.md)
  - [ADR-0005 Scheduled payments and money-flow atomicity](docs/adr/0005-scheduled-payments-and-money-flow-atomicity.md)
  - [ADR-0006 Sync protocol and document envelope](docs/adr/0006-sync-protocol-and-document-envelope.md)
  - [ADR-0007 Identity, linking and the device registry](docs/adr/0007-identity-linking-and-device-registry.md)
  - [ADR-0008 Chat encryption and thread keys](docs/adr/0008-chat-encryption-and-thread-keys.md)
  - [ADR-0009 Games scoring and credit](docs/adr/0009-games-scoring-and-credit.md)
  - [ADR-0010 On-device AI](docs/adr/0010-on-device-ai.md)
  - [ADR-0011 Chat retention and pruning](docs/adr/0011-chat-retention-and-pruning.md)
  - [ADR-0012 Widget data exposure](docs/adr/0012-widget-data-exposure.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Controls matrix (SOC 2 criteria)](docs/CONTROLS.md)
- [Incident runbook](docs/RUNBOOK.md)
- [Firebase backend and the Sprout server](docs/FIREBASE.md)
- [Privacy policy](docs/PRIVACY.md) (published page: `server/public/privacy`), [security and disclosure policy](docs/SECURITY.md)
- [Play data-safety answers](docs/DATA_SAFETY.md), [security review](docs/SECURITY_REVIEW.md) and the owner's [release checklist](docs/RELEASE_CHECKLIST.md)
- [Play store listing](play/listing/README.md)
- [Screenshot tests](docs/SCREENSHOT_TESTS.md) (the screen × tier × state matrix) and the [accessibility checklist](docs/ACCESSIBILITY.md)
- [M2 milestone spec and as-built notes](docs/milestones/M2.md)
- [M3 milestone spec and as-built notes](docs/milestones/M3.md)
- [M4 milestone spec and as-built notes](docs/milestones/M4.md)
- [M5 milestone spec and as-built notes](docs/milestones/M5.md) and [ADR-0009: games scoring and credit](docs/adr/0009-games-scoring-and-credit.md)
- [M6 milestone spec and as-built notes](docs/milestones/M6.md) and [ADR-0010: on-device AI](docs/adr/0010-on-device-ai.md)
- [M7 milestone spec and as-built notes](docs/milestones/M7.md)
- [M8 milestone spec and as-built notes](docs/milestones/M8.md)
- [M9 milestone spec and as-built notes](docs/milestones/M9.md) and the [games addendum](docs/milestones/M9-games.md) (Playground and Times tables)

## Engineering practices

- **Convention plugins** in `build-logic/` configure Android, Compose, Hilt, testing, and lint once; modules apply them instead of repeating configuration.
- **Dependency verification** with SHA-256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP signature verification; trust on first use). OWASP Dependency-Check runs in a separate workflow on a weekly schedule and on manual dispatch, not in the main CI.
- **Renovate** with grouped update PRs against a single version catalog; a workflow refreshes the verification metadata on Renovate branches.
- **Screenshot tests** with Roborazzi on Robolectric for every screen: kid screens in every tier, parent screens light and dark, phone and tablet widths and a 200 % font scale for a subset, the widget too; diffed in CI, recorded on Linux only ([docs/SCREENSHOT_TESTS.md](docs/SCREENSHOT_TESTS.md)). Every screen test also runs an accessibility rule (labels, unique sibling labels, tier touch targets; [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md)).
- **Baseline Profiles and Macrobenchmark** for startup and scroll performance: profiles generated on an emulator and committed, journeys in `benchmark/`, numbers in [Performance](#performance).
- **CI** on GitHub Actions (pinned by SHA): build, unit and screenshot tests, Android Lint, Detekt, ktlint; the build job also checks that the published privacy pages match their Markdown and that the data-safety inventory matches the release build's permissions and SDKs; a `backend` job runs the Firestore rules and server tests against the emulators and `npm audit` at high severity (not a required check yet). Secret scanning with push protection is enabled. A branch ruleset on `main` requires pull requests and the build check, and blocks force pushes and deletion.

## Licence

MIT. Copyright (c) 2026 Kiril Petriv.
