# Sprout incident runbook

- **Status:** First version (M3, 2026-11-03). Not yet exercised in a drill; review after the first real incident and before release (M8).
- **Owner:** Kiril Petriv (sole operator). Consoles: Firebase project `sprout-3e1c7` (Spark), the Vercel project for `server/`, the GitHub repository `kpetriv/sprout`.
- **Related:** [Firebase backend](FIREBASE.md) (every command below comes from there), [threat model](THREAT_MODEL.md), [controls matrix](CONTROLS.md), [ADR-0006](adr/0006-sync-protocol-and-document-envelope.md), [ADR-0007](adr/0007-identity-linking-and-device-registry.md).

## Ground rules

1. **Contain first, investigate second.** Revoking a device or rotating a key is cheap and reversible in effect (re-link, re-approve); waiting is not.
2. **Never copy household content out of the console.** Investigate with ids, counts, timestamps and reason codes. Server logs carry only those (`server/src/log.ts`); keep it that way in notes and tickets.
3. **Prefer the in-app path.** Parents can revoke, export and delete themselves. Operator actions against production data use the Admin SDK or the console and are written down here before they are used.
4. **Record every incident**: date, what was seen, what was done, ids involved (device ids, `hidHash`), and the follow-up. A security finding also gets a row in `docs/DECISIONS-PENDING.md` or an ADR amendment.

## 1. Suspected forged or tampered events

**What the parent sees.** The dashboard's "Rejected events" card lists each event this phone refused: a short reason (unreadable, altered, bad signature, parent signature missing or invalid, unknown or inactive device, wrong account, role not allowed, out of order, other), the author device (role, kid name, fingerprint) and when it arrived. No payload is shown. A forged event never changes a balance: it is quarantined and kept as evidence (ADR-0006 §6).

**Triage**

| Reason shown | Likely cause | Action |
|---|---|---|
| Parent signature missing or invalid | A kid device (rooted, or kid mode used to impersonate a parent) writing parent-only events; or a parent key the registry does not know | Check which device wrote it. A KID device: §3. A PARENT device: check Settings → Devices that its parent key is registered (signing enabled) |
| Bad signature, altered (hash mismatch) | A modified client, or someone with project credentials editing a stored event | Revoke the author device (§3). If the author is a parent device the parent does not recognise, treat as §2 and rotate the service account (§7) |
| Other (`FORKED_CHAIN`, `KID_MISMATCH`) | One device signed two different histories (equivocation: a cloned or rooted device), or a kid device wrote for another kid | Revoke that device. Both branches of a fork stay quarantined on every device |
| Out of order (`LAMPORT_TOO_FAR_AHEAD`) | A forged or corrupt lamport far above anything seen | Revoke the author if it repeats; one such event cannot move any device's clock |
| Unknown or inactive device | A revoked device still writing, or a registry the phone has not pulled yet | Usually benign; it clears once the registry syncs. Persisting entries from a REVOKED device confirm the revocation works |

**Inspect.** On the parent phone: the card and Settings → Devices (role, attestation label, status). In the Firestore console (`households/{hid}/events`), filter by `authorDeviceId` to see the volume and lamports a device wrote; `households/{hid}/devices/{deviceId}` shows its status, attestation and `revokedAtLamport`. Quarantine is decided on each device; the server holds the raw events.

**Contain.** From an ACTIVE parent phone: Settings → Devices → the device → **Revoke**. The server sets `revokedAtLamport` above everything the device wrote, so its past stays and anything new is refused everywhere (ADR-0007 §7).

## 2. Lost or stolen parent phone

1. **From another ACTIVE parent phone of the household:** Settings → Devices → the lost phone → **Revoke**. This
   - marks it REVOKED at a lamport above its last event or document, and stamps its parent keys;
   - revokes the parent account's refresh tokens and sets `tokensValidAfter`: **every session of that account signs out, the phone doing the revoking included**. Sign in again on it; writes from sessions older than the revocation are refused by the rules;
   - on its next sync, the lost phone sees its own entry REVOKED and wipes its local data (if it is still online and the app runs).
2. **At Google:** remove the phone from the Google account (Google Account → Security → Your devices) and lock or erase it with Find My Device. Sprout reads are gated by membership, not by device: a thief who can sign the phone back in with the parent's Google account could still **read** the household until the account is secured (threat model T33).
3. **Kid devices** need nothing: they keep syncing; they hold anything the lost phone writes after revocation as quarantined.
4. **Re-adding a replacement phone:** sign in on it, then approve it from the remaining parent phone ("New device waiting", compare fingerprints).

