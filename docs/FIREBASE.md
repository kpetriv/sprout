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
- **Adapter** (`server/src/callable.ts`). Parses and validates the body (otherwise `400 INVALID_ARGUMENT "Bad Request"`, also above 256 KB); verifies `Authorization: Bearer <ID token>` with `auth.verifyIdToken(token, checkRevoked = true)` (a malformed, expired, revoked or disabled user's token answers `401 UNAUTHENTICATED "Unauthenticated"`; no header leaves the request unauthenticated and the handler answers `401 UNAUTHENTICATED "Sign in first"`); outside the emulator requires and verifies `X-Firebase-AppCheck` with `appCheck().verifyToken` (`401 UNAUTHENTICATED` when missing or invalid); then hands the handler the same `CallableRequest` shape `firebase-functions` built (`data`, `auth: {uid, token, rawToken}`, `app`, `rawRequest: {ip, headers}`). An `HttpsError` becomes the protocol error with its own message; any other error becomes `500 INTERNAL "INTERNAL"` and is logged by name and code only.
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
                                         revokedAtLamport?, sessionsRevokedAt?, lamport }
  parentKeys/{fingerprint}             { publicKey, uid, deviceId, validFromLamport, registeredAt,   server only
                                         attestation, revokedAtLamport? }                             (registerParentKey,
                                                                                                       revokeDevice)
  events/{eventId | eventId__hash}     ledger event, envelope flattened (below)                       create only
  kids|accounts|householdPolicy|chores|choreSubmissions|rewards|allowanceSchedules|savingsGoals|interestPolicy/{docId}
                                       DocumentEnvelope (below)                                       conflict rules, tombstones
