# ADR-0006: Sync protocol and document envelope

- **Status:** Accepted
- **Date:** 2026-11-03
- **Milestone:** Decided and implemented in M3 (`core/sync`, `core/sync-firebase`, `core/database`, `firestore/`)
- **Related:** [ADR-0001](0001-architecture.md) (offline-first, `SyncTransport` port), [ADR-0002](0002-ledger-as-events.md) (ledger events), [ADR-0004](0004-parent-authorisation-and-device-identity.md) (signatures, lamport bounds, conflicting copies), [ADR-0007](0007-identity-linking-and-device-registry.md) (registry the receivers verify against), [`docs/FIREBASE.md`](../FIREBASE.md) (wire shapes and rules)

## Context

Up to M2 every household lived on one phone. M3 puts a second device (another parent phone, a kid phone) in the same household, with Firestore in between. Three things have to hold at once:

- **Local-first.** Writes never wait for the network (ADR-0001). A kid ticks a chore on a bus; it syncs later.
- **Receivers trust nothing they did not verify.** The threat model counts a rooted kid device, a repackaged app and someone holding the project's credentials among the actors. Firestore rules stop the first two at the server, but not the third, so every device must be able to check what it pulls on its own: who wrote it, from which registered device, and whether that device was allowed to.
- **Convergence.** Two devices that have seen the same writes end in the same state, whatever order the writes arrived in, and the server's rules agree with the devices about which write wins (otherwise a write the server accepts is one some device ignores, and the household forks).

Ledger events already have the first two properties (signed, hash-chained per author, ADR-0002/0004) and never conflict: they are append-only. Everything else (kids, accounts, chores, submissions, rewards, schedules, goals, policies) is mutable state that two devices can change concurrently.

## Decision

### 1. Local-first outbox

Every write lands in Room and an `outbox` row in **one transaction**. A document is built and signed **inside** that transaction, so the signature covers the lamport allocated there (D-M3-27). The UI reads Room only. `SyncEngine` (`core/sync`) drains the outbox through the `SyncTransport` port; `FirebaseSyncTransport` (`core/sync-firebase`) is the only implementation that talks to Firebase, `FakeSyncTransport` (testFixtures) is the in-memory one the convergence tests use.

Sync runs from a periodic `SyncWorker` (15 minutes, network constraint), an expedited one-off run after each outbox insert, and `SyncEngine.run` while the app is in the foreground (snapshot listeners as change hints, debounced 500 ms). Syncs never overlap: `syncOnce` holds a mutex.

### 2. Pull before push

One `syncOnce` is:

1. **pull** pages until the transport has no more; per page: registry (`devices`, `parentKeys`), then documents in apply order (`kid, account, household_policy, parent_key`, then the rest), then events, then save the page's cursor;
2. **retry held** documents and events (section 6), whose missing author, predecessor or account the pull may have brought;
3. **push** the outbox in `seq` order.

A pull failure ends the sync before anything is pushed. Pulling first means a local write that refers to a remote kid, account or chore is only sent once this device knows it, and `UnknownAccount` cannot arise for remote accounts.

### 3. `DocumentEnvelope` v1

| Field | Meaning |
|---|---|
| `schemaVersion` | 1. A new top-level field needs a new version. |
| `householdId` | The household; receivers refuse another household's document (`WRONG_HOUSEHOLD`). |
| `docType`, `docId` | One of the registry types (section 4) and the id within it. |
| `authorUid`, `authorDeviceId` | The claimed author; checked against the registered device. |
| `lamport` | The author device's Lamport value at the write; the last-writer-wins key with `authorDeviceId`. |
| `deleted` | Tombstone flag. There are no deletes, only tombstones. |
| `payload` | The model as a JSON tree (closed key set per docType on the server). |
| `signature` | Base64 ECDSA P-256 / SHA-256 by the author device key. |

**Signing domain.** `UTF-8("sprout/document/v1") || 0x00 || canonical(envelope minus signature)`, canonical being `CanonicalEncoder`'s form (sorted keys, compact, integers only, strict UTF-8; D-M3-16). The domain differs from `sprout/ledger-event/v1` and `sprout/ledger-event-parent/v2`, so a document signature can never be replayed as an event signature or the reverse.

**Closed top level** (D-M3-14). An older client that drops an unknown top-level field on decoding could no longer verify the signature, so the top level only changes with `schemaVersion`. New fields go inside `payload`, which is signed as a tree and kept as a tree, so unknown payload fields stay covered.

`serverReceivedAt` is a storage field the transport adds next to the envelope (section 7); it is not part of the signed envelope. Ledger events keep their own envelope (ADR-0002) and are stored with its fields flattened next to the event fields (D-M3-18).

### 4. docType registry and writer roles

