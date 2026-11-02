# Sprout

Android portfolio app: a "family economy" where kids earn credits through chores and brain games, spend them on pocket money or screen time, and parents run budgets, allowances, and a tamper-evident ledger. One app, two roles (parent behind PIN/biometric, kid with an age-tiered UI), Firebase sync, on-device AI, E2EE chat.

Package `dev.kpetriv.sprout`. Full plan, architecture, security design and milestones: `docs/PLAN.md`. Decisions: `docs/adr/`. Threat model: `docs/THREAT_MODEL.md`.

## Commands

```
./gradlew build                 # compile everything
./gradlew test                  # JVM unit tests
./gradlew lint detekt           # static analysis
./gradlew :app:installDebug     # install on a running emulator
```

## Module layout

See the tree in `docs/PLAN.md`. Short version: `app/` is a thin shell; `core/*` holds domain, data, crypto, sync, AI, design system; `feature/*` are screen-level modules that never depend on each other; `functions/` and `firestore/` are the backend; `widget/` and `benchmark/` are showcase extras.

## Conventions

- Kotlin, Compose-only UI, Coroutines/Flows, Hilt. Convention plugins live in `build-logic/`; new modules apply them rather than configuring Android directly.
- `core/domain-ledger` and `core/model` are pure Kotlin with no Android or Gradle-Android dependencies (KMP-ready).
- Money is `Long` minor units in the `Money` value class. No `Double`/`Float` for amounts anywhere.
- Repositories expose reads as `Flow`; writes go to Room and the outbox in one transaction; nothing talks to Firebase except `core/sync-firebase`.
- ViewModels expose a single `StateFlow<UiState>` and take events through one `onEvent` function.
- Kid-mode screens take a `KidUiTier` (Seedling, Sprout, Sapling) and switch content on it; every tier gets a screenshot test.
- Never log personal data. Timber only; release tree drops everything below WARN.
- Never commit `google-services.json`, keystores, service-account files, or `local.properties`.

## Working model (coordinator + subagents)

- The main session (Fable 5.1) is the coordinator: it plans, writes briefs, reviews, and commits. It does not write feature code itself beyond one-line fixes.
- Implementation goes to the `implementer` agent (Opus, high effort), one implementer per module set at a time. Two writers never touch the same files.
- Every implementer result goes through `reviewer`. Anything touching `core/crypto`, `core/sync`, `core/sync-firebase`, `firestore/`, `functions/`, or `feature/lock` also goes through `security-auditor` before commit.
- `explorer` (Sonnet) answers read-only questions and locates patterns before a brief is written.
- A brief names the module(s), the done condition, and the tests that must pass. Results come back as a diff summary plus test output, not file dumps.
- Work lands in commits per milestone slice on a feature branch; the coordinator verifies the build before committing.