linkCodes/{hmac(code)}                 { hid, kidId, createdBy, createdAt, expiresAt, used, usedAt?, usedBy? }   no client access
challenges/{uid}                       { hash, expiresAt }                                                       no client access
userHouseholds/{uid}                   { hid }   one household per uid (createHousehold, redeemLinkCode)        no client access
deletionRecords/{autoId}               { hidHash, at, counts: { members, devices, events, documents } }          no client access
rateLimits/{bucket}                    { callable, kind, hits[], expiresAt, updatedAt }                          no client access
```

`deviceId` is the lower-case hex SHA-256 of the device key's SPKI DER (the Android `DeviceId`). A parent key's `fingerprint` is the lower-case hex SHA-256 of its SPKI DER. `members/{uid}.tokensValidAfter` (epoch seconds) is set when one of that member's parent devices is revoked (see `revokeDevice`).

**Event documents.** The transport stores a ledger event with the `Envelope` fields at the top level (not nested under `envelope` as `LedgerJson` writes it), next to the polymorphic `type` and the event-specific fields; the receiver re-nests the envelope fields before hashing (the canonical tree is `{type, envelope: {…}, …}`). Required: `schemaVersion` (1 or 2), `eventId`, `householdId`, `kidId`, `accountId`, `authorUid`, `authorRole`, `authorDeviceId`, `createdAt` (epoch ms), `lamport`, `prevHash` (null or hex), `hash`, `signature`, `type`, `serverReceivedAt`. Optional: `parentSignature` and the event fields of every schema 1–2 event type (`currency, amount, choreId, dueOn, scheduleId, periodKey, gameId, score, playedOn, rewardId, minutesGranted, reason, toAccountId, principal, annualRateBasisPoints, days, sessionId, minutesUsed, minutes, grantedBy, causedBy, note`). Any other key is refused, so a new event type or field needs a rules change together with its schema version. `causedBy` holds 1 to 16 event ids of 1 to 128 characters from `[A-Za-z0-9._-]`; `reason` and `note` at most 500 characters.

**Documents.** Exactly the `DocumentEnvelope` fields `schemaVersion` (1), `householdId`, `docType`, `docId`, `authorUid`, `authorDeviceId`, `lamport`, `deleted`, `payload` (a map), `signature` and `serverReceivedAt`, all required. The payload of each docType has a closed key set, the serial names of its Kotlin model; every key may be absent or null (the receivers validate the model), and present values are bounded:

| docType (collection) | Payload keys (model) | Bounds |
|---|---|---|
| `kid` (`kids`) | `id, householdId, firstName, avatarId, birthYearMonth, uiTier, archived` (core/model `Kid`) | `firstName` ≤ 40, ids ≤ 128 |
| `account` (`accounts`) | `id, kidId, currency, kind` (core/domain-ledger `Account`) | ids ≤ 128 |
| `household_policy` (`householdPolicy`) | `fiatCurrency, requireParentSignature` (core/database `DocumentPayloads`) | bool flag |
| `chore` (`chores`) | `id, householdId, kidId, title, description, reward, schedule, createdAt, state` (core/domain-household `Chore`) | `title` ≤ 80, `description` ≤ 500, `reward`/`schedule` maps ≤ 2 keys, `schedule.daysOfWeek` ≤ 7 |
| `chore_submission` (`choreSubmissions`) | `id, choreId, dueOn, kidId, submittedAt, note, photoRef, status, decidedAt, decidedBy, rejectionReason` (core/model `ChoreSubmission`) | `status` ∈ SUBMITTED, REJECTED, APPROVED (required); `note`, `rejectionReason` ≤ 500; `photoRef` only `fileName` ≤ 128, `sha256` ≤ 64 |
| `reward` (`rewards`) | `id, householdId, title, kind, cost, currency, minutesGranted, active, forKids` (core/domain-household `Reward`) | `title` ≤ 80, `cost`/`minutesGranted` integers, `forKids` ≤ 64 |
| `allowance_schedule` (`allowanceSchedules`) | `id, kidId, accountId, amount, currency, cadence, startsOn, active` (core/domain-household `AllowanceSchedule`) | `amount` integer, `cadence` map ≤ 2 keys |
| `savings_goal` (`savingsGoals`) | `id, kidId, title, target, createdOn, achievedOn` (core/domain-household `SavingsGoal`) | `title` ≤ 80, `target` integer |
| `interest_policy` (`interestPolicy`) | `householdId, annualRateBasisPoints, accrualDay` (core/model `InterestPolicy`) | integers |

`parent_key` (core/database `DocumentPayloads`: `fingerprint, publicKey, uid, deviceId, validFromLamport`) is not a client-writable document any more: `registerParentKey` writes `parentKeys/{fingerprint}`.

**Pull cursors.** Every client write sets `serverReceivedAt` to `FieldValue.serverTimestamp()`; the rules require it and accept it only when it equals the request time.

## Security rules summary

- Membership and role come from `members/{uid}` only; claims and client data are never trusted by the rules.
- Every client write carries `authorUid == request.auth.uid` and an `authorDeviceId` that is an **ACTIVE** device of the caller in this household (`devices/{id}.uid == uid` and `status == ACTIVE`); PENDING, REVOKED, unregistered and other users' devices are refused.
- **Session freshness.** When the caller's member row has `tokensValidAfter`, a write needs `request.auth.token.auth_time >= tokensValidAfter`: sessions signed in before a parent-device revocation cannot write (`auth_time` is the sign-in time and survives token refresh, so the device must sign in again).
- Reads: a member reads everything under its household (the household doc, devices, parent keys, events, documents) except other members' rows (own `members/{uid}` only). Nothing top level is readable.
- Events: create only (no update, no delete); doc id `eventId`, or `eventId__hash` (a conflicting copy kept as evidence) from a PARENT member only; `householdId == hid`; `schemaVersion in [1, 2]`; integer `lamport >= 0`; `authorRole` KID from any member, PARENT only from a PARENT member; a KID member writes only for its own `kidId`.
- Documents: create and update with the author checks, `docType` matching the collection, `docId` matching the document id, `householdId == hid`. An update must win under the client `ConflictResolver` (core/sync) order:
  1. a live copy never overwrites a tombstone (`resource.deleted == false || request.deleted == true`); a PARENT may tombstone a live document whatever the lamports;
  2. two live `choreSubmissions` compare status rank first (SUBMITTED 0, REJECTED 1, APPROVED 2): a higher rank wins whatever the lamports, a lower one never does;
  3. otherwise last writer wins: `lamport` greater, or equal with a greater `authorDeviceId`.

  No deletes: a tombstone is a document with `deleted == true`. This is the same relation as `ConflictResolver` (rank first, then last-writer-wins), so a write the rules refuse as stale is one the receivers would ignore.
- Roles: a PARENT member writes every collection (`householdPolicy` is parent only). A KID member writes only `choreSubmissions`, only for its own `kidId`, only with `payload.status == SUBMITTED` and `payload.id == docId`, never a tombstone; on update only over a live, SUBMITTED copy of its own kid, keeping `id`, `choreId`, `dueOn` and `kidId`.
- `parentKeys`, `members`, `devices`, `linkCodes`, `challenges`, `userHouseholds`, `deletionRecords`, `rateLimits`: no client writes.
- **Size limits are approximate.** Rules cannot measure bytes. Events (target ≤ 16 KB) are bounded by the closed key set and a length bound on every string (ids ≤ 128–256, `reason`/`note` ≤ 500, signatures ≤ 200, `causedBy` ≤ 16 ids of ≤ 128). Documents (target payload ≤ 64 KB) are bounded by the closed envelope key set, envelope string bounds and the per-docType payload key sets above; the values inside nested maps are bounded by key count only. Firestore's 1 MiB document limit is the hard ceiling; the receivers' own validation is the precise one.
- **Evaluation budget.** A request may evaluate at most 1,000 rule expressions and is denied above that. The write rules read the member row and the author device once and pass them down; `causedBy` is checked through `join(',')` and a regex instead of per-element calls. The tests "stays within the rules evaluation budget …" write the largest event and the largest kid submission update; extend them when adding checks on those paths.

## Callables

All callables are served by the server at `POST /<name>` (protocol and adapter above), require App Check outside the emulator (in the adapter, plus the same check in the handler's guard), answer with `HttpsError` codes whose messages carry no household content, and log ids and counts only (`server/src/log.ts`, one JSON line per event on stdout/stderr, is the only logging path; ESLint enforces it). Every PARENT callable also refuses (`unauthenticated`) a session whose `auth_time` is older than the member's `tokensValidAfter`.

| Callable | Caller | Result |
|---|---|---|
| `createHousehold({ fiatCurrency, devicePublicKey, attestationChain? })` | signed in, not anonymous, no household | `{ hid, deviceId }`; household, PARENT member, ACTIVE device, `userHouseholds/{uid}`, claims `{ hid, role: PARENT }` |
| `beginDeviceRegistration()` | signed in | `{ challenge }` (32 random bytes, base64); `challenges/{uid}` stores its SHA-256 for 10 minutes |
| `registerParentDevice({ devicePublicKey, attestationChain? })` | PARENT | `{ deviceId, fingerprint }`; device PENDING; fingerprint = first 16 hex characters in groups of 4 |
| `approveDevice({ deviceId, approverDeviceId, signature })` | PARENT; approver is its ACTIVE device | `{ deviceId, status: ACTIVE }` |
| `registerParentKey({ publicKey, attestationChain?, deviceId, signature })` | PARENT; `deviceId` is its ACTIVE device | `{ fingerprint, validFromLamport }`; `parentKeys/{fingerprint}` with `attestation`; `validFromLamport` = highest lamport of the household's events and documents + 1 (read in the write transaction); an attested key must be TEE or StrongBox and require a biometric for every use; unattested only in the emulator or with `ALLOW_UNATTESTED=true` |
| `createLinkCode({ kidId, callerDeviceId, signature })` | PARENT; kid exists | `{ code, expiresAt }` (8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, 10 minutes, epoch ms); at most 5 live codes per household |
| `redeemLinkCode({ code, devicePublicKey, attestationChain? })` | anonymous, no claims, no `userHouseholds` row | `{ hid, kidId, deviceId }`; code marked used, KID member, ACTIVE device, `userHouseholds/{uid}`, claims `{ hid, role: KID, kidId }`; wrong, used and expired codes all answer `not-found` |
| `revokeDevice({ deviceId, callerDeviceId, signature })` | PARENT; `callerDeviceId` is its ACTIVE device and differs from `deviceId` | `{ deviceId, revokedAtLamport }` = highest lamport over the device's events **and documents in every collection** + 1 (1 when it wrote nothing; `internal` if a stored lamport is not a safe integer); also stamped on its `parentKeys`. A kid device: user disabled, refresh tokens revoked, membership deleted. A parent device: the owner's refresh tokens revoked and `members/{uid}.tokensValidAfter` set, which signs every session of that parent out, **the calling device included** (it signs in again) |
| `exportHousehold({ callerDeviceId, signature })` | PARENT | `{ exportedAt, household, members, devices, parentKeys, collections: { <name>: [...] }, events }` (timestamps as ISO strings); `resource-exhausted` above 4 MB (the host's response limit is 4.5 MB) |
| `deleteHousehold({ confirmation, callerDeviceId, signature })` | PARENT; `confirmation == hid`; signed in within the last 5 minutes (`failed-precondition` otherwise) | `{ deleted: true }`; deletes `members/*` first, then `households/{hid}` recursively, the household's link codes, and for every uid in `members` ∪ `devices.uid` its challenge, `userHouseholds` row and Auth user; one `deletionRecords` row with `sha256(hid)` and counts |

The link code is never stored in clear (only its HMAC) and never logged.

### Signed requests (device proof, T7/T8)

Privileged callables carry a signature by one of the caller's **ACTIVE parent devices** (`devices/{id}` with the caller's uid, role PARENT, status ACTIVE): ECDSA P-256 with SHA-256, DER encoded, base64 (what Android's `SHA256withECDSA` produces with the device key). The signed bytes are UTF-8 strings joined by single `0x00` bytes:

| Callable | Device field | Signed bytes |
|---|---|---|
| `approveDevice` | `approverDeviceId` | `"sprout/approve-device/v1" ‖ 0x00 ‖ deviceId` (unchanged) |
| `registerParentKey` | `deviceId` | `"sprout/register-parent-key/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ publicKey` (the raw SPKI DER bytes, not base64) |
| `createLinkCode` | `callerDeviceId` | `"sprout/create-link-code/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ {"kidId":"<kidId>"}` |
| `revokeDevice` | `callerDeviceId` | `"sprout/revoke-device/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ {"deviceId":"<deviceId>"}` |
| `exportHousehold` | `callerDeviceId` | `"sprout/export-household/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ {}` |
| `deleteHousehold` | `callerDeviceId` | `"sprout/delete-household/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ {"confirmation":"<hid>"}` |

`hid` is the caller's household id (from its claims). The JSON part is the callable's other arguments (everything except the device field and `signature`) as canonical JSON: keys sorted by UTF-16 code unit, no whitespace, strings as `JSON.stringify` writes them (`server/src/crypto.ts` `canonicalJson`; the arguments are ASCII ids, so there is nothing to escape). The server rebuilds it from the arguments it uses, so unknown extra arguments are ignored and not signed. Binding `hid` and the domain stops a signature from being replayed in another household or for another callable. A missing or malformed signature is `invalid-argument`; a wrong signer, a non-ACTIVE or foreign device, or a signature over other arguments is `permission-denied`.

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
| `createLinkCode` | 20 / hour | 60 / hour | 5 live codes per household |
| `redeemLinkCode` | 10 / hour | 10 / hour | 20 / hour per code id (the HMAC); counted before the code is looked up |
| `revokeDevice` | 30 / hour | 60 / hour | |
| `exportHousehold` | 5 / hour | 10 / hour | |
| `deleteHousehold` | 3 / hour | 10 / hour | |

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
| `FIRESTORE_EMULATOR_HOST`, `FIREBASE_AUTH_EMULATOR_HOST` | development and tests only | Point the Admin SDK at the emulators (`npm run dev` defaults them; `firebase emulators:exec` sets them for the tests). Never set them on Vercel. |
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

Deploy the indexes before relying on `revokeDevice`: it queries every event and document collection on `(authorDeviceId, lamport desc)`, and the composite indexes in `firestore/firestore.indexes.json` must be built first (the emulator does not enforce indexes).

## Production project checklist

1. The Firebase project `sprout-3e1c7` stays on **Spark** (D-M3-29). Firestore location **`eur3`** (multi-region Europe), chosen when the database is created; it cannot be changed later (D-M3-8).
2. Add the Android app `dev.kpetriv.sprout` (and the debug suffix if used), download `google-services.json` to `app/` (it is gitignored; never commit it; CI injects it from a secret). The app applies the `google-services` plugin only when the file exists.
3. Enable Authentication providers: Google, and Anonymous (kid devices).
4. App Check: register the app with the **Play Integrity** provider and **enforce** App Check for Firestore in the console; the server enforces it itself for the callables.
5. Create the server's service account (above), set the server environment in Vercel and deploy it; leave `ALLOW_UNATTESTED` unset.
6. Firestore TTL policies: enable TTL on `expiresAt` for the collection groups `linkCodes`, `challenges` and `rateLimits`.
7. Deploy rules and indexes (above). Deploy credentials for CI, if rules ever deploy from CI, belong to a separate service account limited to rules and index administration (for example `roles/firebaserules.admin` and `roles/datastore.indexAdmin`) and live in GitHub Actions secrets, never in the repository.
8. Set budget alerts and error alerting (Vercel's function logs and usage; T18, PLAN "Availability and operations"), and update `docs/PRIVACY.md` with the regions (Firestore `eur3`, server `fra1`).