| docType | Collection | Who may write |
|---|---|---|
| `kid`, `account`, `household_policy`, `chore`, `reward`, `allowance_schedule`, `savings_goal`, `interest_policy` | `kids`, `accounts`, `householdPolicy`, `chores`, `rewards`, `allowanceSchedules`, `savingsGoals`, `interestPolicy` | A PARENT device |
| `chore_submission` | `choreSubmissions` | A PARENT device; or a KID device for **its own kid** (`payload.kidId == device.kidId`), only with status `SUBMITTED`, only under its own id (`payload.id == docId`), never a tombstone |
| `parent_key` | (none) | Nobody: parent keys are registered by the server (`registerParentKey`, ADR-0007, D-M3-34). Kept in the registry for apply order; ignored on pull, `Permanent("unsupported")` on push |

The role is the **registered** role of the author device (`DeviceRegistry`), never one the document states. This is what makes the reward catalogue parent-signed (D-M3-3, T27): a reward counts only if its envelope verified against a PARENT device key.

### 5. Conflict rules

`ConflictResolver.resolve(local, remote)` applies the remote copy iff it is strictly greater under one total order, compared in this order (D-M3-13 as amended):

1. **Tombstones beat live copies**, whatever the lamports. A tombstone is only replaced by a tombstone with a greater `(lamport, authorDeviceId)`. A late or concurrent update never resurrects a deleted document.
2. **Submission status rank, then LWW.** For two live `chore_submission` copies the higher rank wins (`APPROVED` 2 > `REJECTED` 1 > `SUBMITTED` 0) before lamports; equal ranks fall back to step 3. A stale or later `SUBMITTED` never undoes a decision.
3. **Last writer wins** on `(lamport, authorDeviceId.value)`.

Guards: a remote submission whose **identity** (`id`, `choreId`, `dueOn`, `kidId`) differs from the stored one is ignored (identity is immutable); a payload that does not decode loses. Every step is a total preorder and the last a total order on distinct writes, so the state is the maximum of the copies seen: commutative, associative, idempotent.

**The server mirrors the same relation.** `firestore/firestore.rules` accepts a document update only when it wins under the same order (a live copy never overwrites a tombstone, rank first for submissions, then LWW), refuses kid tombstones and kid writes over decided submissions, and keeps the identity fields. A write the rules refuse as stale is exactly one the receivers would ignore. The rules tests "documents: conflict semantics match core/sync ConflictResolver" pin this.

Remote document lamports are observed by the device clock, bounded by `maxLamportJump` (default 1,000,000, ADR-0004 §4); a document above the bound is held in `document_inbox` and audited.

### 6. Events: verification, held vs quarantined, forks, conflicting copies

