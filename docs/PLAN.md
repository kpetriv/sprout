# Sprout — Android portfolio app plan

Package: `dev.kpetriv.sprout`. Repo: `/Users/kirilpetriv/Personal/sprout`.

## Context

Kiril wants a Play-Store-ready Android app that demonstrates senior/lead-level engineering: Compose, Coroutines/Flows, multi-device sync, on-device AI, security and privacy by design, and a real numbers domain. Interests: budgeting, planners, parenting (kids of different ages, chores, controlled screen time, parent↔kid chat).

Decisions made:
- **Concept:** a "family economy" app. Kids earn credits via chores and short brain games; credits convert to pocket money or screen time; parent runs budgets, allowances, savings goals, and a ledger. Parent↔kid chat. No device-locking in v1 (avoids Accessibility/UsageStats/overlay policy review).
- **Backend:** Firebase (Auth, Firestore, App Check; as built, encrypted media are Firestore chunks and Cloud Storage is not used, ADR-0008) on the free Spark plan, plus the Sprout server (`server/`, Hono on Vercel, speaking the Firebase callable protocol with the Admin SDK) for the privileged operations, because Spark has no Cloud Functions (D-M3-29, ADR-0007). Sync engine written against a `SyncTransport` interface so the transport can be swapped.
- **App shape:** one app, two roles. Parent area behind PIN/biometric; kid mode on the kid's device, with an age-tiered UI.
- **Play audience:** parents, 18+. Kid mode is a parent-configured feature. Keeps chat and generative AI outside the Families policy regime.
- **Scope:** full showcase — Glance widget, Baseline Profiles + Macrobenchmark, screenshot tests, KMP-ready shared domain.
- **Security bar:** controls that would stand up in a SOC 2 Type I style review (Security, Availability, Confidentiality, Privacy criteria), with end-to-end encryption for chat and photos and a tamper-evident ledger. Rationale: SOC 2 is about demonstrable controls (access, encryption, logging, change management, incident handling), not about hiding all data from the server. Firestore already encrypts at rest on a SOC 2 certified platform; E2EE for the most sensitive data (what a child says and photographs) plus cryptographic integrity for money events gives the strongest story without the key-recovery burden of encrypting everything.

Environment: empty folder `/Users/kirilpetriv/Personal`, Android SDK installed, Android Studio present, JDK 17 (bump to JDK 21 if the chosen AGP requires it). No gradle on PATH — use the wrapper.

## Product scope (v1)

**Parent mode** (PIN/biometric-protected)
- Household setup: add kids (first name, birth month/year → age band, avatar), link a kid device via a short single-use code.
- Chores: one-off or recurring, reward in money and/or minutes, assign to a kid, approve/reject submissions (with photo).
- Allowance: weekly/monthly auto-credit per kid; optional "interest" on savings to teach compounding.
- Dashboard: per-kid balances (money + time), spend by category, savings-goal progress, charts, AI weekly summary.
- Rewards catalogue: things a kid can buy with credits (screen time, treats, outings).
- Chat: one thread per kid. Audit view of all ledger events.
- Per-kid UI tier selection (auto-suggested from age, parent can override).

**Kid mode** — three UI tiers, same data, different presentation (see "Age-tiered kid UI").
- Today: chores due, balance, savings goal.
- Submit a chore as done (optional photo).
- Brain games: mental arithmetic, pattern memory, word scramble; small credits with a daily cap; difficulty follows tier.
- Shop: spend credits on rewards; screen-time purchases go into a visible **time bank** the kid starts/stops (honour system in v1).
- Chat with parent; AI "kindness check" nudges before sending an unkind message (never blocks, never reports).

**Out of v1:** device enforcement, in-app purchases, multiple households per user, iOS.

## Age-tiered kid UI

`Kid.ageBand` (derived from birth month/year) suggests a `KidUiTier`; parent can override per kid. Tier is a value in the kid profile, synced, and read by every kid-mode screen via a `LocalKidTier` composition local. Design system exposes tier-scaled tokens (type scale, touch target size, corner radius, colour saturation) and tier-specific components.

