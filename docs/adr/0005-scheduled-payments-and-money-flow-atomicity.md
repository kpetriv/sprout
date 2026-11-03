# ADR-0005: Scheduled payments and money-flow atomicity

- **Status:** Accepted
- **Date:** 2026-11-03
- **Milestone:** Decided at the M2 review and security audit; implemented in M2
- **Related:** [ADR-0002](0002-ledger-as-events.md) (ledger rules, `maxAmount`), [ADR-0004](0004-parent-authorisation-and-device-identity.md) (parent signatures, "Sign now" amendment)

## Context

M2 adds the first flows where one user action moves value in more than one place, and the first ledger events planned by a scheduler rather than typed by a parent:

- **Screen-time rewards** debit the kid's fiat `MAIN` account (`RewardPurchased`) and credit minutes (`TimeBankCredited`). Written as two appends, a crash or a rule failure between them leaves the kid charged with no minutes.
- **Allowance and interest** are planned by a daily worker into `pending_parent_actions` and need a per-use parent signature (ADR-0004). The review found queued actions that broke ledger rules only when the parent was already looking at the prompt (for example an amount over `maxAmount` after a schedule edit), actions that re-prompted forever after failing, queues planned from a schedule that had since changed, and two drains running at once.
- **Interest** was computed on today's savings balance for every missed accrual day, so a deposit made today earned interest for days before it existed.
- **Chore decisions**: a submission rewarded in money and minutes produces two `ChoreRewarded` events, each with its own prompt. A parent who cancels the second prompt leaves a half-paid approval; a later rejection of the same occurrence would contradict a payment already in the ledger.
- **One-off "any time" chores** (`Once(dueOn = null)`) had no stable occurrence key, so one chore could be submitted and paid repeatedly.
- **Photos** taken for submissions left plaintext copies in the camera cache, and encrypted copies were kept after the submission was decided.

## Decision

### 1. A purchase and its credit are one atomic write

A reward purchase and its screen-time credit are written by **one writer operation, `appendAllAsKid`**, which validates every event against the ledger rules as a batch and appends all of them in one database transaction (events, outbox rows, audit entries), or none. Lamports and hash-chain links are assigned inside the transaction, in order.

Owed grants are repaired by a **redeem step at app start and daily**: any accepted `RewardPurchased` for a `SCREEN_TIME` reward without its referencing `TimeBankCredited` (possible only for data written before this change, or after a failure outside the transaction) gets the credit appended. The step is idempotent: the credit references the purchase `eventId`, so running it twice appends nothing.

### 2. Scheduled actions are validated before any prompt

- Every pending action is validated against the ledger rules (static rules, account kinds, `maxAmount`, overflow) **before** a biometric prompt is shown. The parent is never asked to sign an event the writer would refuse.
- An action that fails validation is marked **`FAILED`** with the rule that failed and is shown on the dashboard. It **never re-prompts**; the parent fixes the schedule, and the planner creates a new action.
- When a schedule (allowance amount, cadence, interest rate or accrual day) changes, its not-yet-signed actions are **cancelled and re-planned** from the new schedule. Period keys keep re-planning idempotent.
- Draining is **serialised**: one drain at a time per device, so two prompts can never sign the same period twice.
- Draining is started by the parent's **"Sign now"** on the dashboard (ADR-0004 amendments), not automatically at unlock. Each prompt names the kid, the amount, and the period.

### 3. Interest principal is the end-of-day balance per accrual day

The principal for each accrual period is the **savings balance at the end of that accrual day**, taken from the ledger (`InterestPlanner.due` takes a `balanceOn(date)` function), not today's balance. Catch-up over several missed periods **compounds**: interest accrued for an earlier period is part of the balance on the later accrual days.

### 4. Chore decisions cannot contradict payments

- A **rejection is refused** once any reward event for that chore occurrence has been paid.
- A **partial approval** (one currency paid, the other prompt cancelled or failed) is reported on the submission and in the approvals inbox, and the parent can finish it. Finishing appends only the missing currency; the deterministic per-currency event ids keep it idempotent.

### 5. One-off "any time" chores pay once

A `Once` chore with no due date uses its **creation date** as the occurrence key (`dueOn`). The unique `(choreId, dueOn)` constraint on submissions then means it can be submitted and paid once.

### 6. Photo hygiene

- The camera cache directory is **cleared before each capture and at app start**, so a plaintext capture never outlives the encryption step by more than one session.
- Encrypted photos are **deleted when the submission is decided** (approved or rejected) and when the chore or the kid is archived.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Two separate appends with compensation** (append the purchase, then the credit; on failure, append a refund) | The window between the appends is visible to projections and to sync, and the refund is itself a write that can fail. The ledger would carry a purchase, a refund, and no credit for one tap. One transaction has no window. |
| **Automatic draining at parent unlock** | Unlocking the parent area is not consent to pay. A run of prompts at unlock trains the parent to approve without reading, which is exactly the habit per-use signing (ADR-0004) is meant to prevent. |
| **Validate at signing time only** | The parent would pass a biometric prompt and then see the event refused, and a rule that keeps failing would re-prompt on every drain. |
| **Interest on today's balance for every missed period** | Pays interest on money that was not saved during the period, and makes the result depend on when the parent opens the app. |
| **Allow rejection after a partial payment** (and reverse the payment) | Needs a parent-signed reversal event type and leaves the ledger saying the chore was both paid and rejected. Finishing or leaving the approval is simpler and truthful. |
| **Random occurrence key for any-time chores** | Every submission would be a new occurrence, so one chore could be paid without limit. |

## Consequences

**Easier**

- A balance never shows a charge without its minutes, and the redeem step heals older data.
- The parent only ever signs events the writer will accept, and each prompt says what it pays.
- Interest is reproducible from the ledger alone, whenever it is computed.
- Fewer plaintext and decided photos on the device at any time.

**Harder**

- `appendAllAsKid` must keep chain and lamport assignment correct across a batch; tests cover rollback mid-batch.
- Scheduled money waits for the parent to tap "Sign now"; the dashboard must make pending actions obvious.
- `FAILED` and cancelled actions need dashboard UI and a clear way to fix the schedule.
- The receiver side (M3) needs signed reward definitions (ADR-0004 amendments) to verify that a purchase's cost and minutes match the catalogue.