**The lost phone was the household's only parent device.** There is **no in-app recovery in M3**: a new phone registers PENDING, and only an ACTIVE parent device can approve it or revoke the old one. Operator recovery needs an Admin SDK script that does what `revokeDevice` and `approveDevice` do (REVOKED with the computed `revokedAtLamport`, parent keys stamped, `tokensValidAfter`, refresh tokens revoked, the new device ACTIVE) after verifying the requester controls the household's Google account. That script is **not written yet**; do not edit the registry by hand in the console. Until it exists the only fallback is deleting the household (§4), which loses its data.

## 3. Kid device compromised (rooted, cloned, or in the wrong hands)

1. From an ACTIVE parent phone: Settings → Devices → the kid's phone → **Revoke**. The server disables the kid's anonymous user, revokes its refresh tokens and deletes its membership, so it can neither read nor write any more. Its events below `revokedAtLamport` stay; later ones are refused.
2. Check the "Rejected events" card for what it tried (§1).
3. To re-link the kid on a clean device: Settings → Kids → the kid → **Link a device**, and enter the code on the new phone within 10 minutes.
4. Balances need no repair: forged credits were never applied (quarantined), and order-dependent overspends are resolved by the projection the same way on every device.

## 4. Household deletion request

**In the app (normal path).** A parent: Settings → Data → **Delete household**, types the household id, and must have signed in within the last 5 minutes (sign out and in again if refused). The server (`deleteHousehold`):

- deletes the member rows, then `households/{hid}` recursively (events, documents, devices, parent keys), the household's link codes, and for every member and registered device owner (revoked kids included) the registration challenge, the `userHouseholds` row and the **Auth user**;
- writes one `deletionRecords/{autoId}` row: `{ hidHash: sha256(hid), at, counts }`, nothing else;
- every other household device wipes itself on its next sync (positive evidence: its account is gone).

Suggest an **export first** (Settings → Data → Export household), which is the only copy the parent keeps (no server backups on Spark). Export refuses households above 4 MB.

**By e-mail or another channel.** Verify the requester controls the household's parent Google account before doing anything. If they still have a parent phone, guide them through the in-app path. If they do not, an operator deletion needs an Admin SDK script equivalent to `deleteHousehold` (not written yet; the same gap as §2). Answer within one month (GDPR Art. 12).

**Retention after deletion.** `deletionRecords` rows: 12 months (proposed, PRIVACY.md), then delete. Server logs: 30 days at most. Link codes, challenges and rate-limit rows expire on their own (TTL on `expiresAt`, FIREBASE.md checklist step 6).

## 5. Link-code abuse

**Signs.** Bursts of `resource-exhausted` from `redeemLinkCode` or `createLinkCode` in the server logs (they log the callable and outcome, never the code or the IP).

**Built-in limits** (`server/src/rateLimit.ts`): `redeemLinkCode` 10 / hour per uid, 10 / hour per IP, 20 / hour per code id across IPs, counted before the lookup; `createLinkCode` 20 / hour per uid, 60 / hour per IP, 5 live codes per household. Codes live 10 minutes and work once. Wrong, used and expired codes all answer `not-found`.