| Tier | Age | Balance & numbers | Chores | Shop | Chat | Games |
|---|---|---|---|---|---|---|
| **Seedling** | ~5–7 | Balance as a jar filling with coins and a single whole number ("7 coins"); minutes as a sun/clock graphic. No decimals, no history list. Big buttons, TTS read-aloud on tap. | Picture cards with one tap "Done!" and confetti; max 3 shown. | Picture tiles; "can I get it?" shown as green/grey, no prices in text. | Preset messages + emoji + voice note only; free text off by default. | Counting, simple add/subtract, memory with pictures. |
| **Sprout** | ~8–11 | Real currency with decimals, savings goal progress bar, simple last-10 history. | List with due dates and reward amounts; can add note + photo. | Prices shown; "days until you can afford it". | Free text with kindness check. | Multiplication, pattern memory, word scramble. |
| **Sapling** | ~12+ | Full ledger with categories and filters, interest shown with explanation, budget vs. spend chart, export CSV. | Can propose a chore and a rate to the parent (parent approves). | Full catalogue, wishlist with savings plan. | Free text, kindness check nudge only. | Harder arithmetic, logic puzzles, optional timed mode. |

Each feature module has one entry composable per screen that switches on tier: `TodayScreen(tier)` → `SeedlingTodayContent`, `SproutTodayContent`, `SaplingTodayContent` sharing the same `UiState`. Screenshot tests run per tier so every state × tier is covered. Lower tiers hide, never break, functionality that higher tiers show.

## Domain model (the numbers core)

Event-sourced ledger in a pure-Kotlin module (KMP-ready).

- `Account` — one per (kid, currency) where currency ∈ {MONEY(minor units, ISO code), MINUTES}; `SavingsGoal` sub-account per kid.
- `LedgerEvent` (append-only, immutable, idempotent by `eventId: UUID`): `ChoreRewarded`, `AllowancePaid`, `GamePlayed`, `RewardPurchased`, `ManualAdjustment`, `TransferToSavings`, `InterestAccrued`, `TimeBankStarted/Stopped`.
- Every event carries `householdId`, `authorUid`, `authorDeviceId`, `createdAt`, `lamport: Long`, `prevHash`, `hash`, `signature` (see Security).
- Balances are projections from folding events; cached in Room for UI, never source of truth.
- Money is `Long` minor units in a `Money` value class; no floating point in the ledger.
- Invariants in the domain: no negative balances, reward > 0, daily game cap, only PARENT role may author `ChoreRewarded`, `AllowancePaid`, `ManualAdjustment`, `InterestAccrued`.

Chores, rewards, kids, chat messages are synced, device-signed documents: tombstones beat live copies, chore submissions resolve by status rank first (`APPROVED` > `REJECTED` > `SUBMITTED`), and everything else is last-writer-wins on `(lamport, authorDeviceId)` (ADR-0006).

## Architecture

Multi-module Gradle project with convention plugins in `build-logic/`. Hilt for DI. Compose-only UI. Navigation: Navigation 3 if stable at scaffold time, else type-safe Navigation-Compose (record in ADR).

```
app/                         — thin shell, Hilt entry, nav graph, role switch, lock screen
build-logic/                 — convention plugins (android-library, compose, hilt, test, security lint)
core/
  domain-ledger/             — pure Kotlin: events, projections, Money, hash chain
  model/                     — shared models (Kid, Chore, Reward, Message, KidUiTier)
  crypto/                    — Keystore-backed device keys, household key, signing, AES-GCM, Tink wrappers
  database/                  — Room (SQLCipher) entities, DAOs, projection cache, outbox, audit log
  sync/                      — JVM module: SyncEngine, DocumentEnvelope, ConflictResolver, SyncTransport interface (FakeSyncTransport in testFixtures)
  sync-firebase/             — Firestore/Auth/AppCheck transport, callable client for the Sprout server
  ai/                        — AiGateway interface + Gemini Nano impl + rules-based fallback
  designsystem/              — theme, tier-scaled tokens, components, previews
  ui/                        — shared composables (charts, balance jar/ring), window-size helpers
  testing/                   — screenshot infra only (Roborazzi rule and options); fakes live in each module's testFixtures (FakeSyncTransport in core/sync, FakeClock in core/time, in-memory key stores in core/crypto, TestHousehold in core/database)
  time/                      — pure Kotlin: Clock over kotlin.time.Instant
  common/                    — dispatchers, Result, logging (PII-safe)
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
server/                      — the Sprout server (TypeScript, Hono on Vercel, firebase-admin): the callables for households, devices, link codes, parent keys, revocation, export and deletion, plus tests against the emulators
firestore/                   — security rules, indexes, rules tests (vitest + emulator)
widget/                      — Glance widget: kid balance + chores due
benchmark/                   — Macrobenchmark + Baseline Profile generator
docs/                        — plan, ADRs (docs/adr/), threat model, controls matrix, runbook, privacy, Firebase backend
```

Each feature exposes `ViewModel` → `StateFlow<UiState>` and consumes repositories from `core/`. Writes go to Room + outbox in one transaction; `SyncEngine` drains the outbox.

