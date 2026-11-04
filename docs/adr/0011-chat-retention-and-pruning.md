# ADR-0011: Chat retention and pruning

- **Status:** Accepted
- **Date:** 2026-11-04
- **Milestone:** Decided and implemented in M7 (`core/database` `PruneChatUseCase`, `PurgeChatUseCase`, `ChatExportReader`, household policy v2; `server` `pruneMessages`; `firestore` rules; `core/sync-firebase` `HouseholdFunctions.pruneMessages`)
- **Related:** [ADR-0006](0006-sync-protocol-and-document-envelope.md) (sync protocol, pull cursors), [ADR-0008](0008-chat-encryption-and-thread-keys.md) (chat encryption, `message_seq`, thread keys), [ADR-0009](0009-games-scoring-and-credit.md) (the game cap that moved into the same policy), [M7 spec](../milestones/M7.md) §1 to §3, decisions D-M4-10, D-M4-17, D-M7-8 to D-M7-10 and D-M7-11 to D-M7-17

## Context

Chat messages and their media (photos, voice notes) were kept for ever: on every device in `messages` and the blob cache, and on the server in `households/{hid}/messages` and `blobs`. The privacy policy promises parents a retention choice and a way to delete the history. Three constraints shape how:

- **Message and blob documents are create-only under the rules.** That is what keeps a member from rewriting or deleting what another member said (ADR-0008). Clients therefore cannot delete server copies, and loosening the rules to allow it would let any member erase evidence.
- **Every device holds its own copy.** The plaintext lives only on devices (end-to-end encryption); a server-side deletion does not reach them, and the server cannot read what it deletes.
- **Receivers verify per-author sequence numbers.** `message_seq` decides whether a pulled message is new, a duplicate, a gap or a replay, and thread keys decide what newer messages decrypt with. Pruning must not make a deleted message look new again, and must not break later messages.

## Decision

### 1. Retention is synced household policy

`household_policy` gains `chatRetentionDays` (1 to 3650, null keeps messages) and `dailyGameCapMinor` (0 to 10,000, null is the default 50) in schema version 8 (D-M7-10). The document stays parent-only (rules, `RemoteDocumentVerifier`'s writer role, and `HouseholdPolicyRepository` refuses a KID session); the rules bound both values and close the payload key set. A null setting is left out of the payload, so a household that never sets one keeps the v1 document byte for byte.

### 2. Each device prunes its own copy

`PruneChatUseCase(now)` runs daily and after every successful sync on every device (the app's `ChatPruneScheduler`). With a retention set, it removes, per thread in one transaction:

- every message (any status) whose date is before the cut, the date being the signed `createdAt` or, when that is later, the time this device received it (a clock ahead or a forged future date cannot keep a message for ever, D-M7-13);
- the blob cache entries only those messages named, when the cached blob is a chat blob of that thread (never a chore photo) and nothing still queued or stored names it; the files go after the transaction commits;
- the thread's read mark when it is older than the cut. Every message that stays was received at or after the cut, so it is unread with or without that mark: unread counts do not change.

It keeps `message_seq` and `thread_keys`. A removed message pulled again (another page, a re-linked cursor, a server that has not pruned yet) is below its author's expected `seq` and is ignored as a duplicate; newer messages of every epoch still decrypt. This device's own messages still queued for upload are kept until the server has them (the next prune takes them). The prune is idempotent and audited `CHAT_PRUNED` with counts only.

### 3. The server copy goes through a callable

`pruneMessages({ before, purge })` on the Sprout server (device proof `sprout/prune-messages/v2`, PARENT only, rate limited) deletes with the Admin SDK, in batches of 400 and at most 5,000 documents per call (`more: true` asks for another call):

- every message whose `createdAt` **or** `serverReceivedAt` is before the cut: a superset of what devices prune, so the server never keeps longer than the devices;
- every chat blob chunk (`threadId` set; chore photos are never touched) stored before the cut that no remaining message names.

Without `purge`, the household must have a retention and `before` must be no earlier than `now − chatRetentionDays` (less a 5 minute clock skew), so a retention prune can never delete more than the policy says. `purge: true` accepts any `before` up to now. `before` may be up to 5 minutes ahead of the server and is clamped to now. Every call writes `households/{hid}/prunes/{autoId}` (`at`, `before`, `purge`, `deviceId`, counts; no content), which clients can neither read nor write; it is deleted with the household. A parent device calls the callable after its local prune; kid devices only prune locally.

### 4. "Delete chat history now"

A parent action (`PurgeChatUseCase(before)`, parent authorisation and a typed confirmation in the UI): the same local prune with the cut at now, then `pruneMessages` with `purge: true`. Other devices keep their local copies until their own retention removes them; the parent is told so (D-M7-8).

### 5. Pulls after a prune

Pull cursors only move forward (ADR-0006). A device whose cursor is past the deleted rows never asks for them; a device whose cursor predates the prune simply never receives them. A newly linked device receives only what is still on the server, and only the current epoch's key (D-M4-10).

### 6. Export

The server export (`exportHousehold`) is unchanged: it carries whatever is left, as ciphertext. The readable export is built on a parent device from its local copy (`ChatExportReader`, D-M7-9), so pruned messages are in neither.

## Consequences

- Retention is enforced by each device's own clock and schedule. A device that is off, or a modified client, keeps its copy longer; the server copy is bounded by the parent device's calls. This is the honest limit of an end-to-end encrypted chat: no party can delete what another device holds.
- The server sees which messages and chunks it deleted and when (it already sees their envelopes); the prune record holds counts only.
- A message removed locally but still on the server (no parent device has called the callable yet) is never stored again by a device that already applied it, but a device that never had it (a re-link) can receive it until the server prunes.
- Lowering the retention deletes on the next prune with no undo. The settings screen says so.
- `message_seq` rows and thread keys outlive their messages. They hold no content (sequence numbers and keys for epochs still in use).

## Alternatives considered

- **Client deletes under the rules** (a tombstone or a delete rule for old messages). Rejected: any member could erase what others said, and rules cannot tell a retention delete from a malicious one.
- **Server-only retention (a scheduled job).** The Spark plan has no scheduler, and devices would keep their copies anyway.
- **A purge marker in the policy** (`chatPurgeAt`) that devices obey. Rejected for M7: one more synced field to bound and audit, while a parent action plus the callable covers "delete now". Devices still apply their retention.
- **Deleting `message_seq` with the messages.** Rejected: a re-delivered old message would then look new and be stored again.
