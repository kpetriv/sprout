# ADR-0007: Identity, device linking and the device registry

- **Status:** Accepted
- **Date:** 2026-11-03
- **Milestone:** Decided and implemented in M3 (`server/`, `firestore/`, `core/sync-firebase`, `core/crypto`, `core/database`, `feature/onboarding`, `feature/settings`)
- **Related:** [ADR-0003](0003-security-model.md) (identity, device linking), [ADR-0004](0004-parent-authorisation-and-device-identity.md) (device identity and attestation, parent keys), [ADR-0006](0006-sync-protocol-and-document-envelope.md) (what receivers verify against the registry), [`docs/FIREBASE.md`](../FIREBASE.md) (callables, rules, environment)

## Context

ADR-0004 made every receiver verify events against a **registry** of household devices and parent keys, and required device keys to be attested at linking. M3 has to build that registry and the flows that fill it: who a user is, how a device joins, how a second parent phone is approved, how a kid phone is linked, how keys are registered, revoked and erased. Constraints:

- **Nothing a client writes about identity can be trusted.** Roles, household membership, device status and parent keys must be written by something holding the project's admin credentials.
- **The Firebase project stays on the free Spark plan** (Kiril, 2026-11-03), which has no Cloud Functions.
- **Kids have no accounts** (PLAN, PRIVACY). A kid device needs its own identity without an e-mail address.
- **A phone gets lost.** Revocation has to cut off a device's future writes everywhere without invalidating what it legitimately wrote before.

## Decision

### 1. Users and claims

- **Parents** sign in with Google through Credential Manager in release builds; debug builds use e-mail and password against the Auth emulator (D-M3-2). **Kid devices** are anonymous Firebase users.
- **Custom claims** `{ hid, role: PARENT | KID, kidId? }` are set **by the server only** (`createHousehold`, `redeemLinkCode`). The app forces a token refresh after linking.
- **`members/{uid}`** mirrors the claims inside the household. The Firestore rules read membership and role from this row, never from claims or client data, so removing a member takes effect on the next request.
- **One household per uid** (`userHouseholds/{uid}`, written in the same transaction as the membership; D-M3-34). This closes D-M3-20 (two concurrent `createHousehold` calls).
- **One parent account per household** in M3: a second parent phone is the same Google account on another device (section 4b). A co-parent with their own account is not built.
- **Kid mode on a parent phone** writes KID-role events authored by the parent uid and the parent device (D-M3-12): receivers accept KID-role events from a PARENT device, never PARENT-role events from a KID device.

### 2. Device identity

- The M1/M2 device key (`sprout.device.signing.v1`) is provisional and never registered (ADR-0004 §2).
- At registration the device calls `beginDeviceRegistration` (32 random bytes; the server stores only their SHA-256 under `challenges/{uid}`, 10 minutes), creates a fresh P-256 key under **`sprout.device.signing.v2`** with `setAttestationChallenge(challenge)` (StrongBox first, TEE fallback), sends the public key and the attestation chain, and deletes the v1 key once v2 is active. `DeviceId` is the hex SHA-256 of the key's SPKI.
- The server (`server/src/attestation.ts`, `registration.ts`) verifies the chain to the embedded Google hardware attestation roots, the CA flags of every non-leaf certificate, Google's revocation list, the outstanding challenge, an EC P-256 signing key generated in the keystore, and that the attested key **is** the submitted key. It records **`attestation: STRONGBOX | TEE | SOFTWARE | NONE`**.
- **Production accepts attested devices only** (Kiril, 2026-11-03; D-M3-5, D-M3-38): with `ALLOW_UNATTESTED` unset a registration without a chain is `failed-precondition`. `NONE` exists only in the emulator project (`SPROUT_EMULATOR=true` and a `demo-` project id) and is shown as "unverified device" in Settings. A `SOFTWARE`-attested device key is accepted and recorded; only parent keys must be hardware-backed (section 5). The verified boot state is logged, not enforced. An unreachable revocation list fails open with a warning log.

### 3. Registry documents and who writes them