## Security and privacy design

Documented in `docs/THREAT_MODEL.md` and `docs/CONTROLS.md` (controls mapped to SOC 2 trust criteria).

**Identity and access**
- Parents sign in with Firebase Auth via Credential Manager (Google + passkeys). Kid devices get their own Firebase Auth identity (anonymous auth, upgraded with custom claims `{hid, role: KID, kidId}` set by the server) — never the parent's credentials.
- Device linking: parent taps "link device" → the server's `createLinkCode` creates an 8-char code (crypto-random, 10-minute TTL, single use, rate-limited per uid and IP, stored as an HMAC). Kid device submits code + its attested device public key → the server validates, registers the key, sets claims. Code is never a URL and never logged.
- Adding a second parent device requires approval on an existing parent device (public key fingerprint comparison and a signed approval; ADR-0007).
- Firestore rules enforce: household membership, `authorUid == request.auth.uid` on every write, role-gated document types (kid cannot write approvals, rewards, allowance, kid profiles), immutable ledger events (no update/delete), schema validation of required fields. Rules tested in CI with the emulator.
- App Check with Play Integrity on every Firebase call, enforced by the server on every callable and, once the owner switches it on before production, in Firestore (M8 release checklist), so requests from repackaged or scripted clients are rejected.

**Anti-spoofing and tamper evidence**
- Each device generates a P-256 key pair in Android Keystore (StrongBox when available, hardware-backed otherwise, `setUserAuthenticationRequired` for parent keys). Public key registered at link time; stored under the household's device list.
- Every ledger event and chat message is signed with the device key; `prevHash`/`hash` per author stream form a hash chain. Receivers verify signature + chain before applying; failures are quarantined and surfaced in the parent audit view.
- Replay protection: `eventId` uniqueness + per-author monotonic lamport; duplicates are no-ops.
- Firestore rules reject writes whose `authorDeviceId` is not in the registered device list for that uid.

**End-to-end encryption (chat + photos)**
- Household chat key: X25519 agreement at link time between parent and kid device keys → HKDF → per-thread AES-256-GCM key; rotated on device removal. Messages and chore photos are encrypted on device; Firestore stores ciphertext (media as encrypted chunks) + routing metadata only.
- Kindness check runs on plaintext on the kid's device before encryption; nothing leaves the device for AI.
- Removing a device rotates keys; removed devices cannot read new messages.

**Data at rest on device**
- Room via SQLCipher, passphrase generated once and wrapped by a Keystore AES key. DataStore secrets encrypted with Tink keyset in Keystore.
- Parent PIN: Argon2id (or PBKDF2-HMAC-SHA256 with high iterations if Argon2 lib is not acceptable) with per-install salt; biometric via `BiometricPrompt` with `CryptoObject`; exponential lockout after failed attempts; auto-lock on background.
- `allowBackup=false`, backup rules exclude DB and keys; `FLAG_SECURE` on parent screens and chat; no screenshots in task switcher for sensitive screens.

**Transport and app hardening**
- Firebase SDKs over TLS; Network Security Config forbids cleartext; no custom HTTP clients in v1.
- R8 full mode, `debuggable=false`, no logging of PII (Timber release tree drops everything below WARN and a lint rule forbids logging models), StrictMode in debug.
- No ads, no third-party analytics in v1. No crash reporting in v1 (Crashlytics was considered and not shipped; adding it needs a data-safety and privacy change, `docs/DATA_SAFETY.md`). Gemini Nano runs on device only.

**Supply chain and change management**
- Gradle dependency verification (`verification-metadata.xml`), Renovate with grouped PRs, OWASP Dependency-Check in CI, GitHub Actions pinned by SHA, branch protection with required CI, signed commits, CODEOWNERS.
- Secrets (`google-services.json`, keystores, Firebase service account) never committed; injected from GitHub Actions secrets. Secret scanning enabled.
- Static analysis: Detekt + ktlint, Android Lint security checks, custom lint for `Log.*` with PII and for missing `FLAG_SECURE`.

**Privacy compliance (GDPR/UK GDPR, COPPA-adjacent)**
- Data minimisation: kids are first name + avatar + birth month/year; no email, no location, no contacts, no advertising ID.
- Lawful basis and consent captured at household creation; parent is the controller of the child's data in-app.
- Right of access/portability: the server's `exportHousehold` returns a JSON bundle (a ledger CSV is derived on device). Right to erasure: `deleteHousehold` removes Firestore docs (encrypted media chunks included), Auth users, and writes a deletion audit record; local wipe on all devices on next sync.
- Retention policy: chat messages optional auto-delete after N days (parent setting); linking codes and audit logs with fixed TTL.
- Privacy policy page + Play data-safety form generated from `docs/PRIVACY.md`; security.txt and a disclosure policy in the repo.

