# Firebase backend

The backend is Firestore (security rules in `firestore/`) and Cloud Functions (TypeScript in `functions/`). Until a real Firebase project exists (D-M3-1), everything runs and is tested against the **Firebase Emulator Suite** under the project id `demo-sprout`. Design: `docs/milestones/M3.md` §1, §5 and §7.

```
firebase.json              emulator ports, rules and functions paths
.firebaserc                default project demo-sprout
firestore/                 firestore.rules, firestore.indexes.json, rules tests (vitest + @firebase/rules-unit-testing)
functions/                 callables (src/), tests (test/), embedded Google attestation roots (src/attestation.ts)
```

## Prerequisites

- **Node 24** and npm. Nothing is installed globally (D-M3-10): `firebase-tools` is a pinned devDependency of both `functions/` and `firestore/` and runs through `npx` or the npm scripts.
- **Java 21 or newer** for the emulators (`firebase-tools` 15 refuses older JVMs). On macOS with Homebrew:

  ```
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"
  ```

- The first emulator start downloads the emulator jars to `~/.cache/firebase/emulators` (CI caches that directory, keyed on the `firebase-tools` version).

## What `demo-sprout` means

A project id starting with `demo-` is a Firebase **demo project**: the emulators accept it without any login, without a real project and without network access to Google, and any SDK call aimed at a non-emulated service fails instead of reaching production. Nothing in this repository can touch a real Firebase project.

## Running the emulators

From the repository root:

```
npm --prefix functions ci && npm --prefix functions run build
npx --no-install --prefix functions firebase emulators:start --project demo-sprout
```

`--prefix functions` runs the `firebase-tools` pinned in `functions/package-lock.json` while keeping the repository root (and its `firebase.json`) as the working directory; `--no-install` stops `npx` from fetching an unpinned `firebase-tools` if the packages are not installed. A plain `npx firebase …` from the root works only if `firebase-tools` resolves from there. Ports: Auth 9099, Firestore 8080, Functions 5001; the Emulator UI is off. `singleProjectMode` is on, so every client must use `demo-sprout`. Debug builds of the app talk to these ports through `BuildConfig.FIREBASE_EMULATOR_HOST` (default `10.0.2.2`, M3 §5).

The Functions emulator serves the compiled `functions/lib/`; rebuild (`npm run build`) after changing `functions/src/`. Inside it `FUNCTIONS_EMULATOR=true` with the `demo-sprout` project, so App Check is not required, keys without attestation are accepted and recorded as `NONE` (D-M3-5, D-M3-6), and link codes use the public development HMAC key (see "Link-code key"). The emulator warns that the declared runtime (Node 22) differs from the host's Node 24; that is expected.

Emulator log files (`firebase-debug.log`, `firestore-debug.log`) land in the directory the command runs from. `functions/` and `firestore/` ignore them; run from the root only with that in mind.

## Running the tests

```
cd firestore && npm ci && npm run lint && npm test     # rules tests, Firestore emulator only
cd functions && npm ci && npm run lint && npm run build && npm test   # Auth + Firestore emulators
```

Each `npm test` is `firebase emulators:exec --only … --project demo-sprout "vitest run"`: it starts the emulators, runs vitest against them and shuts them down. Test files run sequentially because they share one emulator and clear it between tests.

- **Rules tests** (`firestore/test/*.test.ts`) use `initializeTestEnvironment` with `firestore.rules`, seed two households with `withSecurityRulesDisabled` and assert allowed and denied operations with `assertSucceeds`/`assertFails`.
- **Functions tests** (`functions/test/*.test.ts`) call each callable's handler directly through `onCall(...).run(request)` with a hand-built `CallableRequest` (auth uid and token claims, `auth_time`, sign-in provider, App Check presence, client IP and `X-Forwarded-For`), while the Admin SDK inside the handler talks to the real Auth and Firestore emulators. This covers everything between the framework's token verification and the response; the HTTP framing and token verification themselves are the framework's. `firebase-functions-test` is not used: its current release requires `jest` as a mandatory peer and `firebase-admin` 13 or older, and its v2 `wrap` is a thin layer over the same `.run()`.
- The attestation parser is tested against a **self-generated** chain (fake root, fake intermediate, leaf with a hand-built key description extension; `functions/test/attestationFixtures.ts`). No real attested chain can be produced offline: a chain recorded from a physical device is still needed as a fixture for the Google-rooted path.

## Firestore layout and document shapes

