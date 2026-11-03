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

The Functions emulator serves the compiled `functions/lib/`; rebuild (`npm run build`) after changing `functions/src/`. Inside it `FUNCTIONS_EMULATOR=true`, so App Check is not required and keys without attestation are accepted and recorded as `NONE` (D-M3-5, D-M3-6). The emulator warns that the declared runtime (Node 22) differs from the host's Node 24; that is expected.

Emulator log files (`firebase-debug.log`, `firestore-debug.log`) land in the directory the command runs from. `functions/` and `firestore/` ignore them; run from the root only with that in mind.

## Running the tests

```
cd firestore && npm ci && npm run lint && npm test     # rules tests, Firestore emulator only
cd functions && npm ci && npm run lint && npm run build && npm test   # Auth + Firestore emulators
```

Each `npm test` is `firebase emulators:exec --only … --project demo-sprout "vitest run"`: it starts the emulators, runs vitest against them and shuts them down. Test files run sequentially because they share one emulator and clear it between tests.

- **Rules tests** (`firestore/test/*.test.ts`) use `initializeTestEnvironment` with `firestore.rules`, seed two households with `withSecurityRulesDisabled` and assert allowed and denied operations with `assertSucceeds`/`assertFails`.
- **Functions tests** (`functions/test/*.test.ts`) call each callable's handler directly through `onCall(...).run(request)` with a hand-built `CallableRequest` (auth uid and token claims, sign-in provider, App Check presence, client IP), while the Admin SDK inside the handler talks to the real Auth and Firestore emulators. This covers everything between the framework's token verification and the response; the HTTP framing and token verification themselves are the framework's. `firebase-functions-test` is not used: its current release requires `jest` as a mandatory peer and `firebase-admin` 13 or older, and its v2 `wrap` is a thin layer over the same `.run()`.
- The attestation parser is tested against a **self-generated** chain (fake root, fake intermediate, leaf with a hand-built key description extension; `functions/test/attestationFixtures.ts`). No real attested chain can be produced offline: a chain recorded from a physical device is still needed as a fixture for the Google-rooted path.

## Firestore layout and document shapes

```
households/{hid}                       { createdBy, createdAt, fiatCurrency }                      functions only
  members/{uid}                        { role: PARENT|KID, kidId?, addedAt }                        functions only
  devices/{deviceId}                   { uid, role, kidId?, publicKey, attestation, status,          functions only
                                         registeredAt, approvedBy?, approvedAt?, revokedAt?,
                                         revokedAtLamport?, lamport }
  parentKeys/{fingerprint}             { publicKey, uid, deviceId, validFromLamport, registeredAt,   parent device create;
                                         envelope?, serverReceivedAt?, revokedAtLamport? }            revokedAtLamport by functions
  events/{eventId | eventId__hash}     ledger event, envelope flattened (below)                       create only
  kids|accounts|householdPolicy|chores|choreSubmissions|rewards|allowanceSchedules|savingsGoals|interestPolicy/{docId}
                                       DocumentEnvelope (below)                                       LWW, tombstones
linkCodes/{sha256(code)}               { hid, kidId, createdBy, createdAt, expiresAt, used, usedAt?, usedBy? }   no client access
challenges/{uid}                       { hash, expiresAt }                                                       no client access
deletionRecords/{autoId}               { hidHash, at, counts: { members, devices, events, documents } }          no client access
rateLimits/{bucket}                    { callable, kind, hits[], expiresAt, updatedAt }                          no client access
```

`deviceId` is the lower-case hex SHA-256 of the device key's SPKI DER (the Android `DeviceId`). A parent key's `fingerprint` is 64 lower-case hex characters (SHA-256 of its SPKI).

**Event documents.** The transport stores a ledger event with the `Envelope` fields at the top level (not nested under `envelope` as `LedgerJson` writes it), next to the polymorphic `type` and the event-specific fields; the receiver re-nests the envelope fields before hashing (the canonical tree is `{type, envelope: {…}, …}`). Required: `schemaVersion` (1 or 2), `eventId`, `householdId`, `kidId`, `accountId`, `authorUid`, `authorRole`, `authorDeviceId`, `createdAt` (epoch ms), `lamport`, `prevHash` (null or hex), `hash`, `signature`, `type`. Optional: `parentSignature`, the event fields of every schema 1–2 event type (`currency, amount, choreId, dueOn, scheduleId, periodKey, gameId, score, playedOn, rewardId, minutesGranted, reason, toAccountId, principal, annualRateBasisPoints, days, sessionId, minutesUsed, minutes, grantedBy, causedBy, note`) and `serverReceivedAt`. Any other key is refused, so a new event type or field needs a rules change together with its schema version.

