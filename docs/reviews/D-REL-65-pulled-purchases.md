# D-REL-65: pulled reward purchases are not checked against the catalogue

- **Status:** Security read and recommendation, 2026-11-14. No production code changed.
- **Scope:** `core/database` (`RoomLedgerRepository`, `PurchaseCatalogue.kt`, `RoomRemoteApplier`), `core/domain-ledger` (`LedgerRules`, `Projection`, `RemoteEventVerifier`), `core/sync` (`ConflictResolver`), `firestore/firestore.rules`, `feature/wallet`, `docs/THREAT_MODEL.md` T27, ADR-0005.
- **Base:** `main` at `7272195` (after 1.1.2, PR 19).

## Verdict

**The claim is confirmed.** A kid-authored `RewardPurchased` is compared with the reward catalogue only on the device that appends it. A pulled purchase passes the device-signature, role, kid, chain and ledger-rule checks, but no receiver looks at the catalogue, and the screen-time credit that follows it is checked only against the purchase itself. T27's "Mitigated M3" and ADR-0005's last consequence describe a receiver-side check that does not exist.

**Severity: Low.** Exploiting it needs a rooted kid phone with the genuine app instrumented (the T27 actor). The gain is limited to the kid's own fiat MAIN balance (paying less) and the kid's own time bank, which runs on the honour system (an accepted risk). Every forged purchase stays signed by the kid's device, hash-chained, and visible in the parent's wallet history. The documentation gap is the more pressing part: T27 should not say "Mitigated" while this holds.

**Recommendation: option (b)**, flag on receivers rather than quarantine, backed by a local history of the reward versions each device has applied, with T27 and ADR-0005 reworded in the same change. Details at the end.

## 1. The two paths

### Local append (checked)

