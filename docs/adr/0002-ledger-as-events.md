# ADR-0002: Ledger as a signed, append-only event stream

- **Status:** Accepted
- **Date:** 2026-11-02
- **Milestone:** M0 (implemented in M1)

## Context

The ledger records every movement of money and screen-time minutes in a household. It has to:

- be correct to the minor unit, with no rounding drift over years of allowances and interest;
- survive several devices writing while offline and then syncing in any order;
- show parents exactly who changed what, when, and from which device;
- make tampering detectable, including tampering by a kid with a rooted device or by someone with access to the backend.

A conventional "balance column" design fails most of these. Concurrent offline edits to a balance either conflict or silently lose updates, and the history of how a balance came to be is lost unless it is logged separately.

## Decision

**The ledger is an append-only stream of immutable `LedgerEvent`s.** Event types:

| Event | Author role | Effect |
|---|---|---|
| `ChoreRewarded` | Parent | Credit money and/or minutes for an approved chore |
| `AllowancePaid` | Parent | Scheduled credit |
| `GamePlayed` | Kid | Small credit, subject to a daily cap |
| `RewardPurchased` | Kid | Debit for a catalogue item |
| `ManualAdjustment` | Parent | Correction or receipt-based entry |
| `TransferToSavings` | Parent or kid | Move between main account and savings goal |
| `InterestAccrued` | Parent | Interest on savings |
| `TimeBankStarted` / `TimeBankStopped` | Kid | Spend minutes from the time bank |

Events are never updated or deleted. A mistake is corrected by a new compensating event.

**Accounts.** One account per (kid, currency), where currency is `Currency.Fiat(isoCode, fractionDigits)` (an ISO 4217 code and its number of minor-unit digits) or `Currency.Minutes`, plus a `SavingsGoal` sub-account per kid.

**Balances are projections.** A balance is the result of folding the event stream for an account. Projections are cached in Room for the UI and rebuilt from events when needed. The cache is never the source of truth and is never synced.

**Money is `Long` minor units.** `Money` is `@JvmInline value class Money(val minorUnits: Long)`: an amount only, with no currency inside. The currency belongs to the `Account` (and the `Balance` projected for it), so the same `Money` means pence in a GBP account and minutes in a minutes account. Arithmetic on `Money` is exact. The type does not stop two amounts in different currencies from being added; that check happens at the account and projection level, which rejects any event whose currency does not match the account it targets. Interest uses integer arithmetic with an explicit, tested rounding rule (round half-even to the minor unit). `Double` and `Float` are never used for amounts; this is a convention enforced in code review, not by a lint rule.

**Envelope fields.** Every event carries:

| Field | Purpose |
|---|---|
| `eventId: UUID` | Identity and idempotency key |
| `householdId` | Scope; enforced by Firestore rules |
| `authorUid`, `authorDeviceId` | Who wrote it and from which registered device |
| `createdAt` | Wall-clock time for display only; not used for ordering |
| `lamport: Long` | Logical clock for ordering |
| `prevHash`, `hash` | Per-author hash chain |
| `signature` | ECDSA P-256 over the canonical encoding, by the device key (ADR-0003) |

**Idempotency by `eventId`.** Applying an event whose `eventId` is already present is a no-op. This makes outbox retries and duplicate pulls safe.

**Lamport ordering.** Each device keeps a Lamport counter, incremented on every local event and merged (`max(local, remote) + 1`) on every pull. Total order for folding is `(lamport, authorDeviceId, eventId)`, which is deterministic on every device. `createdAt` is never trusted for order because device clocks are wrong, and a kid can change theirs.

**Per-author hash chain.** For each `authorDeviceId`, `hash = SHA-256(canonicalEncoding(event without hash and signature) || prevHash)`, and `prevHash` is the previous event's `hash` from the same author (a fixed genesis value for the first). A per-author chain rather than a global one means devices never need to agree on a single head while offline, yet any gap, reorder, or edit within one author's history breaks the chain. Events that fail signature or chain verification are quarantined, excluded from projections, and shown in the parent audit view.

**Domain invariants**, enforced in `core/domain-ledger` and mirrored where possible in Firestore rules:

1. No negative balances: a debit that would take an account below zero is rejected.
2. Rewards and credits are strictly positive (`reward > 0`).
3. `GamePlayed` credits respect a per-kid daily cap.
4. Only the `PARENT` role may author `ChoreRewarded`, `AllowancePaid`, `ManualAdjustment`, and `InterestAccrued`.
5. `eventId` is unique; lamport is strictly increasing per author.
6. Events are immutable once written.

The module is pure Kotlin (ADR-0001) and tested with property-style tests: arbitrary interleavings of the same event set fold to the same balances, and any single-field mutation breaks verification.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Mutable balance rows** (`UPDATE balance SET amount = amount + x`) | Concurrent offline writes from two devices conflict, and last-write-wins loses money. History is lost unless logged separately, and the log then becomes the real ledger anyway. No natural place for signatures or tamper evidence. |
| **Double-entry tables** (debit and credit rows per transaction) | The right model for a multi-party accounting system. Here every movement is between a kid account and an implicit household source or sink, so the second leg carries no information. Sprout keeps the core benefit (immutable, balanced entries) through append-only events and the no-negative invariant, without doubling the write volume. Can be added as a projection if reporting needs it. |
| **`BigDecimal` for money** | Exact, but JVM-only (not KMP-ready), heavier to serialise, and its scale handling invites subtle equality bugs (`1.0 != 1.00`). Household amounts fit comfortably in a `Long` of minor units. |

## Consequences

**Easier**

- Offline multi-device writes never conflict: appends from different authors commute, and folding is deterministic.
- A full audit trail comes for free; the parent audit view is just the stream.
- Tampering is detectable per author, and replays are no-ops.
- The domain is testable without Android, storage, or network.

**Harder**

- **Projection cost.** Recomputing a balance means folding every event for an account. A kid earning daily for years produces thousands of events, which is fine in absolute terms but wasteful on every screen open. Mitigation: the Room projection cache stores `(accountId, balance, lastAppliedLamport, eventCount)`. New events are folded incrementally onto the cached value. A full rebuild runs only when an event arrives with a lamport lower than `lastAppliedLamport` (a late offline write), on schema migration, or when verification state changes. Rebuilds are scoped to one account and run off the main thread.
- Corrections are new events, so the UI must present "adjusted" entries clearly instead of editing history.
- Canonical encoding must be stable forever; changing it breaks every existing hash. The encoding is versioned in the event envelope from day one.
- The no-negative invariant is checked against the local view. Two offline debits can each pass locally and together overdraw. Resolution on merge: the later debit by total order is flagged and a parent-visible compensating event is proposed, never silently dropped. This is tested in the convergence suite.
- Storage grows without bound. At realistic household volumes this is kilobytes per kid per year; compaction (signed snapshots) is deferred until measured otherwise.