**Documents.** Exactly the `DocumentEnvelope` fields `schemaVersion` (1), `householdId`, `docType`, `docId`, `authorUid`, `authorDeviceId`, `lamport`, `deleted`, `payload` (a map), `signature`, plus optional `serverReceivedAt`.

**Pull cursors.** A client may set `serverReceivedAt` to `FieldValue.serverTimestamp()`; the rules accept it only when it equals the request time.

## Security rules summary

- Membership and role come from `members/{uid}` only; claims and client data are never trusted by the rules.
- Every client write carries `authorUid == request.auth.uid` and an `authorDeviceId` that is an **ACTIVE** device of the caller in this household (`devices/{id}.uid == uid` and `status == ACTIVE`); PENDING, REVOKED, unregistered and other users' devices are refused.
- Reads: a member reads everything under its household (the household doc, devices, parent keys, events, documents) except other members' rows (own `members/{uid}` only). Nothing top level is readable.
- Events: create only (no update, no delete); doc id `eventId` or `eventId__hash`; `householdId == hid`; `schemaVersion in [1, 2]`; integer `lamport >= 0`; `authorRole` KID from any member, PARENT only from a PARENT member; a KID member writes only for its own `kidId`.
- Documents: create and update with the author checks, `docType` matching the collection, `docId` matching the document id, `householdId == hid`; an update must win last-writer-wins: `lamport` greater, or equal with a greater `authorDeviceId`. No deletes: a tombstone is a document with `deleted == true`.
- Roles: a PARENT member writes every collection (`householdPolicy` is parent only). A KID member writes only `choreSubmissions`, only for its own `kidId`, only with `payload.status == SUBMITTED` and `payload.id == docId`, never a tombstone, and never over a stored submission that is decided or belongs to another kid.
- `parentKeys`: create by a PARENT member for its own uid and one of its ACTIVE devices, with `registeredAt == request.time` and no `revokedAtLamport`; no client update or delete.
- `members`, `devices`, `linkCodes`, `challenges`, `deletionRecords`, `rateLimits`: no client writes.
- **Size limits are approximate.** Rules cannot measure bytes. Events (target ≤ 16 KB) are bounded by the closed key set, a length bound on every string (ids ≤ 128–256, `reason`/`note` ≤ 400, signatures ≤ 200) and `causedBy` ≤ 64 entries (list elements cannot be measured). Documents (target payload ≤ 64 KB) are bounded by the closed key set, envelope string bounds and at most 64 top-level payload keys (nested values cannot be measured). Firestore's 1 MiB document limit is the hard ceiling; the receivers' own validation is the precise one.

## Callables

All callables are `onCall` v2 in `europe-west1`, require App Check outside the emulator (`enforceAppCheck`, plus the same check in the handler), answer with `HttpsError` codes whose messages carry no household content, and log ids and counts only (`functions/src/log.ts` is the only logging path; ESLint enforces it).

| Callable | Caller | Result |
|---|---|---|
| `createHousehold({ fiatCurrency, devicePublicKey, attestationChain? })` | signed in, not anonymous, no household | `{ hid, deviceId }`; household, PARENT member, ACTIVE device, claims `{ hid, role: PARENT }` |
| `beginDeviceRegistration()` | signed in | `{ challenge }` (32 random bytes, base64); `challenges/{uid}` stores its SHA-256 for 10 minutes |
| `registerParentDevice({ devicePublicKey, attestationChain? })` | PARENT | `{ deviceId, fingerprint }`; device PENDING; fingerprint = first 16 hex characters in groups of 4 |
| `approveDevice({ deviceId, approverDeviceId, signature })` | PARENT; approver is its ACTIVE device | `{ deviceId, status: ACTIVE }`; signature is ECDSA P-256/SHA-256 (DER, base64) over `"sprout/approve-device/v1" ‖ 0x00 ‖ deviceId` |
| `createLinkCode({ kidId })` | PARENT; kid exists | `{ code, expiresAt }` (8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, 10 minutes, epoch ms); at most 5 live codes per household |
| `redeemLinkCode({ code, devicePublicKey, attestationChain? })` | anonymous, no claims | `{ hid, kidId, deviceId }`; code marked used, KID member, ACTIVE device, claims `{ hid, role: KID, kidId }`; wrong, used and expired codes all answer `not-found` |
| `revokeDevice({ deviceId, callerDeviceId })` | PARENT; `callerDeviceId` is its ACTIVE device and differs from `deviceId` | `{ deviceId, revokedAtLamport }` = highest lamport of the device's events + 1 (1 without events); also stamped on its `parentKeys`; a kid device's user is disabled and its membership deleted |
| `exportHousehold()` | PARENT | `{ exportedAt, household, members, devices, parentKeys, collections: { <name>: [...] }, events }` (timestamps as ISO strings); `resource-exhausted` above 9 MB |
| `deleteHousehold({ confirmation })` | PARENT; `confirmation == hid` | `{ deleted: true }`; recursive delete of `households/{hid}`, the household's link codes and its members' challenges, every member's Auth user; one `deletionRecords` row with `sha256(hid)` and counts |