| Path | Content | Written by | Read by |
|---|---|---|---|
| `households/{hid}` | `createdBy, createdAt, fiatCurrency` | server | members |
| `households/{hid}/members/{uid}` | `role, kidId?, addedAt, tokensValidAfter?` | server | the member itself only |
| `households/{hid}/devices/{deviceId}` | `uid, role, kidId?, publicKey, attestation, status (PENDING, ACTIVE, REVOKED), approvedBy?, revokedAt?, revokedAtLamport?, lamport` | server | members |
| `households/{hid}/parentKeys/{fingerprint}` | `publicKey, uid, deviceId, validFromLamport, attestation, revokedAtLamport?` | server (`registerParentKey`, `revokeDevice`) | members |
| `userHouseholds/{uid}`, `linkCodes/{hmac}`, `challenges/{uid}`, `rateLimits/{bucket}`, `deletionRecords/{id}` | server bookkeeping | server | nobody |

Devices mirror `devices` and `parentKeys` into Room (`RoomDeviceRegistry`), read in full on every pull (D-M3-22), and verify every pulled event and document against that mirror (ADR-0006).

### 4. Linking flows

**a. Create a household.** A signed-in, non-anonymous user with no household calls `createHousehold({ fiatCurrency, devicePublicKey, attestationChain })`: household, PARENT member, ACTIVE device, `userHouseholds` row and PARENT claims in one go.

**b. Second parent device.** The same account signs in on another phone, which calls `registerParentDevice`: the device is created **PENDING** and the call returns its display fingerprint (first 16 hex characters in groups of 4). The existing phone shows "New device waiting"; the parent compares the fingerprints and approves with `approveDevice({ deviceId, approverDeviceId, signature })`, where `signature` is the approving ACTIVE parent device's signature over `"sprout/approve-device/v1" ‖ 0x00 ‖ deviceId` (T8). Until then the rules refuse the PENDING device's writes and receivers hold anything it wrote (D-M3-31).

**c. Kid device.** A parent calls `createLinkCode({ kidId, callerDeviceId, signature })` (device proof, section 6) from Settings → Kids → Link a device:

- 8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (40 bits), from a CSPRNG, valid **10 minutes**, **single use**, at most **5 live codes** per household;
- stored only as `HMAC-SHA256(LINK_CODE_HMAC_KEY, code)` (a plain hash of a 40-bit code is reversible by enumeration; D-M3-34); never logged, never in a URL;
- the kid phone signs in anonymously and calls `redeemLinkCode({ code, devicePublicKey, attestationChain })`; wrong, used and expired codes all answer `not-found`;
- rate limits counted **before** the lookup: 10 / hour per uid, 10 / hour per IP, 20 / hour per code id across IPs (T6). Redeeming creates the KID member, an ACTIVE device, the `userHouseholds` row and claims `{ hid, role: KID, kidId }`.

### 5. Parent-key registration

The parent authorisation key (ADR-0004 §1) is registered only through `registerParentKey({ publicKey, attestationChain, deviceId, signature })` (D-M3-34); clients cannot write `parentKeys` (rules).

- `signature` is by the caller's ACTIVE parent device over `"sprout/register-parent-key/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ SPKI`, so a key is bound to the device whose Keystore holds it.
- An attested key must be **TEE or StrongBox** and its hardware-enforced list must require a **biometric for every use** (`USER_AUTH_TYPE` includes biometric, no `NO_AUTH_REQUIRED`, `AUTH_TIMEOUT` absent or 0). Unattested keys only in the emulator or with `ALLOW_UNATTESTED=true`.
- The server sets **`validFromLamport`** = the highest lamport of the household's events and documents + 1, read in the write transaction; the device moves its clock there (`recordServerRegisteredKey`, D-M3-36).
- Receivers accept a parent signature only from a key whose `deviceId` is the event's author device, whose fingerprint is the SHA-256 of its key, and whose `[validFromLamport, revokedAtLamport)` covers the event's lamport.

### 6. Device proofs on privileged calls

