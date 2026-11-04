# ADR-0011: Chat retention and pruning

- **Status:** Accepted
- **Date:** 2026-11-04
- **Milestone:** Decided and implemented in M7 (`core/database` `PruneChatUseCase`, `PurgeChatUseCase`, `ChatExportReader`, household policy v2; `server` `pruneMessages`; `firestore` rules; `core/sync-firebase` `HouseholdFunctions.pruneMessages`)
- **Related:** [ADR-0006](0006-sync-protocol-and-document-envelope.md) (sync protocol, pull cursors), [ADR-0008](0008-chat-encryption-and-thread-keys.md) (chat encryption, `message_seq`, thread keys), [ADR-0009](0009-games-scoring-and-credit.md) (the game cap that moved into the same policy), [M7 spec](../milestones/M7.md) §1 to §3, decisions D-M4-10, D-M4-17, D-M7-8 to D-M7-10, D-M7-11 to D-M7-17 and D-M7-51 to D-M7-59 (fix pass 1)

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

- every message (any status) this device received before the cut, by its own clock (D-M7-52, amending D-M7-13). Never by the author's signed `createdAt`, nor the time shown (which is that date for an author's first message): a kid phone whose clock is behind, or a modified client that backdates, would otherwise make its messages vanish on every phone right after they arrive, a self-destructing message that undercuts "you cannot erase what you said" (ADR-0008). A date ahead cannot keep a message for ever either. A newly linked phone keeps the history it pulls for one retention period from the pull, at most that much longer than the server;
- the blob cache entries only those messages named, when the cached blob is a chat blob of that thread (never a chore photo) and nothing still queued or stored names it; the files go after the transaction commits;
- the thread's read mark when it is older than the cut. Every message that stays was received at or after the cut, so it is unread with or without that mark: unread counts do not change.

It keeps `message_seq` and `thread_keys`. A removed message pulled again (another page, a re-linked cursor, a server that has not pruned yet) is below its author's expected `seq` and is ignored as a duplicate; newer messages of every epoch still decrypt. This device's own messages still queued for upload are kept until the server has them (the next prune takes them). Cache files that no index row names (a crash between the commit and the file deletion, or an interrupted write) are swept after every prune (`BlobCache.sweepOrphans`, D-M7-59). The prune is idempotent and audited `CHAT_PRUNED` with counts only.

### 3. The server copy goes through a callable

`pruneMessages({ before, purge })` on the Sprout server (device proof `sprout/prune-messages/v2`, PARENT only, rate limited) deletes with the Admin SDK, in batches of 400 and at most 5,000 documents per call (`more: true` asks for another call):

- every message the server stored (`serverReceivedAt`) before the cut, never by the author's `createdAt` (D-M7-52);
- every chat blob chunk (`threadId` set; chore photos are never touched) stored more than 10 minutes before the cut that no remaining message names. A chunk is uploaded before the message that names it, so a younger one may belong to a message still on its way (an author who went offline in between); a later prune takes it once it is that old and still unnamed (D-M7-55). Whether a page of chunks is still named is read with `in` queries, and a call reads at most 25 pages of chunks.

Without `purge`, the household must have a retention and `before` must be **no later** than `now − chatRetentionDays` (plus a 5 minute clock skew): a later cut deletes more, so a retention prune can never delete more than the policy says, while any earlier cut (a device clock behind, a retried call, a `more: true` follow-up computed a while ago) is accepted because it deletes less (D-M7-51). A phone that has just shortened the retention pushes the policy before it calls; otherwise the server, still on the longer retention, refuses the cut. `purge: true` accepts any `before` up to now. `before` may be up to 5 minutes ahead of the server and is clamped to now. Every call writes `households/{hid}/prunes/{autoId}` (`at`, `before`, `purge`, `deviceId`, `status`, counts; no content) **before** it deletes anything (`status: 'started'`) and finalises it with the counts (`'done'`, or `'failed'` with what was deleted before the failure), so a call that dies midway is still on record. Clients can neither read nor write it; it is deleted with the household. Then it raises the household's prune watermark (§5). A parent device calls the callable after its local prune; kid devices only prune locally.

### 4. "Delete chat history now"