The link code is never stored (only its SHA-256) and never logged.

## Rate limits

Sliding windows stored in `rateLimits/{bucket}` (`functions/src/rateLimit.ts`). The bucket id is `<callable>_<kind>_<first 32 hex of sha256(callable|kind|key)>`, so uids, IP addresses and code hashes are never stored in clear. A refused call is not recorded. Exceeding a limit answers `resource-exhausted`.

| Callable | Per uid | Per IP | Other |
|---|---|---|---|
| `createHousehold` | 5 / hour | 20 / hour | |
| `beginDeviceRegistration` | 10 / hour | 30 / hour | |
| `registerParentDevice` | 5 / hour | 20 / hour | |
| `approveDevice` | 20 / hour | 60 / hour | |
| `createLinkCode` | 20 / hour | 60 / hour | 5 live codes per household |
| `redeemLinkCode` | 10 / hour | 10 / hour | 20 / hour per code hash; counted before the code is looked up |
| `revokeDevice` | 30 / hour | 60 / hour | |
| `exportHousehold` | 5 / hour | 10 / hour | |
| `deleteHousehold` | 3 / hour | 10 / hour | |

The IP is `rawRequest.ip` (falling back to the first `X-Forwarded-For` hop); behind Google's front end that is the client address.

## Key attestation

`functions/src/attestation.ts` verifies the chain a device sends at registration (`@peculiar/x509` with `@peculiar/asn1-android`): every certificate in its validity period, each signed by the next, the last one signed by an embedded **Google hardware attestation root** (the RSA-4096 root, whose key all earlier RSA roots share, and the ECDSA P-384 "Key Attestation CA1"; source and fingerprints in the file), no certificate on Google's revocation list, the key description extension (`1.3.6.1.4.1.11129.2.1.17`) carrying the caller's outstanding challenge, and an EC P-256 signing key generated in the keystore (purpose SIGN, algorithm EC, curve P-256, origin GENERATED, from the hardware-enforced list for TEE and StrongBox). The leaf's SPKI must equal the submitted key, otherwise the registration is refused. The level is recorded as `STRONGBOX`, `TEE` or `SOFTWARE`.

- **Revocation list.** `https://android.googleapis.com/attestation/status` is fetched with a 24-hour in-memory cache per instance. If it cannot be fetched, the last cached list is used, or the chain is **allowed** with a warning log (`attestation.statusList.unavailable`): an outage of Google's endpoint must not block every registration, at the cost of accepting a certificate revoked during the outage.
- **No chain.** Accepted as `attestation: NONE` only in the emulator or with `ALLOW_UNATTESTED=true`; otherwise `failed-precondition` (D-M3-5).
- **Roots.** When Google publishes a new root, add its PEM to `GOOGLE_ATTESTATION_ROOTS_PEM` with its source and fingerprint.

## Adding a real project later

1. Create the Firebase project (Blaze plan for Functions). Choose the Firestore location **`eur3`** (multi-region Europe) when creating the database; it cannot be changed later (D-M3-8). Functions are already pinned to **`europe-west1`**.
2. Add the Android app `dev.kpetriv.sprout` (and the debug suffix if used), download `google-services.json` to `app/` (it is gitignored; never commit it; CI injects it from a secret). The app applies the `google-services` plugin only when the file exists.
3. Enable Authentication providers: Google, and Anonymous (kid devices).
4. App Check: register the app with the **Play Integrity** provider, then **enforce** App Check for Firestore and Cloud Functions in the console (the functions already set `enforceAppCheck`).
5. Functions configuration: set `ALLOW_UNATTESTED=false` (for example in `functions/.env.<project-id>`, which is safe to commit only if it holds no secrets) so unattested keys are refused.
6. Firestore TTL policies: enable TTL on `expiresAt` for the collection groups `linkCodes`, `challenges` and `rateLimits`.
7. Deploy: `npx --no-install --prefix functions firebase use --add <project-id>`, then `npx --no-install --prefix functions firebase deploy --only firestore:rules,firestore:indexes,functions`. Deploy credentials (a CI service account) live in GitHub Actions secrets, never in the repository.
8. Set budget alerts and Functions error-rate alerting (T18, PLAN "Availability and operations"), and update `docs/PRIVACY.md` with the region.