**If a code may have leaked or the HMAC key is suspected exposed,** rotate `LINK_CODE_HMAC_KEY` (outstanding codes, at most 10 minutes' worth, stop working; parents create new ones):

```
cd server
npx vercel@64.7.0 env rm LINK_CODE_HMAC_KEY production
openssl rand -base64 32 | tr -d '\n' | npx vercel@64.7.0 env add LINK_CODE_HMAC_KEY production --sensitive
npx vercel@64.7.0 --prod
```

If a kid device was linked through a leaked code, revoke it (§3). If a proxy is ever put in front of the server, the per-IP limit keys on that proxy's address until the hop count is configured (FIREBASE.md "Client address").

## 6. Server or Firebase outage

**What users see.** Sync is offline-first: writes keep landing locally, the outbox backs off (1 s doubling to at most 1 hour, with jitter; 60 s after `RESOURCE_EXHAUSTED`) and drains when the backend returns. The dashboard's sync line shows a failing sync. Nothing is lost.

| Outage | Affected | Not affected |
|---|---|---|
| **Sprout server (Vercel)** | Creating a household, linking kids, approving or revoking devices, registering parent keys, export, delete | Sync: devices read and write Firestore directly |
| **Firestore or Auth** | Sync and every callable | Local use of the app |

**Steps.** Check the Vercel deployment and its function logs, and the Firebase status page. Smoke check the server (no App Check token, so `401` means it is up):

```
curl -i -X POST https://<production-url>/createHousehold -H 'content-type: application/json' -d '{"data":{}}'
# 401 {"error":{"message":"Sign in first","status":"UNAUTHENTICATED"}}
```

If a deployment broke it, roll back to the previous production deployment in the Vercel dashboard (or `npx vercel@64.7.0 rollback`) and fix forward. After the outage, a parent phone showing **"sync blocked"** has a ledger event the server refused for good; that is not an outage symptom and needs §1-style investigation of the code shown.

## 7. Rotating the server's service account key

Do it on any suspicion of exposure, when someone with Vercel access leaves, and at least yearly.

```
SA=sprout-server@sprout-3e1c7.iam.gserviceaccount.com
gcloud iam service-accounts keys create sprout-server-service-account.json --iam-account $SA
cd server
npx vercel@64.7.0 env rm FIREBASE_SERVICE_ACCOUNT_JSON production
base64 -i ../sprout-server-service-account.json | npx vercel@64.7.0 env add FIREBASE_SERVICE_ACCOUNT_JSON production --sensitive
npx vercel@64.7.0 --prod
cd ..
gcloud iam service-accounts keys list --iam-account $SA
gcloud iam service-accounts keys delete <old-key-id> --iam-account $SA
rm sprout-server-service-account.json
```

Smoke check (§6) between the deploy and deleting the old key. `base64 -i` is the macOS form; `base64 -w0` on Linux. If the key was exposed, also review the project's Admin activity in Cloud Audit Logs for writes to `members`, `devices` and `parentKeys`, which only the server makes.

## 8. Deploying rules, indexes and the server

Tests first, from the repository root (Node 24, Java 21 for the emulators):

```
cd firestore && npm ci && npm run lint && npm test
cd server && npm ci && npm run lint && npm run build && npm test
```

Rules and indexes (Spark is enough):

```
npx --no-install --prefix firestore firebase deploy --only firestore --project sprout-3e1c7
```

Deploy the indexes before relying on `revokeDevice` (it queries every collection on `(authorDeviceId, lamport desc)`) and wait until they show as built.

Server, from `server/`:

```
npx vercel@64.7.0 --prod
```

Then the smoke check (§6). Never set `SPROUT_EMULATOR`, `ALLOW_UNATTESTED`, `FIRESTORE_EMULATOR_HOST` or `FIREBASE_AUTH_EMULATOR_HOST` on Vercel. A rules change that tightens what clients may write can leave outbox items "sync blocked" on devices that wrote under the old rules; ship the client change first.

## 9. Reproducing locally with the emulators

Prerequisites: Node 24, Java 21 (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"` on macOS with Homebrew). From the repository root, in two terminals:

```
npm --prefix firestore ci && npm --prefix server ci
npx --no-install --prefix firestore firebase emulators:start --only auth,firestore --project demo-sprout
npm --prefix server run dev
```

The server listens on `http://127.0.0.1:8787` (App Check off, unattested keys accepted as `NONE`, the public development link-code key); the Android emulator reaches it at `http://10.0.2.2:8787`, and debug builds use the emulator ports through `BuildConfig.FIREBASE_EMULATOR_HOST` (Gradle property `sprout.firebaseEmulatorHost`, default `10.0.2.2`). Install with `./gradlew :app:installDebug` on two AVDs (a parent and a kid). The real-SDK transport suite:

```
./gradlew :core:sync-firebase:testDebugUnitTest -Psprout.firebaseEmulator=true --tests '*FirebaseEmulatorTransportTest'
```

`demo-sprout` is a demo project: nothing run this way can reach production. Never point a local server at `sprout-3e1c7` to reproduce an incident; reproduce with synthetic data.

## 10. CI flake procedure (D-M3-21)

Known flake: `:app:hiltJavaCompile*` fails with `[Dagger/MissingBinding]` for bindings of library modules whose `compile*JavaWithJavac` ran NO-SOURCE (Hilt's KSP-generated `hilt_aggregated_deps` missing on the runner).

1. Open the failed run's **"Diagnose KSP outputs"** step and save its output with the run id: it shows whether KSP generated nothing or javac did not see the files.
2. Re-run the failed job once. If it passes, add the run id and the diagnosis to D-M3-21 in `docs/DECISIONS-PENDING.md`.
3. If the same commit fails twice, it is not the flake: reproduce locally with a clean build (`./gradlew clean build`).
4. On a second confirmed occurrence, try in order: `--max-workers=2` (or `kotlin.compiler.execution.strategy`) for the static step, then a newer KSP 2.3.x, each on its own branch with three green runs before merging.
5. Never merge on a red required check; the backend job (not required yet, D-M3-11) gets the same treatment.
