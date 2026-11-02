# ADR-0002: Ledger as a signed, append-only event stream

- **Status:** Accepted; amended 2026-11-02 after the M1 review (see [Amendments](#amendments-m1-review-2026-11-02) and [ADR-0004](0004-parent-authorisation-and-device-identity.md))
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

## Amendments (M1 review, 2026-11-02)

These refine the decision above without reversing it; they were made before any real events existed, so no migration is needed. Where they conflict with the text above, they win. Signature domain separation, duplicate `eventId` handling, lamport bounds, and the parent signature are decided in [ADR-0004](0004-parent-authorisation-and-device-identity.md).

### Canonical form comes from the stored document

The canonical encoding is computed from the **JSON tree of the stored document**, not by re-serialising the decoded Kotlin class:

1. Parse the stored (or received) event JSON into a tree.
2. Remove `envelope.hash`, `envelope.signature`, and (from schema version 2) `envelope.parentSignature`.
3. Write the rest as compact JSON with object keys sorted by UTF-16 code unit at every depth, arrays in order, minimal string escaping, plain decimal integers, UTF-8 (the rules already in `CanonicalEncoder`).

Re-serialising the Kotlin class would drop any field the running app version does not know. An event written by a newer schema version would then hash differently on an older device and be quarantined as tampered. Working from the tree keeps unknown fields inside the hashed and signed bytes, so they survive verification. For the same reason, **decoding ignores unknown keys**: an older app reads the fields it knows and verifies the whole document.

When the app creates an event, it serialises the class once, and that JSON is both what is stored and what is canonicalised.

### Amount bound: `maxAmount`

Every amount in an event (`amount`, `minutesUsed`, `principal`) satisfies `|amount| ≤ maxAmount`, where `maxAmount = 10^12` minor units (ten billion pounds, or about 1.9 million years of minutes). A `ManualAdjustment` is bounded in absolute value. An event outside the bound breaks a static rule.

The bound is far above any household amount and far below `Long.MAX_VALUE` (about 9.2 × 10^18), so a single event cannot get near overflow on its own, and it takes millions of maximal events to reach it.

### Overflow is a per-event anomaly; projection never throws

`Money` arithmetic throws `ArithmeticException` on overflow rather than wrapping. Projection catches that per event: an event whose postings would overflow any balance (or the daily game total) is **excluded and recorded as an `InvalidEvent` anomaly**, and folding continues with the next event. Projection never throws because of event content. Otherwise one poisoned event, which a rooted kid device or a forged remote event could produce, would make every balance in the household unreadable on every device (threat model T19).

### Daily game cap: reject, never clamp

A `GamePlayed` whose amount would take the kid's credited total for that `playedOn` date and currency above `LedgerPolicy.dailyGameCapFor(currency)` is rejected as a whole:

- at local validation, it is refused with `DailyCapExceeded` and never written;
- when projecting merged streams (two offline devices each within the cap, together over it), the event that crosses the cap in total order is excluded entirely and reported as a `DailyCapExceeded` anomaly.

It is never partially credited. Clamping would credit an amount that no signed event states, so the balance would no longer be the plain sum of signed amounts.

### Account kinds

Accounts are one per (kid, currency, kind), with kind `MAIN` or `SAVINGS`. A `Currency.Minutes` account is always `MAIN`; savings accounts are fiat only. Which events may post where:

| Event | Fiat `MAIN` | Fiat `SAVINGS` | Minutes (`MAIN`) |
|---|---|---|---|
| `ChoreRewarded`, `AllowancePaid`, `GamePlayed` | Credit | No | Credit |
| `RewardPurchased` | Debit | No | Debit |
| `TransferToSavings` | Debit (source) | Credit (destination) | No |
| `InterestAccrued` | No | Credit | No |
| `TimeBankStarted`, `TimeBankStopped` | No | No | Yes (stop debits) |
| `ManualAdjustment` | Credit or debit | Credit or debit | Credit or debit |
| `OverdraftCompensated` | Credit | Credit | Credit |

`TransferToSavings` moves between two different accounts of the same kid and currency. Every account an event touches must belong to the envelope's kid and match the event's currency. A violation is a static rule failure: refused locally, `InvalidEvent` when projecting.

### Updated invariants

The invariant list above now reads:

1. No negative balances: a debit that would take an account below zero is rejected locally. `TimeBankStopped` is exempt (the minutes were already used); merged offline debits that overdraw are recorded as `OverdraftDetected` and resolved by a parent's `OverdraftCompensated`.
2. Amounts are strictly positive and at most `maxAmount`; a `ManualAdjustment` is non-zero with absolute value at most `maxAmount`.
3. `GamePlayed` credits respect a per-kid, per-date, per-currency daily cap; over-cap events are rejected, never clamped.
4. Only the parent may author `ChoreRewarded`, `AllowancePaid`, `ManualAdjustment`, `InterestAccrued`, and `OverdraftCompensated`. In M1 this is a client-side check on the self-declared `authorRole`; from schema version 2 these events carry a parent signature that receivers verify (ADR-0004).
5. `eventId` is unique: the same id with the same content hash is a no-op, the same id with a different hash is a conflicting duplicate and neither copy is applied (ADR-0004). Lamport is strictly increasing per author.
6. Events are immutable once written.
7. Events post only to accounts of their own kid, in their own currency, of a kind the table above allows.
8. Free-text fields (`ManualAdjustment.reason`, `OverdraftCompensated.note`) are at most 200 characters and are redacted from `toString`. They leave the device in plaintext from M3 (the ledger is signed, not encrypted; ADR-0003) and are listed in [PRIVACY.md](../PRIVACY.md).