```
households/{hid}                       { createdBy, createdAt, fiatCurrency }                      functions only
  members/{uid}                        { role: PARENT|KID, kidId?, addedAt, tokensValidAfter? }     functions only
  devices/{deviceId}                   { uid, role, kidId?, publicKey, attestation, status,          functions only
                                         registeredAt, approvedBy?, approvedAt?, revokedAt?,
                                         revokedAtLamport?, sessionsRevokedAt?, lamport }
  parentKeys/{fingerprint}             { publicKey, uid, deviceId, validFromLamport, registeredAt,   functions only
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

All callables are `onCall` v2 in `europe-west1`, require App Check outside the emulator (`enforceAppCheck`, plus the same check in the handler), answer with `HttpsError` codes whose messages carry no household content, and log ids and counts only (`functions/src/log.ts` is the only logging path; ESLint enforces it). Every PARENT callable also refuses (`unauthenticated`) a session whose `auth_time` is older than the member's `tokensValidAfter`.

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
| `exportHousehold({ callerDeviceId, signature })` | PARENT | `{ exportedAt, household, members, devices, parentKeys, collections: { <name>: [...] }, events }` (timestamps as ISO strings); `resource-exhausted` above 9 MB |
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

`hid` is the caller's household id (from its claims). The JSON part is the callable's other arguments (everything except the device field and `signature`) as canonical JSON: keys sorted by UTF-16 code unit, no whitespace, strings as `JSON.stringify` writes them (`functions/src/crypto.ts` `canonicalJson`; the arguments are ASCII ids, so there is nothing to escape). The server rebuilds it from the arguments it uses, so unknown extra arguments are ignored and not signed. Binding `hid` and the domain stops a signature from being replayed in another household or for another callable. A missing or malformed signature is `invalid-argument`; a wrong signer, a non-ACTIVE or foreign device, or a signature over other arguments is `permission-denied`.

### Link-code key

Link-code ids are `HMAC-SHA256(LINK_CODE_HMAC_KEY, code)` in hex (`functions/src/config.ts` `linkCodeKey`). The key is a Secret Manager secret (`defineSecret('LINK_CODE_HMAC_KEY')`, bound to `createLinkCode` and `redeemLinkCode`); outside the emulator a missing key or one shorter than 32 characters makes both callables answer `internal`. A plain hash would let anyone with a copy of the database recover live codes by hashing all 32⁸ codes; the HMAC id is useless without the key. Rotating the key invalidates the outstanding codes (at most 10 minutes' worth).

In the emulator (`FUNCTIONS_EMULATOR=true` **and** a `demo-` project id) the functions use `LINK_CODE_HMAC_KEY` from the environment if set, else the fixed, public development key `sprout-emulator-only-link-code-key-not-a-secret`. It protects nothing and exists so the emulator needs no secret setup.

## Rate limits

Sliding windows stored in `rateLimits/{bucket}` (`functions/src/rateLimit.ts`). The bucket id is `<callable>_<kind>_<first 32 hex of sha256(callable|kind|key)>`, so uids, IP addresses and code ids are never stored in clear. A refused call is not recorded. Exceeding a limit answers `resource-exhausted`.

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

**Client address.** When the request has an `X-Forwarded-For` header the **right-most** hop is used: Google's front end appends the address it accepted the connection from, so that hop is the one a client cannot choose (hops to its left are client-supplied and ignored). Without the header, `rawRequest.ip`. IPv4-mapped IPv6 counts as IPv4; IPv6 is keyed per **/64**, since one subscriber usually holds a whole /64. Requests with no usable address share **one** bucket per callable (`kind` `noip`) whose limit is the per-IP limit divided by 5 (at least 1), e.g. 2 / hour for `redeemLinkCode`.

Caveat: the right-most hop is the client only when the function is reached directly through Google's front end, as callables are. If a further proxy is ever put in front (a Hosting rewrite, a load balancer, Cloudflare), the right-most hop becomes that proxy and every client would share its bucket; the hop count must then be configured to skip the trusted proxies. Carrier-grade NAT also puts many users behind one IPv4 address, which the per-uid limits and the low call volume make acceptable.

## Key attestation

`functions/src/attestation.ts` verifies the chain a device sends at registration (`@peculiar/x509` with `@peculiar/asn1-android`): every certificate in its validity period, each signed by the next, the last one signed by an embedded **Google hardware attestation root** (the RSA-4096 root, whose key all earlier RSA roots share, and the ECDSA P-384 "Key Attestation CA1"; source and fingerprints in the file), every certificate after the leaf a CA (`basicConstraints` cA true, and `keyCertSign` when it has a `keyUsage` extension), the key description extension (`1.3.6.1.4.1.11129.2.1.17`) present on the leaf only, no certificate on Google's revocation list, the extension carrying the caller's outstanding challenge, and an EC P-256 signing key generated in the keystore (purpose SIGN, algorithm EC, curve P-256, origin GENERATED, from the hardware-enforced list for TEE and StrongBox). The leaf's SPKI must equal the submitted key, otherwise the registration is refused. The level is recorded as `STRONGBOX`, `TEE` or `SOFTWARE`.

- **CA checks.** Without them, a genuine attested leaf (whose private key an attacker on a rooted or emulated device can use) could sign a forged "leaf" with any key description; every signature in `[forged, real leaf, intermediate, root]` verifies. The test "rejects a forged leaf signed by a real attested (non-CA) leaf" covers it.
- **Boot state.** `rootOfTrust.verifiedBootState` is read and logged (`attestation.verified`, `verifiedBootState`, `bootVerified` true for VERIFIED or SELF_SIGNED) but not enforced.
- **Parent keys** (`registerParentKey`) additionally require a TEE or StrongBox key whose hardware-enforced list has `USER_AUTH_TYPE` including biometric (bit 2), no `NO_AUTH_REQUIRED`, and `AUTH_TIMEOUT` absent or 0 (authentication for every use; Keystore omits the tag for a zero timeout).
- **Revocation list.** `https://android.googleapis.com/attestation/status` is fetched with a 24-hour in-memory cache per instance. If it cannot be fetched, the last cached list is used, or the chain is **allowed** with a warning log (`attestation.statusList.unavailable`): an outage of Google's endpoint must not block every registration, at the cost of accepting a certificate revoked during the outage.
- **No chain.** Accepted as `attestation: NONE` only in the emulator or with `ALLOW_UNATTESTED=true`; otherwise `failed-precondition` (D-M3-5).
- **Emulator detection.** The relaxations (App Check, unattested keys, the development link-code key) apply only when `FUNCTIONS_EMULATOR=true` **and** the project id starts with `demo-`, so a stray `FUNCTIONS_EMULATOR` in a real project changes nothing.
- **Roots.** When Google publishes a new root, add its PEM to `GOOGLE_ATTESTATION_ROOTS_PEM` with its source and fingerprint.