**Availability and operations**
- Offline-first by design; Firestore backups need the Blaze plan (open, CONTROLS AV-7); server error alerting M8; incident runbook in `docs/RUNBOOK.md`.
- In-app audit log (who did what, when, from which device) visible to parents; append-only.

## Sync design

- Local-first: every write lands in Room + an `outbox` row in one transaction; UI never waits on network.
- Push: `SyncWorker` drains outbox in order; idempotent writes keyed by `eventId`/`docId`; retries with backoff; partial-failure safe.
- Pull: Firestore snapshot listeners while foregrounded; periodic WorkManager pull otherwise; remote changes pass signature/chain verification, decryption (chat/photos), then `ConflictResolver`.
- Ledger events never conflict (append-only); documents use LWW + status precedence.
- Lamport clock per device, merged on every pull.
- `FakeSyncTransport` drives multi-device convergence tests on the JVM.

## On-device AI design

- Primary: ML Kit GenAI APIs on Gemini Nano (Summarisation, Proofreading/Rewrite, Prompt API where available). Runtime `AiAvailability` check; model download on Wi-Fi with consent.
- Fallback: rules-based implementations so every feature works on every device.
- Uses: chore suggestions by age band, weekly parent summary, kindness check, receipt → manual adjustment prefill.
- No cloud AI in v1; stated in privacy policy and data-safety form.

## Tech stack

Pin at scaffold time to latest stable: Kotlin 2.x (K2), AGP, Compose BOM, Room (KSP) + SQLCipher, Hilt, WorkManager, Coil, Kotlinx Serialization/Coroutines/DateTime, Credential Manager, Biometric, Tink, Firebase BoM (Auth, Firestore, Storage, Functions client, App Check), Node 22/24 + TypeScript + Hono + firebase-admin for `server/`, ML Kit GenAI, Glance, Macrobenchmark, Baseline Profiles, Roborazzi (screenshot tests), Turbine, MockK/Kotest, Detekt + ktlint, Version Catalog, Gradle config cache, Renovate.

## Milestones (each ends with green CI and a tagged build)

1. **M0 Scaffold** — repo, `build-logic`, version catalog, module skeleton, Hilt, theme + tier tokens, CI (build, unit tests, lint, Detekt, dependency verification, dependency-check), Renovate, README, ADR-001 architecture, ADR-002 ledger as events, `docs/THREAT_MODEL.md` first draft.
2. **M1 Ledger core + crypto** — `core/domain-ledger` with hash chain and property-style tests; `core/crypto` (Keystore keys, signing, AES-GCM, PIN hashing) with tests; Room (SQLCipher) schema, outbox, audit log; wallet screen (all 3 tiers) from local events; manual adjustment.
3. **M2 Chores + rewards + tiers** — parent CRUD, kid today (3 tiers), submit/approve, rewards shop (3 tiers), allowance scheduler, savings goals + interest, parent lock screen with PIN/biometric.
4. **M3 Sync + identity** — `core/sync` (JVM), Firebase transport, App Check, Credential Manager sign-in, the Sprout server on Vercel (households, devices, link codes, parent keys, revocation, export, delete; replaces Cloud Functions, D-M3-29), Firestore rules + emulator tests, device registry with key attestation, signature/chain verification on pull with held and forked handling, multi-device JVM convergence tests, ADR-0006/0007, `docs/CONTROLS.md`, `docs/RUNBOOK.md`. Spec and as-built notes: `docs/milestones/M3.md`.
5. **M4 Chat (E2EE) + time bank** — key agreement, encrypted threads, Seedling presets/voice notes, key rotation on device removal, time bank timer with foreground notification.
6. **M5 Games** — three games, tier difficulty, daily cap, scoring → signed ledger events.
7. **M6 AI** — `core/ai`, Gemini Nano integration, fallbacks, kindness check, chore suggestions, weekly summary.
8. **M7 Showcase polish** — Glance widget, adaptive layouts, Baseline Profiles + Macrobenchmark, screenshot tests for every screen × tier × state, accessibility pass, `FLAG_SECURE` audit, retention settings, export/delete flows in settings.
9. **M8 Play release** — privacy policy page, data-safety form, security review checklist (including a network capture on a real device of the AI paths and a receipt scan, D-M6-51), app signing, internal → closed → production tracks; store screenshots from screenshot tests.

## Verification