| Step | Where | What happens |
|---|---|---|
| Kid buys in the shop | `RoomLedgerWriter.appendAllAsKid` (`RoomLedgerWriter.kt:86`), which calls `appendLocked` per event (`:106`) | The purchase and its `TimeBankCredited` go through one transaction (ADR-0005). |
| Own-signature, chain, parent-signature gates | `RoomLedgerRepository.appendLocked` / `appendNew` (`RoomLedgerRepository.kt:408-434`) | Ordinary local gates. |
| Ledger rules | `validateAndStore` (`:574-592`), `LedgerRules.validate` (`:579`) | Static, history and stateful rules, including no overdraw. |
| **Catalogue** | `validateAndStore` `:584-585` | `event is RewardPurchased && authorRole == KID && !catalogue.matches(event)` gives `CATALOGUE_REJECTION`. |
| What `matches` compares | `RewardTableCatalogue.matches` (`PurchaseCatalogue.kt:41-52`) | The reward row exists and is not a tombstone, is available to the purchasing kid (active, `forKids`), `cost == amount`, same currency, `minutesGranted` equal, and the row is parent-authored (`:54-87`: written here in a PARENT session, or a pulled document whose envelope, rebuilt from the row, verifies against a registered PARENT device key at the row's lamport). |

The production wiring is `RewardTableCatalogue(database, …)` in the injected constructor (`RoomLedgerRepository.kt:157`). The class KDoc says the same: "A kid-authored `RewardPurchased` **appended here** must match the catalogue row" (`:123-125`).

A PARENT-authored purchase skips the catalogue (`:584` tests `authorRole == Role.KID`). That is intended: the parent is trusted to spend on the kid's behalf.

### Remote ingest (not checked)

| Step | Where | What happens |
|---|---|---|
| Verify the document | `RemoteEventVerifier` (`RemoteEventVerifier.kt:10-37`), called from `ingestLocked` (`RoomLedgerRepository.kt:731-737`) | Decodes, hash, registered and active author device, `authorUid`, role allowed, **a KID device writes only for its own kid** (step 7, code at `:101`), device signature, parent signature for parent-only types, lamport bound. Nothing about rewards. |
| Order and forks | `IngestOrdering.plan` via `applyPlanned` / `applyInOrder` (`RoomLedgerRepository.kt:844-942`) | Chain links and fork quarantine. |
| **Apply** | `applyRemote` (`:1047-1074`) | Only `LedgerRules.validate(event, before)` (`:1059`). An `UnknownAccount` is held; an order-independent violation is quarantined; an order-dependent one (including `InsufficientFunds`, `GrantMinutesMismatch`, see `isOrderDependent` `:1359-1376`) is **stored** and left to the projection. `catalogue` is never called here; it is used only at `:584`. |

### What the domain rules re-derive for a pulled purchase and its credit

Every device re-derives balances with `Projection`, whose fold runs `LedgerRules.staticViolations` and `historyViolations` on each event (`Projection.kt:404-406`). For the two events of a screen-time purchase:

- **`RewardPurchased`** (`LedgerEvent.kt:126-136`: `amount`, `currency`, `rewardId`, optional `minutesGranted`; postings debit `amount`).
  - Amount positive and at most `LedgerPolicy.maxAmount` (`LedgerRules.kt:161-169`); the default bound is 10^12 minor units (`LedgerPolicy.kt:61`).
  - `minutesGranted`, when present, positive and at most `maxAmount` (`LedgerRules.kt:171-173`).
  - Currency matches the account, the account is the kid's own fiat MAIN (`LedgerRules.kt:27-33`, `:150-158`).
  - `rewardId` is not checked by the rules at all; the backend rules only bound its length (`firestore/firestore.rules:228`, `optString(e, 'rewardId', 128)`).
  - The projection records a grant of `minutesGranted` under the purchase's eventId (`Projection.kt:499`, `:506-509`). It never learns the reward's price or minutes.
  - No overdraw is only a local, stateful rule (`LedgerRules.kt:62-67`). On a receiver a purchase that overdraws is applied and reported as `OverdraftDetected` (`Projection.kt:527-550`), by design (`LedgerRules.kt:75-76`).
- **`TimeBankCredited` with `grantedBy`** (`LedgerEvent.kt:240-251`). Kid-authorable (only a manual credit is parent-only, `LedgerRules.kt:20-22`, `:91-101`). The history rule requires an applied purchase of the same kid with that id, the same `rewardId`, **exactly the purchase's `minutesGranted`**, and no earlier credit (`LedgerRules.kt:57-60`, `:317-334`). So the credit is checked against the purchase, and the purchase against nothing. Whatever minutes the purchase claims, the credit may claim too.

The catalogue the local check reads is itself device-local state: one row per reward, last-writer-wins on `(lamport, authorDeviceId)` with tombstones winning outright (`RoomRemoteApplier.applyReward`, `RoomRemoteApplier.kt:640-649`; `ConflictResolver.kt:29-35`, `:84`). No device keeps earlier versions, and Firestore holds only the latest document.

### Where the documents overstate it

- `docs/THREAT_MODEL.md:78` (T27): the mitigation column describes a parent-signed catalogue that "the catalogue accepts", which is true of the local append only, and the status is "Mitigated M3". The threat as worded ("receivers cannot tell the purchase from a legitimate one") is exactly what still holds.
- `docs/adr/0005-…md:78`: "The receiver side (M3) needs signed reward definitions … to verify that a purchase's cost and minutes match the catalogue." M3 delivered the signed definitions (D-M3-3, D-M3-27, ADR-0006 `:63`) but not the receiver-side verification.
- `RewardEntity`'s KDoc ("Kid-authored `RewardPurchased` events are checked against this row … before they are stored") is accurate only for local appends.

## 2. What a modified kid phone can do today

The attacker is T27's actor (`THREAT_MODEL.md:27`): a kid with root and Frida who hooks the genuine, enrolled app so its device key still signs. A repackaged app cannot reuse the enrolled device key and would have to enrol as a new device, which a parent approves. Skipping `catalogue.matches` (or editing the local reward row, which D-M3-27 makes fail closed locally) lets the phone sign and push purchases that every receiver stores and every projection applies:

| Abuse | Accepted by receivers? | Limit that still applies |
|---|---|---|
| **Lower price** | Yes. | Amount must be at least 1 minor unit (`LedgerRules.kt:166`). |
| **More minutes** | Yes, and the paired `TimeBankCredited` is valid because it only has to match the purchase. | `minutesGranted` ≤ `maxAmount` = 10^12 (`LedgerRules.kt:173`): no practical bound. |
| **Inactive, deleted, hidden or sibling-only reward, or an invented `rewardId`** | Yes. Nothing on a receiver reads the catalogue or `forKids`. | None beyond the 128-character id in the rules. |
| **Spend money the kid does not have** | Yes: `InsufficientFunds` is order-dependent, so the event is stored (`RoomLedgerRepository.kt:1362`). | The projection shows `OverdraftDetected`, which counts towards the anomaly badge on the parent's home (`ParentHomeViewModel.kt:152`). |
| Buy for another kid, debit a sibling's account | **No.** `KID_MISMATCH` (`RemoteEventVerifier.kt:25`, `:101`) and the ownership rule. | |
| Raise a fiat balance | **No.** A purchase only debits; the gain is paying less. | |
| Hide or rewrite the purchase later | **No.** Device signature, per-author hash chain, fork quarantine (T2, T3, ADR-0004). | |

### Controls that still limit it

- **Signatures and the hash chain** make every forged purchase attributable to one kid device and permanent; they cannot be deleted or edited quietly. The parent can revoke the device (T3).
- **`LedgerPolicy.maxAmount`** bounds amounts and minutes, but at 10^12 it only prevents overflow (T19). It is not a business limit.
- **Balance never negative** is enforced only by the author. Receivers record an overdraft anomaly, which the parent sees.
- **The parent's view.** The kid's wallet history shows a Shop row with the amount paid and a Screen-time row with the minutes credited (`WalletStateMapper.kt:180-205`), but no reward title and no comparison with the catalogue. A parent who looks would notice "60 minutes for 1p"; nothing draws their attention to it. The "Rejected events" card (`observeQuarantined`, `RoomLedgerRepository.kt:186`) never shows these, because nothing quarantines them.
- **Real-world rewards** (`TREAT`, `OUTING`, `RewardKind.kt`) are handed over by a parent, who is in the loop.
- **Screen time is honour-system** (`docs/SECURITY_REVIEW.md`, accepted risks): extra minutes inflate a number; the app does not enforce device screen time.
- App Check / Play Integrity (T11) is not a reliable control here: the genuine app is running, and Firestore enforcement is not switched on yet (T33).

### Severity

Likelihood is low (rooted phone plus instrumentation, against a family app). Impact is low: the kid's own balance and an honour-system counter, with a signed, permanent trail that the parent can correct with a `ManualAdjustment` and end by revoking the device. Overall **Low**. That is about the same class as T4 (rooted local database edits), which the design answers with "other devices re-derive from signed events". The difference here is that the re-derivation cannot see the catalogue, so T27 should read "Partly mitigated" whatever option is chosen.

## 3. The three options

The hard case for any receiver-side check, legitimate and not attacker-driven:

1. The kid's phone holds reward R at cost 10 (version v1, lamport 100).
2. Offline, the kid buys R at 10. The purchase gets lamport 140 (the kid's clock follows every lamport it has seen, ledger events included).
3. Meanwhile a parent raises R to 20 (v2, lamport 120) on a parent phone.
4. A second parent phone pulls v2 first, then the purchase. Its catalogue row says 20; the purchase says 10.

The purchase is legitimate, its lamport is above v2's, and the receiver never saw v1 if it was offline during step 1. Lamport order does not imply that the kid had seen v2. A plain "match the current row" check on receivers would refuse it.

### (a) Keep the price a reward had at each lamport

As written, receivers would store every reward version they apply and accept a purchase that matches the version in force at the purchase's lamport.

- **Race above:** fails. The version in force at lamport 140 is v2, which the kid never saw. Lamports are not causal: a purchase at lamport 140 can legitimately use v1. The sound rule is weaker: "matches **some** parent-signed version with a lower lamport". That admits the race, and the attacker can then buy at the cheapest price R ever had. The parent did sign that price at some point, though.
- **Missing versions:** Firestore keeps one document per reward (LWW), so a receiver that was offline across two edits never sees the middle version, and a freshly installed or restored device (1.1.1 account recovery) sees only the latest. Making (a) exact needs a **sync change**: either an append-only, parent-only `rewardVersions` collection (new rules, new pull, new applier), or a schema-3 `RewardPurchased` that carries the reward version it was bought at (lamport, author device, signature and the reward payload), so any receiver can verify the parent's signature without a history.
- **Quarantine:** the embedded-version variant is the only design in which a refusal would be deterministic (it depends only on the event and the registry), so it could quarantine at ingest without making replicas diverge.
- **Cost:** new event schema version (`Envelope.CURRENT_SCHEMA_VERSION` is 2, `Envelope.kt:72`), the rules' key allowlist and bounds (`firestore.rules:195-200`, `:228`), canonical encoding, and a staged rollout: 1.1.x verifiers accept only schema 1 and 2, so receivers must ship before writers, and schema-2 purchases from not-yet-upgraded kid phones remain unverifiable until a household-policy switch requires schema 3. Changes ledger rules and ingest, so security-auditor review on every slice.

### (b) Flag, do not quarantine, purchases that match no version the receiver has seen

On ingest, a receiver compares a pulled kid-authored purchase with the reward versions it knows. A mismatch records a flag that a parent sees. The event is stored and projected exactly as today.

- **Why not quarantine:** whether a receiver has seen a version depends on that device's sync history. Quarantining on it would make two devices hold different ledgers and show different balances, which breaks the convergence every ingest rule is built around (`RoomLedgerRepository.kt:271-273`). A flag lives outside the projection, so balances still converge.
- **Race above:** with a local version history (the storage half of (a), local only), a receiver that saw v1 matches it ("some parent-signed version with a lower lamport") and raises nothing. A receiver that never saw v1 raises a flag: a **false positive** the parent dismisses. They occur only when a price edit and an offline purchase cross on a device that missed the old version.
- **Fresh install or restore:** the device knows only the latest version of each reward, so every historic purchase made at an older price would be flagged. Avoid that by flagging only purchases that arrive after the device's history for that reward started (compare the purchase's server stamp, which every pulled event keeps, with the time the first version was recorded).
- **Residual:** a modified phone can still buy at any price the reward ever had (with a lower lamport), or not be noticed by a parent who ignores flags. Prices the parent once signed, on the kid's own account, is an acceptable residual at Low severity.
- **Cost:** local only. No event schema, rules or sync change. One Room migration and a small parent-facing surface.

### (c) Reword T27 as partly mitigated and accept

- **Race above:** no check, so nothing to get wrong.
- **Residual:** everything in section 2, with no signal to the parent beyond the overdraft anomaly. A parent would have to read wallet rows to notice "60 minutes for 1p".
- **Cost:** two document edits, plus an accepted-risk line in `SECURITY_REVIEW.md`.
- It is honest, and at Low severity it is defensible. But it leaves the signed catalogue (D-M3-3, D-M3-27), built to stop exactly this, doing nothing on the devices that matter (the parents'), and it accepts a gap that (b) closes for a modest, local cost.

| | Race on a receiver that pulled the edit first | Divergence risk | Residual for an attacker | Schema or sync change | Size |
|---|---|---|---|---|---|
| (a) history by lamport, as written | Refuses a legitimate purchase unless weakened to "any earlier version" | High if it quarantines; none if it flags | Cheapest past price | Needs synced history or schema-3 purchases to be exact | Large |
| (a′) purchase carries its signed reward version | Correct: the purchase proves its price | None (deterministic) | Cheapest past price | Event schema 3, rules, staged rollout | Large |
| (b) flag with local history | Flags only if the receiver never saw the old version | None | Cheapest past price, if the parent ignores flags | Room migration only | Medium |
| (c) accept | Not applicable | None | Any price, any minutes, any reward | None | Small |

## 4. Recommendation: option (b), with a local version history

Choose (b) and reword T27 and ADR-0005 in the same change. It turns an invisible abuse into a visible one on the parent's phone at local cost. It keeps convergence intact and needs no event schema, rules or sync change, which matches a Low severity. Keep (a′) on file for the day minutes are enforced on the device or rewards pay out real money; at that point refusing becomes worth a schema migration.

### Changes, by module

**`core/database`** (ingest code in `RoomLedgerRepository`: needs security-auditor review before commit, per `CLAUDE.md`)

1. **`reward_versions` table** (migration 9 → 10, schema JSON 10): `rewardId`, `lamport`, `authorDeviceId`, `authorUid`, `signature`, `deleted`, `cost`, `currency`, `minutesGranted`, `active`, `forKidsJson`, `recordedAtEpochMs`; primary key `(rewardId, lamport, authorDeviceId)`. Insert-or-ignore one row whenever a reward version is written locally (`DocumentWrites` / `RewardRepository`) or applied from a pull (`RoomRemoteApplier.applyReward`), **including versions that lose LWW** (an older version pulled late is still a price the kid may have seen). Seed it in the migration from the current `rewards` rows. Each row must re-verify the way `RewardTableCatalogue.isParentAuthored` does today (written here in a PARENT session, or an envelope rebuilt from the row that verifies against a PARENT device at that lamport), so a row edited on a rooted receiver fails closed.
2. **`PurchaseCatalogue`:** add a history-aware `matchesAnyVersion(event)`: some parent-authored version with `lamport < event.lamport`, live, available to the kid, same cost, currency and minutes. Leave the local `matches` (current row only) as it is: the author must still pay the current price.
3. **`purchase_flags` table** (same migration): `eventId`, `hash`, `kidId`, `rewardId`, `reason` (`NO_SUCH_REWARD`, `NO_MATCHING_VERSION`, `NOT_AVAILABLE_TO_KID`), `flaggedAtEpochMs`, `dismissedAtEpochMs`. Written by `applyRemote` after `storeRemote(… LEDGER_EVENT_INGESTED)` for a kid-authored `RewardPurchased` that fails `matchesAnyVersion`, provided the purchase's server stamp is after the reward's first `recordedAtEpochMs` on this device (no flood after install or restore). Audit it as a new `AuditAction.PURCHASE_FLAGGED` with ids only. Never quarantine; never change the projection.
4. Run the flag check on **PARENT sessions only**. A kid phone has nothing to act on, and skipping it there avoids work on every pull.
5. Wipe path: confirm `WipeLocalDataUseCase` clears both tables (if it touches code there, that also needs the auditor).

**`feature/parent-home`** (and `app`'s `SyncHouseholdStatusSource`): a "Purchases to check" entry next to the "Rejected events" card, listing kid, reward (current title if it still exists), amount paid against the known price, minutes credited, and a Dismiss action that sets `dismissedAtEpochMs`. Optionally mark the matching row in `feature/wallet` history.

**`core/domain-ledger`:** no change. The rules and projection stay catalogue-free and deterministic.

**Docs:**
- `THREAT_MODEL.md` T27: mitigation "Author device refuses a purchase that does not match the parent-signed catalogue (M3); receivers on parent devices flag pulled purchases that match no parent-signed version they have seen (D-REL-65); a forged purchase is device-signed and chained." Status "Partly mitigated: residual, a rooted kid device can buy at any price the reward has ever had."
- ADR-0005 `:78`: say what the receiver side does (flag, not verify), and link this review.
- `RewardEntity` KDoc: "checked before a local append".
- `CONTROLS.md`: a control row for the flag. `SECURITY_REVIEW.md`: the residual under accepted risks. `DECISIONS-PENDING.md`: close D-REL-65 with the chosen option.

### Tests that would prove it

In `core/database` (Robolectric/Room, next to `AtomicPurchaseTest` and the ingest tests), all running as a parent receiver unless stated:

1. **Gap regression (fails today):** a pulled kid purchase at a lower price than the only reward version is stored, projected (balance debited by the forged amount), and **flagged** `NO_MATCHING_VERSION`.
2. **Extra minutes:** a pulled purchase with `minutesGranted` above the reward's, plus its `TimeBankCredited`. Both are applied, the purchase is flagged, the credit is not flagged separately.
3. **Unknown, deleted, inactive and sibling-only rewards:** each is flagged with its reason (`NO_SUCH_REWARD`, `NOT_AVAILABLE_TO_KID`). A tombstoned reward's earlier live version still matches a purchase with a lower lamport than the tombstone.
4. **The race, receiver saw v1:** v1 (lamport 100) applied, v2 (lamport 120) applied, purchase at v1's price with lamport 140, so no flag.
5. **The race, receiver missed v1:** only v2 applied before the history start, and the purchase was server-stamped after it: flagged (documents the known false positive). Same purchase stamped **before** the history start: not flagged (the restore case).
6. **Late older version:** v2 applied, then v1 pulled late (loses LWW). `reward_versions` holds both, and a v1 purchase is not flagged.
7. **Convergence:** two receivers with different reward histories ingest the same events in different orders. Balances, anomalies and the stored ledger are identical; only `purchase_flags` differ.
8. **Fail closed:** a `reward_versions` row whose cost was edited after it was recorded does not verify and does not satisfy a match.
9. **Scope:** a kid session ingesting the same purchase writes no flag; a PARENT-authored purchase is never checked.
10. **Unchanged local behaviour:** the existing `CATALOGUE_REJECTION` tests still pass. A local append at an older version's price is still refused (author pays the current price).
11. **Migration test** 9 → 10: `reward_versions` seeded from `rewards`, `purchase_flags` empty.
12. **Wipe:** both tables are empty after `WipeLocalDataUseCase`.

In `feature/parent-home`: ViewModel test for the flag list and dismiss, plus screenshot tests of the card (empty, one, several).