## Adding a real project later

1. Create the Firebase project (Blaze plan for Functions). Choose the Firestore location **`eur3`** (multi-region Europe) when creating the database; it cannot be changed later (D-M3-8). Functions are already pinned to **`europe-west1`**.
2. Add the Android app `dev.kpetriv.sprout` (and the debug suffix if used), download `google-services.json` to `app/` (it is gitignored; never commit it; CI injects it from a secret). The app applies the `google-services` plugin only when the file exists.
3. Enable Authentication providers: Google, and Anonymous (kid devices).
4. App Check: register the app with the **Play Integrity** provider, then **enforce** App Check for Firestore and Cloud Functions in the console (the functions already set `enforceAppCheck`).
5. Functions configuration: set `ALLOW_UNATTESTED=false` (or leave it unset) so unattested keys are refused, for example in `functions/.env.<project-id>`. `functions/.env*` and `.secret.local` are gitignored; keep such files out of the repository and pass them to the deploy from CI.
   Create the link-code key: `openssl rand -base64 32 | npx --no-install --prefix functions firebase functions:secrets:set LINK_CODE_HMAC_KEY --data-file=-` (at least 32 characters; `createLinkCode` and `redeemLinkCode` answer `internal` without it).
6. Firestore TTL policies: enable TTL on `expiresAt` for the collection groups `linkCodes`, `challenges` and `rateLimits`.
7. Deploy: `npx --no-install --prefix functions firebase use --add <project-id>`, then `npx --no-install --prefix functions firebase deploy --only firestore:rules,firestore:indexes,functions`. Deploy the indexes before the functions: `revokeDevice` queries every event and document collection on `(authorDeviceId, lamport desc)`, and the composite indexes in `firestore/firestore.indexes.json` must be built first (the emulator does not enforce indexes). Deploy credentials (a CI service account) live in GitHub Actions secrets, never in the repository.
8. Set budget alerts and Functions error-rate alerting (T18, PLAN "Availability and operations"), and update `docs/PRIVACY.md` with the region.