`createLinkCode`, `revokeDevice`, `exportHousehold` and `deleteHousehold` (and `approveDevice`, `registerParentKey` above) carry a signature by one of the caller's ACTIVE parent devices over `"sprout/<callable>/v1" ‖ 0x00 ‖ hid ‖ 0x00 ‖ canonicalJson(args)` (`server/src/deviceProof.ts`, `core/sync-firebase` `DeviceProof`). A stolen ID token alone is not enough for these calls; binding `hid` and the callable stops replay into another household or another call. The table of domains is in `docs/FIREBASE.md`.

### 7. Revocation

`revokeDevice({ deviceId, callerDeviceId, signature })`, from an ACTIVE parent device other than the target:

- sets the device `REVOKED` with **`revokedAtLamport`** = the highest lamport the device wrote over **events and documents in every collection** + 1, and stamps the same value on its `parentKeys`. Receivers keep everything below it and quarantine (events) or reject (documents) anything at or above it, so legitimate history survives and nothing new does;
- **kid device:** its anonymous user is disabled, its refresh tokens revoked, its membership deleted;
- **parent device:** the owner's refresh tokens are revoked and `members/{uid}.tokensValidAfter` is set; the rules refuse writes from a session whose `auth_time` is older, and the PARENT callables refuse such sessions too. Every session of that account, **the calling phone included**, must sign in again;
- the revoked device, on its next sync, sees its own entry `REVOKED` (or its account gone), which counts as household gone (D-M3-37), and wipes itself. It is idempotent; the `onDeviceRevoked` trigger of the M3 spec is folded into it (D-M3-19).

### 8. Household deletion and the wipe

`deleteHousehold({ confirmation: hid, callerDeviceId, signature })` needs a PARENT, the typed household id, a device proof and a sign-in within the last **5 minutes**. It deletes `members/*` first (cutting rules access), then `households/{hid}` recursively, the household's link codes, and for every uid in `members` ∪ `devices.uid` (revoked kids included) its challenge, `userHouseholds` row and Auth user. It writes one `deletionRecords` row `{ hidHash: sha256(hid), at, counts }` and nothing else.

Every device that next syncs finds positive evidence that the household is gone (D-M3-37) and runs `WipeLocalDataUseCase`: clears the session, closes and deletes the database files, the encrypted photos and every `SecretStore` blob, keeps the Keystore keys, and restarts to onboarding.

### 9. Purge of the local trial household

When a device joins a real household while it holds trial data (`householdId == "local"`), an explicit screen says the trial data will be deleted; on confirm `PurgeLocalHouseholdUseCase` deletes the trial kids, accounts, events, documents, outbox and inbox rows, pending actions, photos, household policy (including the locally registered parent key, so the parent registers again in the real household) and trial audit rows in one transaction, and writes one `LOCAL_HOUSEHOLD_PURGED` audit row with counts (D-M3-26). Nothing signed by a provisional key ever leaves the device (ADR-0004 §2).

### 10. Hosting: Spark plus a Node server speaking the callable protocol (D-M3-29)

The privileged operations run in **`server/`**: a Hono app on Vercel (Hobby, region `fra1`, Node 22) that speaks the **Firebase callable protocol** and uses `firebase-admin` with a least-privilege service account (`roles/datastore.user`, `roles/firebaseauth.admin`). The Android client keeps the Functions SDK with a custom origin (`FirebaseConfig.functionsBaseUrl`). Rules and indexes deploy with `firebase deploy --only firestore`, which Spark allows. The toolchain (`firebase-tools`, TypeScript, test libraries, the Vercel CLI) is pinned and run through `npx`, never installed globally (D-M3-10, D-M3-17).

Why: Spark has no Cloud Functions, and Blaze needs a billing account on a portfolio project; the callable protocol keeps the client SDK, its auth and App Check headers and its error codes unchanged; the same handler code runs in process against the emulators in tests and under `@hono/node-server` locally. Costs: a service-account key lives outside Google (a sensitive Vercel variable), responses are capped at 4.5 MB (export limit 4 MB, D-M3-35), there are no Firestore triggers, and the server must derive the client address for rate limits itself (right-most `X-Forwarded-For` hop).

