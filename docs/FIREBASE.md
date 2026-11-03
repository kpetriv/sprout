# Firebase backend

The backend is Firestore (security rules in `firestore/`), Firebase Auth and App Check, and **the Sprout server** (TypeScript in `server/`): an HTTP service that runs the privileged operations (the callables) with the Admin SDK. Development and tests run against the **Firebase Emulator Suite** under the project id `demo-sprout`; production is the Firebase project `sprout-3e1c7` plus the server on Vercel. Design: `docs/milestones/M3.md` §1, §5 and §7.

```
firebase.json              emulator ports (Auth, Firestore), rules and indexes paths
.firebaserc                default project demo-sprout
firestore/                 firestore.rules, firestore.indexes.json, rules tests (vitest + @firebase/rules-unit-testing)
server/                    callables (src/handlers/), protocol adapter (src/callable.ts), tests (test/),
                           Vercel entry (api/index.ts, vercel.json), embedded Google attestation roots (src/attestation.ts)
```

## Architecture: why a server and not Cloud Functions (D-M3-29)

The Firebase project stays on the free **Spark** plan, which has no Cloud Functions. Everything the clients may do directly (reading and writing their household under the security rules) still goes to Firestore. Everything they may not (creating a household, registering and approving devices, link codes, revocation, export, deletion: anything that sets custom claims, writes `members`/`devices`/`parentKeys`, or touches Auth users) goes to the server, which holds a service-account key and uses `firebase-admin` exactly as the functions did.

```
Android app ──Firestore SDK──────────────────────────────► Firestore (rules)          ┐
            ──Functions SDK, base URL https://<server>───► Sprout server (Vercel)     │ project sprout-3e1c7
                POST /<callable> {"data": …}                 firebase-admin ──────────► Auth, Firestore    ┘
                Authorization: Bearer <ID token>             verifies ID token + App Check token
                X-Firebase-AppCheck: <token>
```