A parent action (`PurgeChatUseCase(before)`, parent authorisation and a typed confirmation in the UI): the same local prune with the cut at now, then `pruneMessages` with `purge: true`. This phone's own older messages still queued for upload are dropped too, with their outbox rows and queued blob chunks: uploaded after the server purge, they would bring deleted history back to the server and every phone (D-M7-59). Other devices keep their local copies until their own retention removes them; nothing tells them to delete now (a synced purge instant is M8). The dialog and the outcome say exactly that: "on this phone and on Sprout's server; other phones keep their copy until their chat retention removes it" (D-M7-58).

### 5. Pulls after a prune

Pull cursors only move forward (ADR-0006). A device whose cursor is past the deleted rows never asks for them; a device whose cursor predates the prune never receives them. A newly linked device receives only what is still on the server, and only the current epoch's key (D-M4-10).

A device that had not pulled some of an author's messages before they were deleted then sees a `seq` gap: the author's next message is held (`SEQ_GAP`), and before fix pass 1 it stayed hidden for the 24 hour gap timeout (D-M4-12), so after "Delete chat history now" the purging parent's next messages could be invisible on the kid's phone for a day. The server therefore publishes a **prune watermark** on the household root document, `households/{hid}.chatPrune` (`prunedBefore`: the latest cut any prune applied, never moved back; `prunedAt`; `purgedAt` for the last purge). Members read the root already and only the server writes it, so the rules need no change. The transport reads it with every pull page that carries messages (`PullPage.chatPrunedBefore`), the applier keeps it device-locally, and `ChatApplier` skips a `SEQ_GAP` at once (audited `MESSAGE_GAP_SKIPPED` with `cause: pruned`, D-M7-53, amending D-M7-8) when the gap is one a prune can explain, judged on the server's clock only (D-M7-80, M7 audit 2 N3). Every pulled message keeps its server stamp (`serverReceivedAt`, schema 9), and the gap counts as pruned when:
- the author's last stored message before the gap was stored on the server before the watermark (its successors may have been stored before the cut too, and deleted before this device pulled them); or
- nothing of the author is stored before the gap, and either the thread has no stored message at all (a newly linked phone, or a history this phone pruned itself) or the watermark is later than the thread's earliest stored stamp (the cut reached into the history this phone holds).

A stored predecessor without a stamp (this device's own message before it came back from the server, or a row stored before schema 9) explains nothing, and neither does the phone's own receive clock: a phone clock behind the server, or an author silent for longer than this phone's local retention, no longer makes an unrelated gap look pruned. This is still an inference, not a proof that the prune deleted those `seq`s: a message withheld or lost on the server just before a cut can be skipped as pruned. Should a skipped message still turn up, it is accepted late, as for any skipped range; other gaps wait for the timeout.

### 6. Export

The server export (`exportHousehold`) is unchanged: it carries whatever is left, as ciphertext. The readable export is built on a parent device from its local copy (`ChatExportReader`, D-M7-9), so pruned messages are in neither.

## Consequences

- Retention is enforced by each device's own clock and schedule. A device that is off, or a modified client, keeps its copy longer; the server copy is bounded by the parent device's calls. This is the honest limit of an end-to-end encrypted chat: no party can delete what another device holds. "Delete chat history now" is the same: it reaches this phone and the server, not the other phones (D-M7-58).
- The server sees which messages and chunks it deleted and when (it already sees their envelopes); the prune record holds counts only.
- A message removed locally but still on the server (no parent device has called the callable yet) is never stored again by a device that already applied it, but a device that never had it (a re-link) can receive it until the server prunes.
- Lowering the retention deletes on the next prune with no undo. The settings screen says so.
- `message_seq` rows and thread keys outlive their messages. They hold no content (sequence numbers and keys for epochs still in use).
- The prune watermark tells every member when the history was last pruned or purged (an instant, no counts); members already see the messages themselves.
- An M6 build ignores the v2 policy keys when it republishes the policy (D-M7-16): it drops the retention for every device, and projects game credit with the default cap while M7 devices use the household's, so their balances differ until every phone runs M7.

## Alternatives considered

- **Client deletes under the rules** (a tombstone or a delete rule for old messages). Rejected: any member could erase what others said, and rules cannot tell a retention delete from a malicious one.
- **Server-only retention (a scheduled job).** The Spark plan has no scheduler, and devices would keep their copies anyway.
- **A purge marker in the policy** (`chatPurgeAt`) that devices obey. Rejected for M7: one more synced field to bound and audit, while a parent action plus the callable covers "delete now". Devices still apply their retention.
- **Deleting `message_seq` with the messages.** Rejected: a re-delivered old message would then look new and be stored again.