### 11. App Check

Release builds install the **Play Integrity** provider, debug builds the debug provider (D-M3-6). The server's callable adapter requires and verifies `X-Firebase-AppCheck` outside the emulator, and each handler's guard checks it again. **Firestore** App Check enforcement is a console switch and an M8 step: until then Firestore relies on the rules alone, and a scripted client with a stolen session can read what that member may read.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Cloud Functions on Blaze** (the M3 spec as written) | Needs a billing account; Kiril kept the project on Spark (D-M3-29). The handlers are the same code, so moving back is a deployment change. |
| **Cloudflare Workers or another edge runtime** | `firebase-admin` does not run there; the REST Admin API would mean reimplementing token verification, claims and recursive deletes. |
| **Trust custom claims in the rules** | Claims live in tokens for up to an hour after a change; a `members` row is revocable on the next request. |
| **Client-written `parentKeys`** (M3 spec §1) | A client cannot be made to prove the key is hardware-backed and biometric-bound, or choose an honest `validFromLamport`; the server can (D-M3-34). |
| **Accept unattested keys in production** | Lets an emulator or a rooted device's software keystore join a household with no evidence of where its key lives. Rejected by Kiril (D-M3-38). |
| **Plain SHA-256 of link codes** | 32^8 codes are enumerable, so anyone with a database copy recovers live codes. |
| **QR approval of a second parent phone** (PLAN) | The fingerprint comparison gives the same check without a camera dependency; a QR can replace the text later. |
| **Revoke by deleting the device row** | Receivers could no longer tell legitimate history from forgeries; `revokedAtLamport` keeps the first and refuses the second. |

## Consequences

**Easier**

- Every identity fact a receiver relies on (membership, role, device status, parent keys, lamport bounds) is written by the server and checked by both the rules and the receivers.
- Losing a device costs its future, not its past: revocation keeps everything signed before `revokedAtLamport`.
- The backend is free to run and testable end to end against the emulators.

**Harder, and gaps**

- **A household with a single parent device has no in-app recovery** if that phone is lost: a new phone stays PENDING, and only an ACTIVE parent device can approve or revoke. Recovery is a manual operator procedure (`docs/RUNBOOK.md`).
- **Reads are gated by membership, not by device.** Any signed-in session of a member uid reads the household, including a PENDING second phone of that account and a stolen phone that signs back in with the parent's Google account. Writes need an ACTIVE device. Accepted until App Check enforcement (M8); see the threat model.
- The parent key is created with the challenge from `beginDeviceRegistration` and its attestation chain is sent to `registerParentKey` (M3 final pass); a registration that fails is shown as "not registered with the household" with a retry that renews the key (D-M3-39). Only the emulator's software chain has exercised this path so far.
- Revoking a parent device signs that parent out everywhere, the calling phone included.
- No real device attestation chain is in the test fixtures yet; the Google-rooted path is tested only with a self-generated chain.
- Rate limits and the HMAC key depend on server configuration (`LINK_CODE_HMAC_KEY`, Firestore TTL policies) that the code cannot check for itself.

## References

D-M3-1, D-M3-2, D-M3-5, D-M3-6, D-M3-10, D-M3-12, D-M3-17, D-M3-18, D-M3-19, D-M3-20, D-M3-22, D-M3-23, D-M3-26, D-M3-29, D-M3-34, D-M3-35, D-M3-36, D-M3-37, D-M3-38 in [`docs/DECISIONS-PENDING.md`](../DECISIONS-PENDING.md). Code: `server/src` (`handlers/`, `attestation.ts`, `registration.ts`, `deviceProof.ts`, `callable.ts`, `rateLimit.ts`, `guards.ts`), `firestore/firestore.rules`, `core/sync-firebase` (`HouseholdFunctions`, `DeviceProof`, `FirebaseAuthSession`, `AppCheckInstaller`), `core/crypto` (`DeviceKeyStore`), `core/database` (`Session.kt`, `RoomRemoteApplier`, `usecase/PurgeLocalHouseholdUseCase.kt`, `usecase/WipeLocalDataUseCase.kt`).