- **Protocol.** The server speaks the [Firebase callable protocol](https://firebase.google.com/docs/functions/callable-reference), so the client keeps the Functions SDK with a custom origin (`FirebaseFunctions.getInstance(app, "https://<server>")` posts to `https://<server>/<name>`; `core/sync-firebase` `FirebaseConfig.functionsBaseUrl`). Request: `POST /<name>`, `Content-Type: application/json`, body `{"data": …}` (Int64 wrappers decoded as the SDK sends them). Response: `200 {"result": …}` or `{"error": {"status": "<CANONICAL_CODE>", "message": "…"}}` with the HTTP status of the code (below). Unknown paths answer `404 {"error": {"status": "NOT_FOUND", …}}`; `OPTIONS` answers `204` (no CORS: browsers are not clients).
- **Adapter** (`server/src/callable.ts`). Parses and validates the body (otherwise `400 INVALID_ARGUMENT "Bad Request"`, also above 256 KB); verifies `Authorization: Bearer <ID token>` with `auth.verifyIdToken(token, checkRevoked = true)` (a malformed, expired, revoked or disabled user's token answers `401 UNAUTHENTICATED "Unauthenticated"`; no header leaves the request unauthenticated and the handler answers `401 UNAUTHENTICATED "Sign in first"`; a callable declared `authentication: 'none'`, only `deviceStatus`, ignores the header entirely: never verified, never passed on); outside the emulator requires and verifies `X-Firebase-AppCheck` with `appCheck().verifyToken` (`401 UNAUTHENTICATED` when missing or invalid); then hands the handler the same `CallableRequest` shape `firebase-functions` built (`data`, `auth: {uid, token, rawToken}`, `app`, `rawRequest: {ip, headers}`). Request objects are rebuilt without `__proto__`, `constructor` and `prototype` keys (`decode`), so request data cannot change an object's prototype. An `HttpsError` becomes the protocol error with its own message; any other error becomes `500 INTERNAL "INTERNAL"` and is logged by name and code only.
- **Status mapping** (`server/src/https.ts`, as `firebase-functions`): `invalid-argument`, `failed-precondition`, `out-of-range` 400; `unauthenticated` 401; `permission-denied` 403; `not-found` 404; `already-exists`, `aborted` 409; `resource-exhausted` 429; `cancelled` 499; `unknown`, `internal`, `data-loss` 500; `unimplemented` 501; `unavailable` 503; `deadline-exceeded` 504.
- **Host.** Vercel Hobby, Node 22 (`server/package.json` `engines`), region `fra1` (closest to the Firestore location `eur3`), one function (`api/index.ts`, Hono via `@hono/vercel`) that `vercel.json` rewrites every path to, `maxDuration` 300 s. Vercel caps a response body at 4.5 MB, hence the 4 MB export limit (`MAX_EXPORT_BYTES`). Locally the same Hono app runs under `@hono/node-server` (`npm run dev`).

## Prerequisites

- **Node 24** and npm (the server targets Node 22 on Vercel and runs on 22 or newer). Nothing is installed globally (D-M3-10): `firebase-tools` is a pinned devDependency of both `server/` and `firestore/` and runs through `npx` or the npm scripts; the Vercel CLI runs through `npx vercel@<version>` when deploying.
- **Java 21 or newer** for the emulators (`firebase-tools` 15 refuses older JVMs). On macOS with Homebrew:

  ```
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"
  ```

- The first emulator start downloads the emulator jars to `~/.cache/firebase/emulators` (CI caches that directory, keyed on the `firebase-tools` version).

## What `demo-sprout` means

A project id starting with `demo-` is a Firebase **demo project**: the emulators accept it without any login, without a real project and without network access to Google, and any SDK call aimed at a non-emulated service fails instead of reaching production. Nothing in this repository can touch a real Firebase project.

## Local development: emulators and the server

From the repository root, in two terminals:

```
npm --prefix firestore ci && npm --prefix server ci
npx --no-install --prefix firestore firebase emulators:start --only auth,firestore --project demo-sprout
npm --prefix server run dev
```

`--prefix firestore` runs the `firebase-tools` pinned in `firestore/package-lock.json` while keeping the repository root (and its `firebase.json`) as the working directory; `--no-install` stops `npx` from fetching an unpinned `firebase-tools` if the packages are not installed. Ports: Auth 9099, Firestore 8080; the Emulator UI is off. `singleProjectMode` is on, so every client must use `demo-sprout`. Debug builds of the app talk to these ports through `BuildConfig.FIREBASE_EMULATOR_HOST` (default `10.0.2.2`, M3 §5).

`npm run dev` (`tsx watch src/dev.ts`) serves the callables on **`http://127.0.0.1:8787`** and restarts on changes. It defaults `SPROUT_EMULATOR=true`, `FIREBASE_PROJECT_ID=demo-sprout`, `FIRESTORE_EMULATOR_HOST=127.0.0.1:8080` and `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099` (each overridable; `PORT` and `HOST` too). With the demo project App Check is not required, keys without attestation, or with a chain that does not end in a Google root (the Android emulator's software Keystore), are accepted and recorded as `NONE` (D-M3-5, D-M3-6), and link codes use the public development HMAC key (see "Link-code key"). The Android emulator reaches it at **`http://10.0.2.2:8787`** (the debug build's functions base URL). Smoke check:

```
curl -i -X POST localhost:8787/createHousehold -H 'content-type: application/json' -d '{"data":{}}'
# 401 {"error":{"message":"Sign in first","status":"UNAUTHENTICATED"}}
```

Emulator log files (`firebase-debug.log`, `firestore-debug.log`) land in the directory the command runs from. `server/` and `firestore/` ignore them; run from the root only with that in mind.

## Running the tests

```
cd firestore && npm ci && npm run lint && npm test     # rules tests, Firestore emulator only
cd server && npm ci && npm run lint && npm run build && npm test      # Auth + Firestore emulators
```

Each `npm test` is `firebase emulators:exec --only … --project demo-sprout "vitest run"`: it starts the emulators, runs vitest against them and shuts them down. Test files run sequentially because they share one emulator and clear it between tests.

- **Rules tests** (`firestore/test/*.test.ts`) use `initializeTestEnvironment` with `firestore.rules`, seed two households with `withSecurityRulesDisabled` and assert allowed and denied operations with `assertSucceeds`/`assertFails`.
- **Server tests** (`server/test/*.test.ts`) call every callable **over HTTP, in process** (`app.request('/<name>', …)` on the Hono app), with an ID token **minted by the Auth emulator** for the caller (an anonymous account's refreshed session, or a custom-token sign-in carrying the test's claims), so the adapter's `verifyIdToken(…, checkRevoked)` runs on every call; the Admin SDK inside the handlers talks to the real Auth and Firestore emulators. The emulator signs nothing (`alg: none`), so where a test needs a token it cannot mint (an `auth_time` from minutes ago, claims the account no longer has) the helper restates the emulator's token with those claims (`test/helpers.ts` `idTokenFor`). The client address is the socket address `@hono/node-server` would pass. There is no App Check emulator: the tests give the app a verifier that accepts one test token, and check separately that the production verifier refuses it. `test/callable.test.ts` covers the adapter itself (status mapping, error shape, malformed bodies, token and App Check refusals, unknown routes).
- The attestation parser is tested against a **self-generated** chain (fake root, fake intermediate, leaf with a hand-built key description extension; `server/test/attestationFixtures.ts`). No real attested chain can be produced offline: a chain recorded from a physical device is still needed as a fixture for the Google-rooted path.

## Firestore layout and document shapes

```
households/{hid}                       { createdBy, createdAt, fiatCurrency }                      server only
  members/{uid}                        { role: PARENT|KID, kidId?, addedAt, tokensValidAfter? }     server only
  devices/{deviceId}                   { uid, role, kidId?, publicKey, attestation, status,          server only
                                         registeredAt, approvedBy?, approvedAt?, revokedAt?,
                                         revokedAtLamport?, sessionsRevokedAt?, lamport,
                                         encryptionKey?, encryptionKeySignature? }
  parentKeys/{fingerprint}             { publicKey, uid, deviceId, validFromLamport, registeredAt,   server only
                                         attestation, revokedAtLamport? }                             (registerParentKey,
                                                                                                       revokeDevice)
  events/{eventId | eventId__hash}     ledger event, envelope flattened (below)                       create only
  kids|accounts|householdPolicy|chores|choreSubmissions|rewards|allowanceSchedules|savingsGoals|interestPolicy|threadKeys/{docId}
                                       DocumentEnvelope (below)                                       conflict rules, tombstones
  messages/{messageId}                 chat message envelope, ciphertext as bytes (below)              create only, kid reads own thread
  blobs/{blobId}.{chunk}               encrypted blob chunk, data as bytes (below)                     create only, fetched by id
  quotas/{yyyymmdd}                    { chunks, last }: blob chunks stored that UTC day (below)        +1 per chunk, same batch
linkCodes/{hmac(code)}                 { hid, kidId, createdBy, createdAt, expiresAt, used, usedAt?, usedBy? }   no client access
challenges/{uid}                       { hash, expiresAt }                                                       no client access
userHouseholds/{uid}                   { hid }   one household per uid (createHousehold, redeemLinkCode)        no client access
deletionRecords/{autoId}               { hidHash, at, counts: { members, devices, events, documents } }          no client access
rateLimits/{bucket}                    { callable, kind, hits[], expiresAt, updatedAt }                          no client access
```

`deviceId` is the lower-case hex SHA-256 of the device key's SPKI DER (the Android `DeviceId`). `encryptionKey` (base64 of the raw 32-byte X25519 key) and `encryptionKeySignature` (base64 DER) are the device's chat key and its binding (M4 §1.1, D-M4-2): the device key's signature over `"sprout/device-encryption-key/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ deviceId ‖ 0x00 ‖ key` (core/domain-ledger `EncryptionKeyBindingPayload`). The server stores them only after verifying the binding against the device's registered key (`registerEncryptionKey` or a registration callable's optional arguments); receivers verify it again and ignore a key whose binding fails. A parent key's `fingerprint` is the lower-case hex SHA-256 of its SPKI DER. `members/{uid}.tokensValidAfter` (epoch seconds) is set when one of that member's parent devices is revoked (see `revokeDevice`).

**Event documents.** The transport stores a ledger event with the `Envelope` fields at the top level (not nested under `envelope` as `LedgerJson` writes it), next to the polymorphic `type` and the event-specific fields; the receiver re-nests the envelope fields before hashing (the canonical tree is `{type, envelope: {…}, …}`). Required: `schemaVersion` (1 or 2), `eventId`, `householdId`, `kidId`, `accountId`, `authorUid`, `authorRole`, `authorDeviceId`, `createdAt` (epoch ms), `lamport`, `prevHash` (null or hex), `hash`, `signature`, `type`, `serverReceivedAt`. Optional: `parentSignature` and the event fields of every schema 1–2 event type (`currency, amount, choreId, dueOn, scheduleId, periodKey, gameId, score, playedOn, rewardId, minutesGranted, reason, toAccountId, principal, annualRateBasisPoints, days, sessionId, minutesUsed, minutes, grantedBy, causedBy, note`). Any other key is refused, so a new event type or field needs a rules change together with its schema version. `causedBy` holds 1 to 16 event ids of 1 to 128 characters from `[A-Za-z0-9._-]`; `reason` and `note` at most 500 characters.

**Documents.** Exactly the `DocumentEnvelope` fields `schemaVersion` (1), `householdId`, `docType`, `docId`, `authorUid`, `authorDeviceId`, `lamport`, `deleted`, `payload` (a map), `signature` and `serverReceivedAt`, all required. The payload of each docType has a closed key set, the serial names of its Kotlin model; every key may be absent or null (the receivers validate the model), and present values are bounded:

| docType (collection) | Payload keys (model) | Bounds |
|---|---|---|
| `kid` (`kids`) | `id, householdId, firstName, avatarId, birthYearMonth, uiTier, archived` (core/model `Kid`) | `firstName` ≤ 40, ids ≤ 128 |
| `account` (`accounts`) | `id, kidId, currency, kind` (core/domain-ledger `Account`) | ids ≤ 128 |
| `household_policy` (`householdPolicy`) | `fiatCurrency, requireParentSignature` (core/database `DocumentPayloads`) | bool flag |
| `chore` (`chores`) | `id, householdId, kidId, title, description, reward, schedule, createdAt, state` (core/domain-household `Chore`) | `title` ≤ 80, `description` ≤ 500, `reward`/`schedule` maps ≤ 2 keys, `schedule.daysOfWeek` ≤ 7 |
| `chore_submission` (`choreSubmissions`) | `id, choreId, dueOn, kidId, submittedAt, note, photoRef, status, decidedAt, decidedBy, rejectionReason` (core/model `ChoreSubmission`) | `status` ∈ SUBMITTED, REJECTED, APPROVED (required); `note`, `rejectionReason` ≤ 500; `photoRef` only `fileName` ≤ 128, `sha256` ≤ 64, `blobId` ≤ 64 (the uploaded photo blob, M4 §2) |
| `reward` (`rewards`) | `id, householdId, title, kind, cost, currency, minutesGranted, active, forKids` (core/domain-household `Reward`) | `title` ≤ 80, `cost`/`minutesGranted` integers, `forKids` ≤ 64 |
| `allowance_schedule` (`allowanceSchedules`) | `id, kidId, accountId, amount, currency, cadence, startsOn, active` (core/domain-household `AllowanceSchedule`) | `amount` integer, `cadence` map ≤ 2 keys |
| `savings_goal` (`savingsGoals`) | `id, kidId, title, target, createdOn, achievedOn` (core/domain-household `SavingsGoal`) | `title` ≤ 80, `target` integer |
| `interest_policy` (`interestPolicy`) | `householdId, annualRateBasisPoints, accrualDay` (core/model `InterestPolicy`) | integers |
| `thread_key` (`threadKeys`) | `threadId, epoch, createdByDeviceId, wraps` (core/domain-ledger `ThreadKeyDocument`); PARENT only; docId `"<threadId>.<epoch>.<createdByDeviceId>"` (one document per creator, D-M4-3) | every key required; `threadId` ≤ 128, `epoch` integer ≥ 0, `createdByDeviceId` a device id **equal to the envelope's `authorDeviceId`**; `wraps` 1 to 16 entries keyed by device id, each exactly `{ wrapped }` of 1 to 128 base64 characters; never a tombstone; `lamport` ≤ 2^50; rewritten only by the same author device |

`parent_key` (core/database `DocumentPayloads`: `fingerprint, publicKey, uid, deviceId, validFromLamport`) is not a client-writable document any more: `registerParentKey` writes `parentKeys/{fingerprint}`.

**Messages** (`messages/{messageId}`, M4 §1.3). The core/domain-ledger `MessageJson` fields at the top level, all required except `blobId`: `schemaVersion` (1), `householdId`, `threadId` (the kid id), `messageId` (the document id), `authorUid`, `authorDeviceId`, `authorRole`, `seq` (≥ 1, per author device per thread), `lamport`, `createdAt` (epoch ms), `epoch` (≥ 0), `kind` (`TEXT`, `PRESET`, `VOICE`, `PHOTO`), `ciphertext` (**Firestore bytes**, 1 to 8,192), `blobId` (null, or ≤ 128 characters exactly for VOICE and PHOTO), `signature` (≤ 200) and `serverReceivedAt`. The transport stores the ciphertext as bytes, so the rules check its real size, and reads it back as base64, which is the tree the author signed. No plaintext ever reaches Firestore.

**Blob chunks** (`blobs/{blobId}.{chunk}`, M4 §2, D-M4-5). The core/domain-ledger `BlobJson` header fields at the top level (`schemaVersion`, `householdId`, `blobId`, `threadId`, `kidId`, `submissionId`, `authorUid`, `authorDeviceId`, `lamport`, `chunk`, `chunks`, `sha256`, `epoch`, `signature`) plus `data` (**Firestore bytes**, 1 to 716,800) and `serverReceivedAt`. `chunk` is 0 or 1, `chunks` 1 or 2 (`chunk < chunks`), the document id is `"<blobId>.<chunk>"`, `sha256` hex of the whole blob ciphertext; exactly one of `threadId` (a chat blob, equal to `kidId`) and `submissionId` (a chore photo). `kidId` must name an existing `kids/{kidId}` document. Blobs are never pulled: a device reads one blob's chunks by id when it needs them (`SyncTransport.fetchBlob`); when chunk 0 is there but fewer than its `chunks` are, the result is a transient `BlobIncompleteException` (the uploader has not pushed the rest yet), so callers retry instead of declaring the blob invalid. The transport commits each chunk on its own (up to 700 KB each) together with the day's quota counter (below), in outbox order within the chat lane (D-M4-22), so a chunk reaches the server before the chat message that names its blob. A chore submission is a document in the ledger lane, so it can reach the server before its photo's chunk; receivers then find nothing stored under the blob id yet (`NOT_FOUND`, "not uploaded yet") and fetch it again later.

**Blob quota** (`quotas/{yyyymmdd}`, audit H2, D-M4-18). `{ chunks, last }`: how many blob chunks the household stored on that UTC day and the document id of the latest. Each chunk is committed in one batch with a merge of `{ chunks: increment(1), last: "<blobId>.<chunk>" }` on today's counter (`yyyymmdd` of `request.time`, UTC). The rules tie the two together: the chunk's create rule requires `getAfter(quota).last` to be its own id and `getAfter(quota).chunks <= 60`; the counter's rule requires `day == today`, `chunks == previous + 1` (a new counter starts at 1), `chunks <= 60`, keys `chunks` and `last` only, and `last` to name a chunk that does not exist before the batch and does after it. So each counter step is exactly one new chunk, a batch carries at most one chunk, the counter cannot be moved without a chunk, and a household stores at most **60 chunks per UTC day** (about 42 MB at the 700 KB maximum; a photo is one chunk, a voice note one). Members read the counter; nobody deletes it (`deleteHousehold` removes it with the household). The counter id is the **device's** UTC day, recomputed at every attempt; the rules accept only the **server's** day. **A refused chunk is never a permanent refusal** (audit A2-M3, D-M4-22): chunks are authored and validated on the device, so the rules refuse one only for the daily budget, for a counter day that is not the server's (a few minutes either side of UTC midnight, or a device whose clock or date is off), or for a revoked member (which the transport's membership check turns into "household gone" first). The transport reads the stored copy (identical: accepted) and the counter of the device's day: when it is used up, `SyncError.Transient` with a retry at the next UTC midnight (by the device's clock); otherwise `SyncError.Transient` with the engine's backoff (1 s doubling to 1 h), whose next attempt recomputes the day, so a chunk refused around midnight goes through once both clocks agree. Either way only the **chat lane** waits behind the chunk (the message naming the blob and later messages); ledger events and documents go on. A device whose date stays wrong cannot send photos or voice notes until it is corrected, and its chat lane waits (no "blocked" state, no Discard, since nothing is refused for good); its ledger sync is unaffected. The other half of the abuse protection is App Check enforcement for Firestore (production checklist, M8): the rules bound what a member can do, App Check keeps scripted clients with a member's credentials out.

**Pull cursors.** Every client write sets `serverReceivedAt` to `FieldValue.serverTimestamp()`; the rules require it and accept it only when it equals the request time.

**Push order and refusals** (`core/sync-firebase` `FirebaseSyncTransport`, review H3, M2, M3, audit A2-M3, D-M4-22). The outbox is two independent lanes: the **ledger lane** (events and documents) and the **chat lane** (messages and blob chunks). The transport writes each lane in outbox order (never regrouped by kind); the engine hands it the due items of both lanes merged by `seq`. When the rules refuse a ledger event or a chat message for good, **its lane** stops there: nothing queued behind it in that lane is sent, and the sync engine keeps the refused item at the head of the lane (retried hourly, shown as "sync blocked" on the dashboard, and as "Sending is blocked" in the chat for a message), because the next event of the hash chain or the next message `seq` would otherwise reach receivers without it. A deferred blob chunk (above) stops the chat lane the same way, as a transient wait. The other lane goes on, so a stuck photo or a refused message never holds back approvals and allowances, and a refused ledger event never holds back chat. A refused document is dropped from the outbox, with its `SYNC_REJECTED` audit row written in the same transaction (audit A2-L3), and nothing stops. A failure that is not about one item (network, timeout, session) stops both lanes. A KID session whose kid cannot be determined (no `kidId` claim, no own registry row) skips the messages lane and its listener rather than issue a query the rules refuse. A device row whose id is not the SHA-256 of its public key is dropped and counted as malformed (audit H1, D-M4-16).

## Security rules summary

- Membership and role come from `members/{uid}` only; claims and client data are never trusted by the rules.
- Every client write carries `authorUid == request.auth.uid` and an `authorDeviceId` that is an **ACTIVE** device of the caller in this household (`devices/{id}.uid == uid` and `status == ACTIVE`); PENDING, REVOKED, unregistered and other users' devices are refused.
- **Session freshness.** When the caller's member row has `tokensValidAfter`, a write needs `request.auth.token.auth_time >= tokensValidAfter`: sessions signed in before a parent-device revocation cannot write (`auth_time` is the sign-in time and survives token refresh, so the device must sign in again).
- Reads: a member reads everything under its household (the household doc, devices, parent keys, events, documents) except other members' rows (own `members/{uid}` only). Nothing top level is readable.
- **Thread keys** (audit M2). A `threadKeys` document's id is `"<threadId>.<epoch>.<createdByDeviceId>"`, its `createdByDeviceId` is the author device, it is never a tombstone, its `lamport` is at most 2^50, and an update must come from the same author device as the stored copy. Two parents rotating the same epoch therefore write two documents (receivers keep both keys); neither can overwrite the other's.
- **Blob quota.** At most 60 blob chunks per household per UTC day, each counted in `quotas/{yyyymmdd}` in the same batch (see "Blob quota" above); a chunk's `kidId` must be an existing kid.
- **Chat** (M4 §3). `messages` and `blobs` are create only (no update, no delete), written by an ACTIVE device of the caller with the session-freshness check. A PARENT-role message only from a PARENT member; a KID-role message from any member (kid mode on a parent phone, D-M3-12, as for events); a KID member writes only KID-role messages in its own thread (`threadId == kidId`) and blobs of its own kid (`kidId`). **Reads**: a PARENT member reads every thread; a KID member reads messages with `threadId == its kidId` only (a query must filter on `threadId`, which the transport does for kid sessions) and gets blobs with `kidId == its kidId` only; blobs are never listed. A get of a missing message or blob is allowed (reveals only that the id is unused). `threadKeys` is a document collection that only PARENT members write (the kid gate allows only `choreSubmissions`); members read it (each wrap is useless without its device's private key).
- Events: create only (no update, no delete); doc id `eventId`, or `eventId__hash` (a conflicting copy kept as evidence) from a PARENT member only; `householdId == hid`; `schemaVersion in [1, 2]`; integer `lamport >= 0`; `authorRole` KID from any member, PARENT only from a PARENT member; a KID member writes only for its own `kidId`.
- Documents: create and update with the author checks, `docType` matching the collection, `docId` matching the document id, `householdId == hid`. An update must win under the client `ConflictResolver` (core/sync) order:
  1. a live copy never overwrites a tombstone (`resource.deleted == false || request.deleted == true`); a PARENT may tombstone a live document whatever the lamports;
  2. two live `choreSubmissions` compare status rank first (SUBMITTED 0, REJECTED 1, APPROVED 2): a higher rank wins whatever the lamports, a lower one never does;
  3. otherwise last writer wins: `lamport` greater, or equal with a greater `authorDeviceId`.

  No deletes: a tombstone is a document with `deleted == true`. This is the same relation as `ConflictResolver` (rank first, then last-writer-wins), so a write the rules refuse as stale is one the receivers would ignore.
- Roles: a PARENT member writes every collection (`householdPolicy` is parent only). A KID member writes only `choreSubmissions`, only for its own `kidId`, only with `payload.status == SUBMITTED` and `payload.id == docId`, never a tombstone; on update only over a live, SUBMITTED copy of its own kid, keeping `id`, `choreId`, `dueOn` and `kidId`.
- `parentKeys`, `members`, `devices` (encryption keys included), `linkCodes`, `challenges`, `userHouseholds`, `deletionRecords`, `rateLimits`: no client writes.
- **Size limits are approximate.** Rules cannot measure bytes. Events (target ≤ 16 KB) are bounded by the closed key set and a length bound on every string (ids ≤ 128–256, `reason`/`note` ≤ 500, signatures ≤ 200, `causedBy` ≤ 16 ids of ≤ 128). Documents (target payload ≤ 64 KB) are bounded by the closed envelope key set, envelope string bounds and the per-docType payload key sets above; the values inside nested maps are bounded by key count only. Firestore's 1 MiB document limit is the hard ceiling; the receivers' own validation is the precise one.
- **Evaluation budget.** A request may evaluate at most 1,000 rule expressions and is denied above that; the emulator's denial reads "Unable to evaluate the expression as the maximum of 1000 expressions to evaluate has been reached". The write rules read the member row and the author device once and pass them down; `causedBy` is checked through `join(',')` and a regex instead of per-element calls. The tests "stays within the rules evaluation budget …" write the largest event, the largest kid submission update, the largest message, the largest blob chunk with its counter and the largest thread key (16 wraps, checked one index at a time with one regex each, written and re-wrapped); extend them when adding checks on those paths. **Headroom** (measured in fix pass 1 by appending `padTen()` calls, each 10 comparisons `request.time != null`, to the rule until the write was denied): the 16-wrap thread key, with the audit M2 checks, still passes with 2 such calls (20 comparisons) and is denied with 3; the largest blob chunk with its quota checks passes with 9 (90 comparisons) and is denied with 10. The thread key path is the tight one: a new check there needs the budget test re-run.
- **Batches** (review M1). Firebase documents the budget per request, and a batched write is one request. The emulator, however, evaluates each written document against its own budget: `firestore/test/batches.test.ts` commits 3 maximal events with 2 maximal documents, a 16-wrap thread key with a maximal event, and 10 maximal events, each in one batch, and all succeed (while investigating, a batch of 500 maximal events succeeded too; no denial could be produced). The suspected cause of refused batches was therefore not reproduced; production accounting cannot be tested locally, so the transport stays conservative: a batch carries one kind of item only, is cut at an estimated cost of 1,000 (`BATCH_BUDGET`: events cost 100, documents 125, messages 50, so at most 10 events, 8 documents or 20 messages), and thread keys and blob chunks always go alone. The old "400 writes per batch" constant is gone (Firestore's own limit is 500 writes). A refused batch is still retried item by item, so a wrong estimate costs round trips, not data.

## What the server and Firestore can see (chat metadata)

The chat is end-to-end encrypted, but the server (and anyone who can read the Firestore project) sees the envelope around every ciphertext:

- **Who talks in which thread**: `threadId` (the kid's id), `authorUid`, `authorDeviceId` and `authorRole` of every message and blob chunk; thread key documents name their thread, epoch and creator device.
- **Membership over time**: the device ids in each epoch's `wraps` (who could read that epoch), the epochs themselves (a new epoch follows a device removal), and every device's registered encryption key (device rows).
- **When**: `createdAt` (the author's clock), `serverReceivedAt` (the server's), and `lamport`; message timing reveals activity patterns.
- **How much and what kind**: `kind` (TEXT, PRESET, VOICE, PHOTO), the ciphertext size of each message (a size class once the client pads plaintexts to buckets, audit M3, ADR-0008; the exact length before that), each blob's chunk count, chunk sizes and whole-blob `sha256`, and which message names which blob (`blobId`).
- **Order and volume**: per-device per-thread `seq` (how many messages a device has sent in a thread) and the daily blob counter (`quotas/{yyyymmdd}`: how many photos and voice notes the household sent that day).
- **Chore photos**: a chore-photo blob's `submissionId` and `kidId` link it to the submission document, which itself is not encrypted.
- **Reads**: which device fetched which blob and when (request logs of the hosting project), not what it contained.

None of the plaintexts, thread keys or device private keys are ever stored; exports (`exportHousehold`) carry the same envelopes and nothing more.

## Callables

All callables are served by the server at `POST /<name>` (protocol and adapter above), require App Check outside the emulator (in the adapter, plus the same check in the handler's guard), answer with `HttpsError` codes whose messages carry no household content, and log ids and counts only (`server/src/log.ts`, one JSON line per event on stdout/stderr, is the only logging path; ESLint enforces it). Every PARENT callable also refuses (`unauthenticated`) a session whose `auth_time` is older than the member's `tokensValidAfter`. `deviceStatus` is the only callable without an ID token.

| Callable | Caller | Result |
|---|---|---|
| `createHousehold({ fiatCurrency, devicePublicKey, attestationChain?, encryptionKey?, encryptionKeySignature? })` | signed in, not anonymous, no household | `{ hid, deviceId }`; household, PARENT member, ACTIVE device, `userHouseholds/{uid}`, claims `{ hid, role: PARENT }`. The optional encryption key is stored only if its binding verifies for the new household (`permission-denied` otherwise, nothing created); since the id is assigned here, clients register their key afterwards with `registerEncryptionKey` |
| `beginDeviceRegistration()` | signed in | `{ challenge }` (32 random bytes, base64); `challenges/{uid}` stores its SHA-256 for 10 minutes |
| `registerParentDevice({ devicePublicKey, attestationChain?, encryptionKey?, encryptionKeySignature? })` | PARENT | `{ deviceId, fingerprint }`; device PENDING; fingerprint = first 16 hex characters in groups of 4; the optional encryption key must be bound by the submitted device key to the caller's household and the new device id (`permission-denied` otherwise; both fields or neither, else `invalid-argument`) |
| `approveDevice({ deviceId, approverDeviceId, signature })` | PARENT; approver is its ACTIVE device | `{ deviceId, status: ACTIVE }` |
| `registerParentKey({ publicKey, attestationChain?, deviceId, signature })` | PARENT; `deviceId` is its ACTIVE device | `{ fingerprint, validFromLamport }`; `parentKeys/{fingerprint}` with `attestation`; `validFromLamport` = highest lamport of the household's events, documents, messages and blob chunks + 1 (read in the write transaction); an attested key must be TEE or StrongBox and require a biometric for every use; unattested only in the emulator or with `ALLOW_UNATTESTED=true` |
| `registerEncryptionKey({ publicKey, bindingSignature, deviceId, signature })` | PARENT or KID member (member row confirmed, session not older than `tokensValidAfter`); `deviceId` is its ACTIVE device (any role) | `{ deviceId, created }`; a low-order X25519 key (the all-zero key, 1, the two order-8 points, p − 1, p, p + 1, with the unused top bit ignored; RFC 7748 §6.1, libsodium's list) is `invalid-argument` here and in every registration callable's optional `encryptionKey` (audit L1); stores `encryptionKey` (the raw 32-byte X25519 key, base64) and `encryptionKeySignature` (= `bindingSignature`) on the device row after verifying the binding against the device key (`permission-denied` if not); the same key again answers `created: false` and changes nothing; a different key already registered is `failed-precondition` (a changed key needs a new device registration, D-M4-2) |
| `createLinkCode({ kidId, callerDeviceId, signature })` | PARENT; kid exists | `{ code, expiresAt }` (8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, 10 minutes, epoch ms); at most 5 live codes per household |
| `redeemLinkCode({ code, devicePublicKey, attestationChain?, encryptionKey?, encryptionKeySignature? })` | anonymous, no claims, no `userHouseholds` row | `{ hid, kidId, deviceId }`; code marked used, KID member, ACTIVE device, `userHouseholds/{uid}`, claims `{ hid, role: KID, kidId }`; wrong, used and expired codes all answer `not-found`. The optional encryption key is checked in the transaction against the code's household before anything is written (`permission-denied` leaves the code unused); kid devices normally register it afterwards with `registerEncryptionKey` |
| `revokeDevice({ deviceId, callerDeviceId, signature })` | PARENT; `callerDeviceId` is its ACTIVE device and differs from `deviceId` | `{ deviceId, revokedAtLamport }` = highest lamport over the device's events, **documents in every collection, messages and blob chunks** + 1 (1 when it wrote nothing; `internal` if a stored lamport is not a safe integer); also stamped on its `parentKeys`. A kid device: user disabled, refresh tokens revoked, membership deleted. A parent device: the owner's refresh tokens revoked and `members/{uid}.tokensValidAfter` set, which signs every session of that parent out, **the calling device included** (it signs in again) |
| `exportHousehold({ callerDeviceId, signature })` | PARENT | `{ exportedAt, household, members, devices, parentKeys, collections: { <name>: [...] }, events, messages, blobs }` (timestamps as ISO strings, bytes as base64); `messages` are the stored envelopes with their ciphertext, read in pages of 500 ordered by (lamport, id), `blobs` the chunk headers without `data` (D-M4-6; nothing is decrypted); `resource-exhausted` above 4 MB (the host's response limit is 4.5 MB) or above 5,000 messages ("The chat history has more than 5000 messages …", D-M4-17) |
| `deleteHousehold({ confirmation, callerDeviceId, signature })` | PARENT; `confirmation == hid`; valid device proof; signed in within the last 5 minutes (see below) | `{ deleted: true }`; first writes one `deletionRecords` row with `sha256(hid)` and counts (so a deletion that fails half-way still answers `householdGone` to `deviceStatus`), then deletes `members/*`, `households/{hid}` recursively, the household's link codes, and for every uid in `members` ∪ `devices.uid` its challenge, `userHouseholds` row and Auth user |
| `deviceStatus({ hid, deviceId, signature })` | **no ID token** (any `Authorization` header is ignored); App Check outside the emulator; `signature` by the device key itself | `{ status, householdGone }`. Household or device missing: `{ status: "UNKNOWN", householdGone }`, `householdGone` true only when a `deletionRecords` row with `sha256(hid)` exists. Otherwise the signature must verify with the stored key (`permission-denied` if not), whatever the status, and the answer is `{ status: "ACTIVE" \| "PENDING" \| "REVOKED", householdGone: false }`. Nothing else is revealed. For a device that can no longer sign in (a revoked kid's disabled user, a deleted household's deleted users) to learn why |

The link code is never stored in clear (only its HMAC) and never logged.

**`deleteHousehold` and recent sign-in.** The ID token's `auth_time` (the sign-in time, kept across token refreshes) must be at most **5 minutes** old (`server/src/config.ts` `RECENT_SIGN_IN_MS`). Otherwise the answer is `failed-precondition` with the message "Sign in again to delete the household", and nothing is deleted. It is the only `failed-precondition` `deleteHousehold` gives after its argument checks (a wrong `confirmation` is `invalid-argument`, a bad device proof `permission-denied`, both checked first), so the client maps `FunctionsError.FailedPrecondition` from this call to "sign in again", then repeats it with a new proof. A token refresh does not reset `auth_time`; only a new sign-in does.

### Signed requests (device proof, T7/T8)

Privileged callables carry a signature by one of the caller's **ACTIVE parent devices** (`registerEncryptionKey`: by the registering device itself, parent or kid) (`devices/{id}` with the caller's uid, role PARENT, status ACTIVE): ECDSA P-256 with SHA-256, DER encoded, base64 (what Android's `SHA256withECDSA` produces with the device key). The signed bytes are UTF-8 strings and the raw 32-byte token hash joined by single `0x00` bytes:

| Callable | Device field | Signed bytes |
|---|---|---|
| `approveDevice` | `approverDeviceId` | `"sprout/approve-device/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ {"deviceId":"<deviceId>"}` |
| `registerParentKey` | `deviceId` | `"sprout/register-parent-key/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ publicKey` (the raw SPKI DER bytes, not base64) |
| `createLinkCode` | `callerDeviceId` | `"sprout/create-link-code/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ {"kidId":"<kidId>"}` |
| `revokeDevice` | `callerDeviceId` | `"sprout/revoke-device/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ {"deviceId":"<deviceId>"}` |
| `exportHousehold` | `callerDeviceId` | `"sprout/export-household/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ {}` |
| `deleteHousehold` | `callerDeviceId` | `"sprout/delete-household/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ {"confirmation":"<hid>"}` |
| `registerEncryptionKey` | `deviceId` (the device itself, PARENT or KID) | `"sprout/register-encryption-key/v2" ‖ 0x00 ‖ hid ‖ 0x00 ‖ sha256(idToken) ‖ 0x00 ‖ publicKey` (the raw 32 key bytes, not base64); the key's binding travels separately as `bindingSignature` |
| `deviceStatus` (no ID token) | `deviceId` (the device asking) | `"sprout/device-status/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ deviceId` |

`hid` is the caller's household id (from its claims). `sha256(idToken)` is the SHA-256 of the raw ID token the request carries in `Authorization: Bearer <idToken>` (the compact JWT's UTF-8 bytes, `server/src/crypto.ts` `idTokenHash`), as 32 raw bytes. The JSON part is the callable's other arguments (everything except the device field and `signature`) as canonical JSON: keys sorted by UTF-16 code unit, no whitespace, strings as `JSON.stringify` writes them (`server/src/crypto.ts` `canonicalJson`; the arguments are ASCII ids, so there is nothing to escape). The server rebuilds it from the arguments it uses, so unknown extra arguments are ignored and not signed. Binding `hid` and the domain stops a signature from being replayed in another household or for another callable; binding the ID token (version 2, audit Low 2) stops a captured request from being replayed with any other token, so it is useless once its token expires (one hour at most) or from another session. Within that token's lifetime a replay of the identical request is still accepted, which only repeats what the device asked for (and is rate limited). A missing or malformed signature is `invalid-argument`; a wrong signer, a non-ACTIVE or foreign device, or a signature over other arguments or another token is `permission-denied`. The version 1 payloads are no longer accepted.

`deviceStatus` is not bound to a token (there is none); its signature only proves the asking device holds the registered key, and the answer is that device's status alone.

**UNKNOWN answers are unsigned.** When the household or the device does not exist there is no key to check, so `{ status: "UNKNOWN", householdGone }` is answered without verifying anything. Anyone with App Check (or anyone, in the emulator) can therefore ask about any `(hid, deviceId)` pair and learn two things: whether that device is registered in that household (a registered one demands a valid signature, so a bad signature answers `permission-denied` instead of UNKNOWN), and whether a household with that id was deleted (`householdGone`, from `deletionRecords`). Household ids are random and never shown, and device ids are key fingerprints, so this needs ids the asker already has; it reveals nothing else (no role, uid, status of a registered device, or timestamps). The per-IP limit bounds such probing. Accepted (audit L2): the alternative, answering `permission-denied` for unknown pairs too, would hide a deleted household from the very devices that must learn about it.

**Client side** (`core/sync-firebase`). `DeviceProof` builds the same bytes (`*Payload(hid, idTokenHash, …)`, `deviceStatusPayload(hid, deviceId)`; golden bytes from the server functions in `DeviceProofTest`). The hash comes from `IdTokenHashSource.currentIdTokenHash()` (`FirebaseAuthSession`): it forces a token refresh and returns only the new token's hash, never the token. The Functions SDK then sends that cached token (it refreshes only near expiry, an hour later). Should something else refresh the token in between, the server answers `permission-denied`, and the app's `HouseholdAdminFunctions` signs once more over a new token and resends (one retry; one proof call at a time). `deviceStatus` does not go through the SDK, which fails a call when the signed-in user's token cannot be read (a disabled or deleted user): `CallableInvoker.callWithoutAuth` posts the callable protocol itself, with the App Check token when there is one and no `Authorization` header (`HouseholdFunctions.deviceStatus`).

### Link-code key

Link-code ids are `HMAC-SHA256(LINK_CODE_HMAC_KEY, code)` in hex (`server/src/config.ts` `linkCodeKey`). The key is the server's `LINK_CODE_HMAC_KEY` environment variable (a sensitive Vercel variable); outside the emulator a missing key or one shorter than 32 characters makes `createLinkCode` and `redeemLinkCode` answer `internal`. A plain hash would let anyone with a copy of the database recover live codes by hashing all 32⁸ codes; the HMAC id is useless without the key. Rotating the key invalidates the outstanding codes (at most 10 minutes' worth).

In the emulator (`SPROUT_EMULATOR=true` **and** a `demo-` project id) the server uses `LINK_CODE_HMAC_KEY` from the environment if set, else the fixed, public development key `sprout-emulator-only-link-code-key-not-a-secret`. It protects nothing and exists so the emulator needs no secret setup.

## Rate limits

Sliding windows stored in `rateLimits/{bucket}` (`server/src/rateLimit.ts`). The bucket id is `<callable>_<kind>_<first 32 hex of sha256(callable|kind|key)>`, so uids, IP addresses and code ids are never stored in clear. A refused call is not recorded. Exceeding a limit answers `resource-exhausted`.

| Callable | Per uid | Per IP | Other |
|---|---|---|---|
| `createHousehold` | 5 / hour | 20 / hour | |
| `beginDeviceRegistration` | 10 / hour | 30 / hour | |
| `registerParentDevice` | 5 / hour | 20 / hour | |
| `approveDevice` | 20 / hour | 60 / hour | |
| `registerParentKey` | 5 / hour | 20 / hour | |
| `registerEncryptionKey` | 5 / hour | 20 / hour | |
| `createLinkCode` | 20 / hour | 60 / hour | 5 live codes per household |
| `redeemLinkCode` | 10 / hour | 10 / hour | 20 / hour per code id (the HMAC); counted before the code is looked up |
| `revokeDevice` | 30 / hour | 60 / hour | |
| `exportHousehold` | 5 / hour | 10 / hour | |
| `deleteHousehold` | 3 / hour | 10 / hour | |
| `deviceStatus` | — (no user) | 120 / hour, counted before anything is read | 30 / hour per (hid, device id), counted only once the signature verified, so unsigned requests cannot use up a device's own allowance |

**Client address.** When the request has an `X-Forwarded-For` header the **right-most** hop is used: Vercel's edge sets the header to the address it accepted the connection from (a proxy that appends would add it on the right), so that hop is the one a client cannot choose (hops to its left are client-supplied and ignored). Without the header, the socket address (`rawRequest.ip`, local development). IPv4-mapped IPv6 counts as IPv4; IPv6 is keyed per **/64**, since one subscriber usually holds a whole /64. Requests with no usable address share **one** bucket per callable (`kind` `noip`) whose limit is the per-IP limit divided by 5 (at least 1), e.g. 2 / hour for `redeemLinkCode`.

Caveat: the right-most hop is the client only when the server is reached directly through Vercel's edge. If a further proxy is ever put in front (a custom proxy, a load balancer, Cloudflare), the right-most hop becomes that proxy and every client would share its bucket; the hop count must then be configured to skip the trusted proxies. Carrier-grade NAT also puts many users behind one IPv4 address, which the per-uid limits and the low call volume make acceptable.

## Key attestation

`server/src/attestation.ts` verifies the chain a device sends at registration (`@peculiar/x509` with `@peculiar/asn1-android`): every certificate in its validity period, each signed by the next, the last one signed by an embedded **Google hardware attestation root** (the RSA-4096 root, whose key all earlier RSA roots share, and the ECDSA P-384 "Key Attestation CA1"; source and fingerprints in the file), every certificate after the leaf a CA (`basicConstraints` cA true, and `keyCertSign` when it has a `keyUsage` extension), the key description extension (`1.3.6.1.4.1.11129.2.1.17`) present on the leaf only, no certificate on Google's revocation list, the extension carrying the caller's outstanding challenge, and an EC P-256 signing key generated in the keystore (purpose SIGN, algorithm EC, curve P-256, origin GENERATED, from the hardware-enforced list for TEE and StrongBox). The leaf's SPKI must equal the submitted key, otherwise the registration is refused. The level is recorded as `STRONGBOX`, `TEE` or `SOFTWARE`.

- **CA checks.** Without them, a genuine attested leaf (whose private key an attacker on a rooted or emulated device can use) could sign a forged "leaf" with any key description; every signature in `[forged, real leaf, intermediate, root]` verifies. The test "rejects a forged leaf signed by a real attested (non-CA) leaf" covers it.
- **Boot state.** `rootOfTrust.verifiedBootState` is read and logged (`attestation.verified`, `verifiedBootState`, `bootVerified` true for VERIFIED or SELF_SIGNED) but not enforced.
- **Parent keys** (`registerParentKey`) additionally require a TEE or StrongBox key whose hardware-enforced list has `USER_AUTH_TYPE` including biometric (bit 2), no `NO_AUTH_REQUIRED`, and `AUTH_TIMEOUT` absent or 0 (authentication for every use; Keystore omits the tag for a zero timeout).
- **Revocation list.** `https://android.googleapis.com/attestation/status` is fetched with a 24-hour in-memory cache per instance (a warm Vercel instance keeps it; a cold start fetches again). If it cannot be fetched, the last cached list is used, or the chain is **allowed** with a warning log (`attestation.statusList.unavailable`): an outage of Google's endpoint must not block every registration, at the cost of accepting a certificate revoked during the outage.
- **No chain.** Accepted as `attestation: NONE` only in the emulator or with `ALLOW_UNATTESTED=true`; otherwise `failed-precondition` (D-M3-5).
- **Chain without a Google root.** Where unattested keys are allowed (the emulator, or `ALLOW_UNATTESTED=true`), a chain that passes every check except the trust anchor (`UNTRUSTED_ROOT`: the Android emulator's software Keystore chains to a software root) is recorded as `attestation: NONE` with a warning log (`attestation.unverified`, `reason: UNTRUSTED_ROOT`, `keyId`), since nothing in it is vouched for; the key policy (hardware, biometric per use) is therefore not applied to it. The challenge must still be outstanding and carried by the leaf, the leaf key must still be the submitted key, and the challenge is consumed. Every other failure (wrong challenge, bad signature, expired, revoked, key mismatch, a policy violation on a verified chain) is refused in every mode, and with `ALLOW_UNATTESTED` unset outside the emulator `UNTRUSTED_ROOT` is refused too (`permission-denied`).
- **Emulator detection.** The relaxations (App Check, unattested keys, the development link-code key) apply only when `SPROUT_EMULATOR=true` **and** the project id starts with `demo-`, so a stray `SPROUT_EMULATOR` in a real project changes nothing.
- **Roots.** When Google publishes a new root, add its PEM to `GOOGLE_ATTESTATION_ROOTS_PEM` with its source and fingerprint.

## Server environment

| Variable | Where | Meaning |
|---|---|---|
| `FIREBASE_SERVICE_ACCOUNT_JSON` | production (sensitive) | The service account key file's JSON, raw or base64 encoded (`base64 -i key.json`). The Admin SDK credential; its `project_id` is the project unless `FIREBASE_PROJECT_ID` is set. Alternatively `GOOGLE_APPLICATION_CREDENTIALS` (a key file path) for a host with a file system. Without either, outside the emulator, the server refuses to start. |
| `FIREBASE_PROJECT_ID` | production (optional), development | The Firebase project id (`sprout-3e1c7`; `demo-sprout` locally, the `npm run dev` default). Falls back to `GCLOUD_PROJECT` / `GOOGLE_CLOUD_PROJECT`. |
| `LINK_CODE_HMAC_KEY` | production (sensitive, required) | The link-code HMAC key, at least 32 characters: `openssl rand -base64 32`. |
| `ALLOW_UNATTESTED` | production (optional) | `true` accepts device and parent keys without an attestation chain (recorded `NONE`). Leave unset (or `false`) in production (D-M3-5). |
| `SPROUT_EMULATOR` | development and tests only | `true` with a `demo-` project id relaxes App Check, attestation and the link-code key (above). Never set it on Vercel; with a real project id it has no effect anyway. |
| `FIRESTORE_EMULATOR_HOST`, `FIREBASE_AUTH_EMULATOR_HOST` | development and tests only | Point the Admin SDK at the emulators (`npm run dev` defaults them; `firebase emulators:exec` sets them for the tests). Never set them on Vercel: the server **refuses to start** when either is set without the emulator mode (`SPROUT_EMULATOR=true` and a `demo-` project id; `server/src/config.ts` `assertEmulatorEnvironment`, audit Low 7), since the Admin SDK would otherwise accept the emulator's unsigned tokens for a real project. |
| `PORT`, `HOST` | development only | The `npm run dev` listener, default `127.0.0.1:8787`. |

`server/.env*` and `.vercel/` are gitignored; keys never go into the repository.

### Service account (least privilege)

The default `firebase-adminsdk-…@sprout-3e1c7.iam.gserviceaccount.com` account has the broad *Firebase Admin SDK Administrator Service Agent* role. Give the server its own account with only what the callables use:

```
gcloud iam service-accounts create sprout-server --project sprout-3e1c7 --display-name "Sprout server (Vercel)"
SA=sprout-server@sprout-3e1c7.iam.gserviceaccount.com
gcloud projects add-iam-policy-binding sprout-3e1c7 --member serviceAccount:$SA --role roles/datastore.user
gcloud projects add-iam-policy-binding sprout-3e1c7 --member serviceAccount:$SA --role roles/firebaseauth.admin
gcloud iam service-accounts keys create sprout-server-service-account.json --iam-account $SA
```

- `roles/datastore.user`: Firestore reads, writes, transactions and recursive deletes.
- `roles/firebaseauth.admin`: custom claims, `getUser` (the revocation check of `verifyIdToken`), `revokeRefreshTokens`, disabling and deleting users.
- App Check: verifying a token needs **no role** (the Admin SDK checks it against App Check's public JWKS). Only if replay protection (`consume`) is ever turned on does the account need *Firebase App Check Token Verifier* (`roles/firebaseappcheck.tokenVerifier`).
- ID-token verification uses Google's public certificates and needs no role either.

Store the key in Vercel (below), then delete the local file (the root `.gitignore` ignores `*-service-account*.json`, but it should not outlive the upload). Rotate it by creating a new key, updating the variable, redeploying and deleting the old key (`gcloud iam service-accounts keys list/delete`).

### Deploying the server (Vercel)

From `server/` (the Vercel project's root directory), with the Vercel CLI through `npx` (pinned like every tool, D-M3-10; `base64 -i` is the macOS form, `base64 -w0` on Linux):

```
npx vercel@64.7.0 link                                  # once: create or pick the Vercel project, root directory server/
base64 -i sprout-server-service-account.json | npx vercel@64.7.0 env add FIREBASE_SERVICE_ACCOUNT_JSON production --sensitive
printf %s sprout-3e1c7     | npx vercel@64.7.0 env add FIREBASE_PROJECT_ID production
openssl rand -base64 32 | tr -d '\n' | npx vercel@64.7.0 env add LINK_CODE_HMAC_KEY production --sensitive
npx vercel@64.7.0 --prod                                  # build (npm run build) and deploy
```

`vercel.json` sets no framework (plain functions), `npm ci` and `npm run build`, region `fra1`, `maxDuration` 300 s for `api/index.ts`, and rewrites every path to it; `public/` holds only `robots.txt`, so no source file is served statically. The production URL (`https://<project>.vercel.app` or a custom domain) is the release build's functions base URL. Smoke check after a deploy: the `curl` above against the production URL answers `401 UNAUTHENTICATED` (no App Check token).

### Firestore rules and indexes

Rules and indexes deploy with the Firebase CLI, no Functions involved (Spark is enough):

```
npx --no-install --prefix firestore firebase deploy --only firestore --project sprout-3e1c7
```

Deploy the indexes before relying on `revokeDevice` and kid chat: `revokeDevice` and `registerParentKey` query every event, document, message and blob collection on `(authorDeviceId, lamport desc)`, and a kid session pulls and watches `messages` on `(threadId, serverReceivedAt asc|desc)`; the composite indexes in `firestore/firestore.indexes.json` must be built first (the emulator does not enforce indexes).

## Production project checklist

1. The Firebase project `sprout-3e1c7` stays on **Spark** (D-M3-29). Firestore location **`eur3`** (multi-region Europe), chosen when the database is created; it cannot be changed later (D-M3-8).
2. Add the Android app `dev.kpetriv.sprout` (and the debug suffix if used), download `google-services.json` to `app/` (it is gitignored; never commit it; CI injects it from a secret). The app applies the `google-services` plugin only when the file exists.
3. Enable Authentication providers: Google, and Anonymous (kid devices).
4. App Check: register the app with the **Play Integrity** provider and **enforce** App Check for Firestore in the console; the server enforces it itself for the callables. Enforcement for Firestore is the second half of the blob budget (audit H2, D-M4-18): the rules cap a member at 60 chunks a day, App Check keeps clients that are not the app out.
5. Create the server's service account (above), set the server environment in Vercel and deploy it; leave `ALLOW_UNATTESTED` unset.
6. Firestore TTL policies: enable TTL on `expiresAt` for the collection groups `linkCodes`, `challenges` and `rateLimits`.
7. Deploy rules and indexes (above). Deploy credentials for CI, if rules ever deploy from CI, belong to a separate service account limited to rules and index administration (for example `roles/firebaserules.admin` and `roles/datastore.indexAdmin`) and live in GitHub Actions secrets, never in the repository.
8. Set budget alerts and error alerting (Vercel's function logs and usage; T18, PLAN "Availability and operations"), and update `docs/PRIVACY.md` with the regions (Firestore `eur3`, server `fra1`).
