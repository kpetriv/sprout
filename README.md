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
  sync/                      — JVM: SyncEngine, DocumentEnvelope, ConflictResolver, SyncTransport interface
  sync-firebase/             — Firestore/Auth/AppCheck transport, client for the Sprout server
  ai/                        — AiGateway interface + Gemini Nano impl + rules-based fallback
  designsystem/              — theme, tier-scaled tokens, components, previews
  ui/                        — shared composables (charts, balance jar/ring), window-size helpers
  testing/                   — screenshot infra (fakes live in each module's testFixtures)
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
server/                      — the Sprout server (TypeScript, Hono on Vercel): households, devices, link codes, parent keys, revocation, export, delete
firestore/                   — security rules, indexes, rules tests
widget/                      — Glance widget: kid balance + chores due
benchmark/                   — Macrobenchmark + Baseline Profile generator
docs/adr/                    — architecture decision records
```

The reasoning behind this layout is in [ADR-0001](docs/adr/0001-architecture.md).

## Security and privacy

- **Tamper-evident ledger.** Every ledger event is signed by the authoring device and chained to that author's previous event by hash. Receivers verify signature and chain before applying; failures are quarantined and shown in the parent audit view. See [ADR-0002](docs/adr/0002-ledger-as-events.md).
- **Parent authorisation.** The parent area sits behind a PIN, and every money event a parent authors (chore rewards, allowance, interest, adjustments) is also signed with a parent key that only a strong biometric can use (on devices that have one), one fingerprint prompt per event, each naming the kid, the amount and what it pays for. The key is created only after the parent enters the PIN, so knowing the phone's screen lock is not enough to award credits. Scheduled allowance and interest wait for the parent to tap "Sign now". See [ADR-0004](docs/adr/0004-parent-authorisation-and-device-identity.md) and [ADR-0005](docs/adr/0005-scheduled-payments-and-money-flow-atomicity.md).
- **End-to-end encrypted chat and photos.** Per-thread AES-256-GCM keys derived from X25519 agreement between parent and kid devices. Firestore and Storage hold ciphertext and routing metadata only. Keys rotate when a device is removed.
- **Keystore-backed keys.** Each device holds a P-256 key pair in Android Keystore (StrongBox where available). Kid devices get their own Firebase identity; they never see parent credentials.
- **Server-side enforcement.** Firestore rules check household membership, author identity, role, an active registered device, the conflict order, and ledger immutability. Identity changes (households, device registration and approval, link codes, parent keys, revocation, export, deletion) go through the Sprout server, which verifies key attestation, device-signed requests and App Check, and rate-limits every call. See [ADR-0006](docs/adr/0006-sync-protocol-and-document-envelope.md) and [ADR-0007](docs/adr/0007-identity-linking-and-device-registry.md).
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

### Backend

The backend is Firebase (Auth and Firestore, on the free Spark plan) plus the Sprout server in `server/`: a small TypeScript service (Hono, `firebase-admin`) on Vercel that speaks the Firebase callable protocol, because Spark has no Cloud Functions. Debug builds talk to the **Firebase Emulator Suite** (project `demo-sprout`, which cannot reach any real project) and to the server running locally. Requirements: Node 24 and Java 21 for the emulators. From the repository root, in two terminals:

```
npm --prefix firestore ci && npm --prefix server ci
npx --no-install --prefix firestore firebase emulators:start --only auth,firestore --project demo-sprout
npm --prefix server run dev        # http://127.0.0.1:8787, reached from an Android emulator as 10.0.2.2:8787
```

Backend tests: `cd firestore && npm test` (rules) and `cd server && npm test` (every callable over HTTP against the emulators). Production setup, deployment and the rules summary are in [docs/FIREBASE.md](docs/FIREBASE.md). A release build needs your own `app/google-services.json`; it is not committed. Keystores, service-account files, `.env` files and `local.properties` are never committed either.

## Roadmap

Each milestone ends with green CI and a tagged build.

| Milestone | Scope | Status |
|---|---|---|
| M0 Scaffold | Build logic, version catalog, module skeleton, Hilt, theme and tier tokens, CI, Renovate, ADRs, threat model | In progress |
| M1 Ledger core and crypto | Event-sourced ledger with hash chain, Keystore keys and signing, SQLCipher Room, outbox, audit log, wallet (3 tiers) | Done |
| M2 Chores, rewards, tiers | Parent CRUD, kid today and shop (3 tiers), submit/approve, allowance, savings and interest, PIN/biometric lock with fingerprint-signed money events | Done |
| M3 Sync and identity | Sync engine, Firebase transport, App Check, Credential Manager, the Sprout server (Spark plan), Firestore rules and emulator tests, device registry with key attestation, controls matrix, runbook | Done |
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
  - [ADR-0004 Parent authorisation and device identity](docs/adr/0004-parent-authorisation-and-device-identity.md)
  - [ADR-0005 Scheduled payments and money-flow atomicity](docs/adr/0005-scheduled-payments-and-money-flow-atomicity.md)
  - [ADR-0006 Sync protocol and document envelope](docs/adr/0006-sync-protocol-and-document-envelope.md)
  - [ADR-0007 Identity, linking and the device registry](docs/adr/0007-identity-linking-and-device-registry.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Controls matrix (SOC 2 criteria)](docs/CONTROLS.md)
- [Incident runbook](docs/RUNBOOK.md)
- [Firebase backend and the Sprout server](docs/FIREBASE.md)
- [Privacy policy draft](docs/PRIVACY.md)
- [M3 milestone spec and as-built notes](docs/milestones/M3.md)

## Engineering practices

- **Convention plugins** in `build-logic/` configure Android, Compose, Hilt, testing, and lint once; modules apply them instead of repeating configuration.
- **Dependency verification** with SHA-256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP signature verification; trust on first use). OWASP Dependency-Check runs in a separate workflow on a weekly schedule and on manual dispatch, not in the main CI.
- **Renovate** with grouped update PRs against a single version catalog; a workflow refreshes the verification metadata on Renovate branches.
- **Screenshot tests** with Roborazzi for every kid screen in every tier, diffed in CI (M7; Roborazzi and Robolectric are in the version catalog, no screenshot tests yet).
- **Baseline Profiles and Macrobenchmark** for startup and scroll performance, with results recorded here (M7; none recorded yet).
- **CI** on GitHub Actions (pinned by SHA): build, unit and screenshot tests, Android Lint, Detekt, ktlint; a `backend` job runs the Firestore rules and server tests against the emulators (not a required check yet). Secret scanning with push protection is enabled. A branch ruleset on `main` requires pull requests and the build check, and blocks force pushes and deletion.

## Licence

MIT. Copyright (c) 2026 Kiril Petriv.