A pulled event passes `RemoteEventVerifier` (decode and schema, hash, registered device, device status at the event's lamport, uid, role, kid, device signature, parent signature when parent-only, lamport bound; ADR-0004 as amended), then `IngestOrdering` (chain links and forks), then `LedgerRules.validate` in `RoomLedgerRepository.ingest`. Every refusal is one of two kinds:

| Outcome | When | What happens |
|---|---|---|
| **Held** (`ledger_inbox`, `document_inbox`) | The answer may change: author device unknown to the registry mirror or PENDING (D-M3-31); chain gap (`CHAIN_GAP`); unknown account (`UNKNOWN_ACCOUNT`); behind an author's quarantined event in the same batch (`AUTHOR_BLOCKED`); a parent-only event before the household policy document arrived (`POLICY_UNKNOWN`); a document lamport too far ahead | Never projected. Verified again on every ingest and `retryHeld`. At most 500 held rows per author device; the oldest are dropped with a `HELD_DROPPED` audit row (D-M3-36) |
| **Quarantined** (stored `quarantined = 1` with the reason; documents: `DOCUMENT_REJECTED` audit) | The answer cannot change: bad hash or signature, uid or role mismatch, missing or invalid parent signature, REVOKED device at or above its `revokedAtLamport`, lamport too far ahead, an event-intrinsic rule violation (role, amount, currency, ownership, account kind, text, period key, interest terms; D-M3-25), a fork | Kept as evidence, never projected, audited `LEDGER_EVENT_QUARANTINED` with ids and reason only, shown to parents in the "Rejected events" card |

Order-dependent rule failures (funds, daily cap, time bank, period claim, grant) are **stored like valid events** and left to the projection, which decides them identically on every device (D-M3-25).

**Forks** (`FORKED_CHAIN`, D-M3-31). An author that signs two different events linking to the same `prevHash` has equivocated. Every branch is quarantined on every device, including a branch this device had already applied, with all its stored descendants; the projections are rebuilt. Rows the verifier refused never count as branches, so a forged event cannot kill a real chain (D-M3-36).

**Conflicting copies of one `eventId`** (D-M3-30, amending ADR-0004 §6 and D-M1-1). Exactly one copy always applies: among the copies from devices the registry lists as PARENT, the first by `(lamport, authorDeviceId, hash)`; if there are none, the first of all copies. The choice depends only on the set of copies and the registry, so every device applies the same one. The server stores a conflicting copy under `events/{eventId}__{hash}` only from a PARENT member, so a kid cannot plant one. Every conflict is still reported as a `ConflictingDuplicate` anomaly.

### 7. Cursors

- **Documents and events:** one cursor per collection, `<ISO instant>|<document id>` of the last document read (D-M3-23). Every client write sets `serverReceivedAt` to `FieldValue.serverTimestamp()` and the rules accept it only when it equals `request.time`, so the ordering key is the server's, not the device's. Queries read `(serverReceivedAt, id) > cursor`, 500 per collection per page.
- **Registry:** `devices` and `parentKeys` are small and the server updates them in place, so they are read in full on every pull with a content-digest cursor (D-M3-22).
- The cursor is saved **after** the page is applied, in `sync_cursor`. A crash between the two re-pulls the page, which is harmless because applying is idempotent.

### 8. Push and errors

- The engine pushes at most 100 outbox items per call (never two items with the same push id in one call); the transport writes Firestore batches of at most 400 and, when a batch is refused, retries item by item to find the refused one.
- **Idempotent classification** (D-M3-23): a refused event whose id is already stored with the same hash is accepted; with a different hash it is stored as `eventId__hash`; a refused document whose server copy beats or equals it is accepted; anything else is `Permanent("permission-denied")`.
- **Documents:** a permanent rejection is acked and reported (`SYNC_REJECTED` audit row). **Events:** a permanent rejection is **blocking** (D-M3-33, amending D-M3-15): dropping it would break the author's chain on every receiver, so the outbox stops at it, retries it hourly and the dashboard shows "sync blocked" with the code.
- Transient errors back off exponentially (1 s to 1 h, equal jitter; `RESOURCE_EXHAUSTED` hints 60 s); `Unauthenticated` stops the push without counting an attempt.
- **Household gone** needs positive evidence (D-M3-37): the account is gone on a forced token refresh, the `hid` claim is missing after that refresh, or this device's own registry entry reads `REVOKED`. A bare permission denial is transient. Household gone wipes local data (ADR-0007 §8).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **CRDTs** (per-field registers, sequence CRDTs, Automerge-style documents) | The documents are small, mostly parent-authored records where whole-record replacement is the intended semantics; field-level merging of a reward's cost and title would create states nobody wrote. A CRDT library adds a dependency, an op log to sign and verify, and rules the server cannot mirror. The chosen order is itself a max-register over a total order, so it has the convergence property without the machinery. The money is already an append-only log. |
| **Server-authoritative sync** (a function validates every write and assigns the order) | The Spark plan has no Cloud Functions (D-M3-29), every write would need the network (against ADR-0001), and a server that decides would be trusted: someone holding the project credentials could rewrite history. Receivers must verify anyway, so the server only enforces what devices enforce. |
| **Plain LWW for submissions** | A stale `SUBMITTED` with a higher lamport (kid offline, clock ahead) would undo a parent's approval. |
| **Open envelope top level** | Forward-compatible decoding would drop unknown fields and break signature checks on older clients. |
| **Quarantine unknown or PENDING authors** | The registry mirror can lag behind the data; quarantining is final, so a device pulled in the wrong order would refuse a legitimate device forever. |
| **Apply neither conflicting copy** (ADR-0004 §6 as first written) | A kid reusing a parent's eventId could erase the parent's event on every device. |
| **Ack permanently rejected events** | Breaks the author's hash chain for every receiver and loses money movements silently. |
| **Firestore offline persistence as the local store** | No SQLCipher, no transactional outbox with the ledger, and the SDK's own conflict handling is opaque (ADR-0001). |

## Consequences

**Easier**

- Any device can audit what it holds without trusting the server: every event and document is signed by a registered device whose role it checks.
- The rules and the resolver share one definition of "wins", tested on both sides, so a refused push is never a silent divergence.
- Offline writes need nothing from the network; held data heals itself when the registry or the missing link arrives.

**Harder**

- A new top-level envelope field needs a schema version and a rules change; a new event field or type needs a rules change too (closed key whitelist, `docs/FIREBASE.md`).
- A blocked ledger event holds back everything its device queued after it until the cause is fixed (rules deploy, approval), and parents see it.
- Every pull reads the whole registry. Fine for a household; the follow-up is a timestamp cursor on registry writes (D-M3-22).
- Held rows are capped at 500 per author; an author that floods the inbox loses its oldest held rows (audited).
- Cursor correctness rests on Firestore's server timestamps being the commit order within a collection; it is exercised by the emulator transport suite, not proven.

## References

D-M3-3, D-M3-13, D-M3-14, D-M3-15, D-M3-16, D-M3-18, D-M3-22, D-M3-23, D-M3-25, D-M3-27, D-M3-30, D-M3-31, D-M3-33, D-M3-36, D-M3-37 in [`docs/DECISIONS-PENDING.md`](../DECISIONS-PENDING.md). Code: `core/sync` (`DocumentEnvelope`, `DocTypes`, `ConflictResolver`, `RemoteDocumentVerifier`, `SyncEngine`, `SyncTransport`), `core/domain-ledger` (`RemoteEventVerifier`, `IngestOrdering`, `LedgerOrdering`), `core/database` (`RoomLedgerRepository.ingest`, `RoomRemoteApplier`), `core/sync-firebase` (`FirebaseSyncTransport`), `firestore/firestore.rules`.
