# ADR-0001: Multi-module, offline-first architecture

- **Status:** Accepted
- **Date:** 2026-11-02
- **Milestone:** M0

## Context

Sprout is a single Android app with two roles (parent and kid), three age-tiered kid UIs, multi-device sync, on-device AI, and a security bar that has to survive a SOC 2 Type I style review. It is also a portfolio piece, so the structure itself is part of what is being evaluated: build times, test isolation, and how clearly responsibilities are separated all matter.

Forces:

- Several independent feature areas (chores, wallet, games, chat, time bank, settings) that should be buildable and testable in isolation.
- A money domain whose correctness must be provable with fast JVM tests and that may later be shared with iOS or a server.
- Devices that are often offline (kids' tablets, phones in school bags). The UI must never wait on the network.
- Firebase is the chosen backend, but the sync logic and its tests should not depend on Firebase SDKs.
- One developer, with AI agents doing scoped implementation work. Module boundaries need to be enforceable by the build, not by convention alone.

## Decision

**Multi-module Gradle build with convention plugins.** Modules are split into `app/` (thin shell), `core/*` (domain, data, crypto, sync, AI, design system, shared UI, test fixtures), and `feature/*` (one per screen area). Android, Compose, Hilt, test, and lint configuration live in `build-logic/` as convention plugins; a module's build file states what it is (`sprout.android.feature`, `sprout.jvm.library`) and its dependencies, nothing else. Versions live in one version catalog.

**Feature modules never depend on each other.** Cross-feature navigation goes through navigation keys owned by `app/`. Shared UI goes to `core/ui` or `core/designsystem`; shared logic goes to a `core` module. This keeps the dependency graph shallow, so a change in `feature/chat` never recompiles `feature/wallet`.

**Compose-only UI.** No XML layouts or Fragments. Kid screens take a `KidUiTier` and switch content on it; tier-scaled tokens (type scale multiplier, minimum touch target, corner radius) come from the design system through a composition local.

**Hilt for dependency injection.** Standard on Android, compile-time validated, and well supported by WorkManager and ViewModel integrations.

**Single `StateFlow<UiState>` per ViewModel.** Each ViewModel exposes one immutable `UiState` and accepts input through one `onEvent(event)` function. One-off effects (navigation, snackbars) are modelled as state that the UI consumes and acknowledges, not as a separate channel. This gives a unidirectional data flow without a framework.

**Pure-Kotlin domain modules, KMP-ready.** `core/domain-ledger`, `core/model`, and `core/time` depend only on the Kotlin standard library and kotlinx (serialization, datetime, coroutines). No `android.*` imports and no Android Gradle plugin; they apply `sprout.jvm.library`. `core/time` holds the `Clock` interface over `kotlin.time.Instant`, with `FakeClock` as a test fixture of that module, so domain code can read time without depending on `java.time` or Android. Converting these modules to Kotlin Multiplatform later is a build-file change, not a rewrite.

**Offline-first with Room and an outbox.** Every write goes to Room and an `outbox` row in the same transaction. Repositories expose reads as `Flow` from Room. A `SyncEngine` in `core/sync` drains the outbox with retries and backoff, and applies remote changes after verification and conflict resolution.

**Firebase behind a `SyncTransport` interface.** `core/sync` defines `SyncTransport` (push, pull, subscribe). `core/sync-firebase` is the only module that imports Firebase SDKs. Tests use `FakeSyncTransport` from `core/testing` to run multi-device convergence scenarios on the JVM.

**Navigation 3.** `androidx.navigation3` 1.2.0: `NavDisplay` renders a back stack created with `rememberNavBackStack`, destinations are `@Serializable` `NavKey` entries, and each entry gets its own ViewModel scope through `lifecycle-viewmodel-navigation3`. Rationale: the back stack is plain state the app owns, which fits the single-`StateFlow` model and makes the role switch and lock screen ordinary stack edits instead of graph tricks. Type-safe Navigation Compose was the fallback and is not used.

**SDK levels: minSdk 28, compileSdk and targetSdk 37.** API 28 is the floor because it brings hardware-backed Keystore with StrongBox and the `BiometricPrompt` API, which the security model (ADR-0003) relies on for device keys and the parent gate. The toolchain is Gradle 9.8.2, AGP 9.5.0, and Kotlin 2.4.21; JDK 17 is sufficient to build.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Single module** | Simplest to start, but nothing stops the wallet screen from importing Firestore or the ledger from importing Android. Build times grow with every feature, and boundaries rely on review discipline alone. The project's purpose is to demonstrate structure that scales. |
| **MVI library (Orbit, Mavericks, Circuit)** | Adds a framework vocabulary for a pattern that `StateFlow` plus one `onEvent` function already covers. Each library brings its own testing idioms and upgrade cadence. Plain ViewModels keep the code readable to any Android engineer and leave room to adopt one later per feature. |
| **Kotlin Multiplatform from day one** | iOS is out of scope for v1. KMP now would add build complexity (source sets, expect/actual for crypto and storage, slower CI) for no user-visible gain. Keeping the domain pure Kotlin preserves the option at near-zero cost. |
| **Own backend (Ktor or Spring with Postgres)** | Full control, but it means operating auth, push, storage, backups, and availability alone, and attaching a SOC 2 story to self-run infrastructure. Firebase provides these on a certified platform. The `SyncTransport` seam keeps a migration possible. |

## Consequences

**Easier**

- Fast, focused tests: the ledger and sync logic run on the JVM in milliseconds with no emulator.
- Parallel work: an implementer can own one feature module without touching another's files.
- Enforced boundaries: an illegal import fails the build, not the review.
- The UI works fully offline, and sync failures never block the user.

**Harder**

- More modules mean more build files and more Gradle configuration time. Convention plugins and the configuration cache keep this in check; module count is reviewed if configuration time regresses.
- The outbox adds a second write path to reason about: ordering, retries, and idempotency must be tested explicitly (see ADR-0002 for `eventId` idempotency).
- Firestore features that bypass the transport (offline persistence, real-time queries straight into the UI) are deliberately unused. Firestore offline persistence is disabled in favour of Room as the single local source.
- Feature-to-feature flows (for example, from a chore approval to the wallet) need explicit routes in `app/`.

**To watch**

- `core/ui` and `core/common` becoming dumping grounds. Anything feature-specific stays in the feature.
- Hilt graph size and kapt/KSP cost as modules grow.
