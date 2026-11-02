# Sprout

Sprout is an Android app for running a small "family economy". Kids earn credits by doing chores and playing short brain games, then spend them on pocket money, screen time, or rewards the parent has set up. Parents run allowances, savings goals with optional interest, a rewards catalogue, and a ledger that records every credit and debit.

It is one app with two roles. The parent area sits behind a PIN or biometric gate. Kid mode runs on the kid's own device with a UI that adapts to the kid's age. Devices sync through Firebase, chat and chore photos are end-to-end encrypted, and all AI features run on the device. The project is a portfolio piece built to the standard of a production app: multi-module, tested, threat-modelled, and aimed at a Play Store release.

## Features

### Parent mode

- **Household setup:** add kids (first name, birth month and year, avatar) and link a kid device with a short single-use code.
- **Chores:** one-off or recurring, rewarded in money and/or minutes; approve or reject submissions, with an optional photo.
- **Allowance and savings:** weekly or monthly auto-credit per kid, savings goals, optional interest to teach compounding.
- **Dashboard:** per-kid balances (money and time), spend by category, goal progress, charts, and an on-device AI weekly summary.
- **Rewards catalogue:** screen time, treats, outings, priced in credits.
- **Chat and audit:** one encrypted thread per kid; an audit view of every ledger event with author and device.

### Kid mode

Every kid screen reads a `KidUiTier`. The tier is suggested from the kid's age and the parent can override it. Same data, different presentation; lower tiers hide functionality, they never break it.

| | Seedling (~5–7) | Sprout (~8–11) | Sapling (~12+) |
|---|---|---|---|
| Balance | Coin jar, whole numbers, read-aloud | Real currency, goal progress, last 10 entries | Full ledger with filters, interest explained, CSV export |
| Chores | Up to 3 picture cards, one-tap "Done!" | List with due dates, note and photo | Can propose a chore and rate for approval |
| Shop | Picture tiles, green/grey "can I get it?" | Prices and "days until affordable" | Full catalogue, wishlist with savings plan |
| Chat | Presets, emoji, voice notes | Free text with kindness check | Free text, kindness nudge only |
| Games | Counting, simple sums, picture memory | Multiplication, patterns, word scramble | Harder arithmetic, logic, optional timed mode |

Screen-time purchases go into a visible time bank the kid starts and stops. Enforcement is on the honour system in v1; the app requests no Accessibility, UsageStats, overlay, or device-admin permissions.

## Architecture

Multi-module Gradle build with convention plugins, Compose-only UI, Hilt, Coroutines and Flow. Each ViewModel exposes one `StateFlow<UiState>` and takes events through one `onEvent` function. Feature modules depend only on `core/*`, never on each other. The ledger and shared models are pure Kotlin so they can move to Kotlin Multiplatform without rewrites. Writes land in Room and an outbox in one transaction; a sync engine drains the outbox through a `SyncTransport` interface, and Firebase is one implementation of it.

```
app/                         — thin shell, Hilt entry, Navigation 3 back stack, role switch, lock screen
build-logic/                 — convention plugins (android application/library/compose/feature/test, jvm library, hilt)
core/
  domain-ledger/             — pure Kotlin: events, projections, Money, hash chain
  model/                     — shared models (Kid, Chore, Reward, Message, KidUiTier)
  crypto/                    — Keystore-backed device keys, household key, signing, AES-GCM, Tink wrappers
  database/                  — Room (SQLCipher) entities, DAOs, projection cache, outbox, audit log
  sync/                      — SyncEngine, Outbox, ConflictResolver, SyncTransport interface
  sync-firebase/             — Firestore/Storage/Auth/AppCheck transport
  ai/                        — AiGateway interface + Gemini Nano impl + rules-based fallback
  designsystem/              — theme, tier-scaled tokens, components, previews
  ui/                        — shared composables (charts, balance jar/ring), window-size helpers
  testing/                   — fakes (FakeSyncTransport, FakeCrypto), screenshot infra (M7)
  time/                      — pure Kotlin: Clock over kotlin.time.Instant, FakeClock test fixture
  common/                    — dispatchers, Result, logging (PII-safe, M1)
feature/
  onboarding/                — sign-in (Credential Manager), household setup, device linking
  lock/                      — parent PIN/biometric gate, kid-mode exit gate
  parent-home/               — dashboard, charts, AI summary
  chores/                    — parent CRUD, kid today (3 tiers), submission/approval
  wallet/                    — balances (3 tiers), savings goals, allowance, shop
  games/                     — 3 games, tier difficulty, scoring → ledger events
  chat/                      — E2EE threads, kindness check, presets/voice for Seedling
  timebank/                  — kid time bank timer
  settings/                  — kids & tiers, devices, PIN, data export, delete household
functions/                   — Cloud Functions (TypeScript): link-device, delete-household, export, interest cron guard (M3)
firestore/                   — security rules + rules tests (M3)
widget/                      — Glance widget: kid balance + chores due
benchmark/                   — Macrobenchmark + Baseline Profile generator
docs/adr/                    — architecture decision records
```

The reasoning behind this layout is in [ADR-0001](docs/adr/0001-architecture.md).

## Security and privacy