- Unit: ledger projections and hash chain, signature verify/reject, conflict resolver, allowance scheduler, PIN lockout, game scoring (`./gradlew test`).
- Multi-device sync: JVM test with two in-memory DBs + `FakeSyncTransport`; interleaved offline writes converge; a forged event (bad signature or wrong author) is quarantined.
- Firestore rules: emulator tests covering kid-cannot-approve, cross-household read denied, ledger immutable, unregistered device rejected.
- E2EE: test that the transport layer only ever sees ciphertext for chat/photos; removed device cannot decrypt post-rotation messages.
- UI: Compose tests for submit/approve and purchase; Roborazzi screenshots per tier diffed in CI.
- Security tooling in CI: Detekt, Android Lint security, dependency verification, OWASP Dependency-Check, secret scanning.
- Performance: Macrobenchmark startup with/without Baseline Profile, recorded in README.
- Manual: two emulators (parent + kid), airplane mode then reconnect, ledger equality; Gemini Nano path on a supported Pixel, fallback on emulator; tier switch on a kid profile re-renders every kid screen.

## Working model: Fable coordinator, Sonnet/Opus workers

This session stays on Fable 5.1 as coordinator and quality gate. Implementation work is delegated to subagents on cheaper models at high effort. Set up in M0 as project files inside the repo so every future session inherits it.

**`sprout/.claude/settings.json`** (project-scoped, committed):
```json
{
  "model": "fable",
  "effortLevel": "high",
  "env": {
    "CLAUDE_CODE_SUBAGENT_MODEL": "opus",
    "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE": "60"
  },
  "modelSettings": {
    "claude-opus-5-5": { "effortLevel": "high" }
  }
}
```
- `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=60` compacts at 60% of the auto-compact window; applies to the main session and subagents. (Docs do not state the base for 1M-window models; verify with `/context` after setting, or use `CLAUDE_CODE_AUTO_COMPACT_WINDOW` for an absolute token value.)
- `effortLevel: high` is the floor for models with no saved level. Opus 5.5 defaults to medium, so it gets an explicit `high` under `modelSettings`. Fable 5.1 default is already high.
- `CLAUDE_CODE_SUBAGENT_MODEL=opus` makes Opus 5.5 the default for any subagent without an explicit model, so delegated work never silently runs on Fable. The Agent tool's `model` parameter and agent frontmatter override it per call/agent (e.g. Sonnet for cheap read-only exploration).
- Put the same `env` block in `~/.claude/settings.json` if you want it in every project.

**`sprout/.claude/agents/`** (committed):
| Agent | model | effort | Role |
|---|---|---|---|
| `implementer.md` | opus | high | Builds one milestone slice from a brief; runs tests; reports diff summary. Tools: all. |
| `reviewer.md` | opus | high | Reviews a diff for correctness, security (threat model), Compose/coroutine misuse. Read-only tools + Bash for tests. |
| `explorer.md` | sonnet | high | Reads code/docs, answers questions, finds patterns. Read-only. |
| `security-auditor.md` | opus | xhigh | Checks changes against `docs/THREAT_MODEL.md` and `docs/CONTROLS.md`; rules tests; crypto usage. Read-only + Bash. |

**`sprout/CLAUDE.md`** delegation rules (so any coordinator session follows them):
- Coordinator (Fable) plans, writes briefs, reviews, and merges; it does not write feature code itself except one-line fixes.
- One implementer per module set at a time; never two writers on the same files.
- Every implementer result goes through `reviewer`; anything touching `core/crypto`, `core/sync`, `core/sync-firebase`, `firestore/`, `server/`, or `feature/lock` also goes through `security-auditor` before merge.
- Briefs name the module, the done condition, and the tests that must pass; results come back as a diff summary plus test output, not file dumps.
- Commits per milestone slice on a feature branch; PRs reviewed in-session before merge.

## Play policy notes

- No Accessibility, UsageStats, overlay, or device-admin permissions in v1.
- Target audience 18+; kid mode is a parent feature; no child accounts; kid mode collects first name/avatar/birth month only, chosen by the parent.
- No camera permission: a chore photo is taken with the phone's camera app (`ActivityResultContracts.TakePicture`), and chat photos come from the system photo picker. Photos and voice notes are E2EE and stored as encrypted chunks in Firestore under the household (no Cloud Storage).
- Permissions are checked against the Play data-safety answers in CI (`docs/DATA_SAFETY.md`, `tools/privacy/inventory.py`); the privacy policy is published from `docs/PRIVACY.md` (M8).
- Device enforcement (v2, if ever) goes in an isolated module behind a feature flag and gets its own policy review.
