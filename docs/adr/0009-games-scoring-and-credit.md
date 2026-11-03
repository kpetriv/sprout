# ADR-0009: Games scoring and credit

- **Status:** Accepted
- **Date:** 2026-11-03
- **Milestone:** Decided and implemented in M5 (`core/games`, `core/domain-ledger`, `core/database`, `core/sync`, `core/sync-firebase`)
- **Related:** [ADR-0002](0002-ledger-as-events.md) (ledger as events, daily cap: reject, never clamp), [ADR-0004](0004-parent-authorisation-and-device-identity.md) (device signatures), [ADR-0006](0006-sync-protocol-and-document-envelope.md) (sync protocol), [M5 spec](../milestones/M5.md), decisions D-M5-2 to D-M5-5 and D-M5-7, threat model T4, T12

## Context

Brain games (arithmetic, pattern memory, word scramble) let a kid earn small credits. A play ends in one `GamePlayed` ledger event (`gameId`, `score`, `playedOn`, `amount`) that the **kid's own device** writes and signs. No parent approves it, and no server sees the play. So the ledger cannot check that the kid really answered eight questions right. A rooted kid device can sign any event its key will sign.

Before M5 the only limit was the daily cap (`LedgerPolicy.dailyGameCap`, 50 minor units per kid, date and currency). A game over the cap was rejected locally and excluded from merged streams, never clamped (ADR-0002). Two gaps were left:

- **The amount was trusted.** Any `GamePlayed` up to the cap was valid, so one forged event could claim the whole day's 50.
- **`playedOn` was author-declared** (T12). The cap is counted per `playedOn`, so a device could date plays onto past days and use their unused cap.

The game logic also had to live somewhere every device could run, so that every receiver can re-derive the credit.

## Decision

### 1. A pure `core/games` module

Game generation, scoring and credit are pure Kotlin in `core/games`: no Android, no coroutines, no I/O, and deterministic from a seed (D-M5-2). `GameId` is `<kind>.<difficulty>` (`arithmetic.sprout`), `GameId.parse` accepts only the eight playable ids (Seedling has no word scramble, D-M5-6), and `GameCredit` is the only place a score becomes credit: 1 per correct round, +3 for a perfect play, +2 for the Sapling timed bonus, at most 15 per play. `core/domain-ledger` depends on it, which keeps the domain KMP-ready.

### 2. Every receiver re-derives the credit (D-M5-3)

`LedgerRules` gains static rules for `GamePlayed`, applied when the event is authored, when it is ingested and when merged streams are projected:

- `gameId` must parse as a playable `GameId` (`Violation.UnknownGame`; the id is quoted only when it looks like an id, since an author-written string may hold anything);
- `score` must be in `0..rounds(gameId)` (`Violation.ScoreOutOfRange`);
- `amount ≤ GameCredit.earned(gameId, score)` (`Violation.GameCreditTooHigh`). The event carries no timed flag, so the bound assumes the timed bonus wherever it was possible (a perfect Sapling play may claim 15).

Because they are static rules, a pulled event that breaks them is quarantined on ingest (D-M3-25) and kept as evidence, and a projection excludes it as `InvalidEvent`. Every device decides the same way from the event alone.

### 3. `≤`, not `=`: the author clamps to the cap

The rule allows **less** than earned. `PlayGameUseCase` reads today's remaining cap from the projection and appends `min(earned, remaining)`. When nothing remains it appends nothing and returns `CapReached`, and the kid can still play "for fun" (D-M5-7). This is not the clamping ADR-0002 forbids: the projection still excludes a game that goes over the cap whole. The *author* chooses a smaller amount before signing, and every receiver sees exactly what was signed.

### 4. The `playedOn` window (D-M5-5)

A `GamePlayed` must be dated within **7 days before to 1 day after** the date it reached the household (`PlayedOnWindow`):

- **Receivers** judge against the UTC date of the backend's `serverReceivedAt` stamp. The Firestore rules require it to be `request.time`, so the client cannot set it, and every receiver sees the same value for the same stored document. The transport now carries it with each pulled event (`PulledEvent`). The data layer keeps it on the stored row and on a held `ledger_inbox` row (`serverReceivedAtEpochMs`, schema version 7), and every projection is built with these stamps. A pulled game outside the window is **stored**, so its author's chain still links, but the projection **excludes** it as `LedgerAnomaly.PlayedOnOutOfRange` (in the parent's audit view), as it does a game over the cap.
- **The author** checks against its household-local today. `LedgerRules.validate(event, projection, today)` refuses a local game outside the window before it is stored.

The one-day slack ahead also covers the gap between a household's local date and UTC. Offline play for up to a week still syncs, and backdating is bounded to seven days of cap.

Quarantining instead (an earlier draft) was rejected. A verifier quarantine takes the event out of its author's chain, so every later event from that device would wait for good in `ledger_inbox` on every receiver. A tablet that was offline for more than a week would stop syncing.

## Consequences

- One forged play gains at most 15 minor units, and a day at most the daily cap. A rooted kid device can still claim a perfect score for every play. The damage per play and per day is bounded, and the parent sees the plays in the wallet history. **Residual risk, accepted.**
- A game outside the window is applied by the device that wrote it (its own copy has no server stamp) and excluded by every receiver. Until a parent adjusts, the kid device's balance can be higher than everyone else's by that play's amount. This is the same class of author/receiver divergence as D-M3-25. It needs the device to be offline for over a week, or a forged date. **Residual risk, accepted.**
- Rows ingested before schema version 7, and inbox rows held before it, have no stamp and are never date-checked.
- The daily cap stays the device default (D-M5-4). A parent-configurable cap (M7) must be synced household policy, so that every device projects the same exclusions.
- Changing `GameCredit`, the round counts or the window changes how existing events project. Any such change needs a new ADR and a migration plan for existing events, like any other rule change.

## Alternatives considered

- **Trust the amount, cap only.** Simplest. But one event could take the whole day's cap, and receivers could not tell a forged play from a real one.
- **Server-side scoring.** The Spark plan has no Cloud Functions, and the Sprout server holds no ledger state. Moving play to the server would also break offline play.
- **Signed game sessions (seed and answers in the event).** Receivers could replay the session and check every answer. But a rooted device can produce right answers as easily as a right score, so it adds bulk and privacy surface and proves nothing more.
- **Quarantine out-of-window games in the verifier.** This blocks the author's chain on receivers (see §4).
- **Judge the window against the receiving device's clock.** Devices that pull at different times would decide differently, and the ledgers would diverge.