- **Tamper-evident ledger.** Every ledger event is signed by the authoring device and chained to that author's previous event by hash. Receivers verify signature and chain before applying; failures are quarantined and shown in the parent audit view. See [ADR-0002](docs/adr/0002-ledger-as-events.md).
- **End-to-end encrypted chat and photos.** Per-thread AES-256-GCM keys derived from X25519 agreement between parent and kid devices. Firestore and Storage hold ciphertext and routing metadata only. Keys rotate when a device is removed.
- **Keystore-backed keys.** Each device holds a P-256 key pair in Android Keystore (StrongBox where available). Kid devices get their own Firebase identity; they never see parent credentials.
- **Server-side enforcement.** Firestore rules check household membership, author identity, role, registered device, and ledger immutability. App Check with Play Integrity rejects repackaged or scripted clients.
- **Encrypted at rest.** Room runs on SQLCipher with a Keystore-wrapped passphrase. The parent PIN is hashed with Argon2id (PBKDF2 fallback) and protected by exponential lockout. Parent and chat screens use `FLAG_SECURE`.
- **No analytics, no ads.** No third-party analytics SDKs. Crashlytics is opt-in with PII scrubbing. Kids are a first name, an avatar, and a birth month and year.

The full design is in [ADR-0003](docs/adr/0003-security-model.md) and the [threat model](docs/THREAT_MODEL.md).

## On-device AI

All AI runs locally through an `AiGateway` interface. The primary implementation uses ML Kit GenAI APIs on Gemini Nano where the device supports it; a rules-based fallback keeps every feature working on every device. Uses:

- **Kindness check:** before a kid sends a message, the plaintext is checked on device and an unkind message gets a gentle nudge. It never blocks and never reports.
- **Weekly parent summary** of chores, earnings, and spending.
- **Chore suggestions** by age band.
- **Receipt to manual adjustment:** prefill an adjustment from a photographed receipt.

No data leaves the device for AI in v1, and the privacy policy says so.

## Build and run

Requirements: JDK 17 (enough for Gradle 9.8.2, AGP 9.4.1, and Kotlin 2.4.21) and the Android SDK with the API 37 platform. The app targets API 37 and runs on Android 9 (API 28, minSdk) and later. Use the Gradle wrapper; no global Gradle install is needed.

```
./gradlew build                 # compile everything
./gradlew test                  # JVM unit tests
./gradlew lint detekt           # static analysis
./gradlew :app:installDebug     # install on a running emulator or device
```

From M3 onward the app needs a Firebase project. Place your own `app/google-services.json` locally; it is not committed, and CI injects it from a repository secret. Keystores, service-account files, and `local.properties` are never committed either.

## Roadmap

Each milestone ends with green CI and a tagged build.

| Milestone | Scope | Status |
|---|---|---|
| M0 Scaffold | Build logic, version catalog, module skeleton, Hilt, theme and tier tokens, CI, Renovate, ADRs, threat model | In progress |
| M1 Ledger core and crypto | Event-sourced ledger with hash chain, Keystore keys and signing, SQLCipher Room, outbox, audit log, wallet (3 tiers) | Done |
| M2 Chores, rewards, tiers | Parent CRUD, kid today and shop (3 tiers), submit/approve, allowance, savings and interest, PIN/biometric lock | Planned |
| M3 Sync and identity | Sync engine, Firebase transport, App Check, Credential Manager, Cloud Functions, Firestore rules and emulator tests, device registry, controls matrix | Planned |
| M4 Chat and time bank | E2EE threads, key rotation, Seedling presets and voice notes, time bank with foreground notification | Planned |
| M5 Games | Three games, tier difficulty, daily cap, signed scoring events | Planned |
| M6 AI | Gemini Nano integration, fallbacks, kindness check, suggestions, weekly summary | Planned |
| M7 Showcase polish | Glance widget, adaptive layouts, Baseline Profiles, full screenshot coverage, accessibility, retention, export/delete | Planned |
| M8 Play release | Privacy policy, data-safety form, security review, app signing, staged tracks | Planned |

## Documentation

- [Architecture decision records](docs/adr/README.md)
  - [ADR-0001 Architecture](docs/adr/0001-architecture.md)
  - [ADR-0002 Ledger as events](docs/adr/0002-ledger-as-events.md)
  - [ADR-0003 Security model](docs/adr/0003-security-model.md)
- [Threat model](docs/THREAT_MODEL.md)

## Engineering practices

- **Convention plugins** in `build-logic/` configure Android, Compose, Hilt, testing, and lint once; modules apply them instead of repeating configuration.
- **Dependency verification** with SHA-256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP signature verification; trust on first use). OWASP Dependency-Check runs in a separate workflow on a weekly schedule and on manual dispatch, not in the main CI.
- **Renovate** with grouped update PRs against a single version catalog; a workflow refreshes the verification metadata on Renovate branches.
- **Screenshot tests** with Roborazzi for every kid screen in every tier, diffed in CI (M7; Roborazzi and Robolectric are in the version catalog, no screenshot tests yet).
- **Baseline Profiles and Macrobenchmark** for startup and scroll performance, with results recorded here (M7; none recorded yet).
- **CI** on GitHub Actions (pinned by SHA): build, unit tests, Android Lint, Detekt, ktlint. Firestore rules tests join in M3. Secret scanning is a GitHub repository setting (enable in repo settings; not yet enabled). Branch protection, also a repository setting, requires CI to pass.

## Licence

MIT. Copyright (c) 2026 Kiril Petriv.
