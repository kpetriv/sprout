# Sprout incident runbook

- **Status:** First version (M3, 2026-11-03); M8 (2026-11-04) added the operator script (§2, §4) and the static pages to the deploy (§8). Not yet exercised in a drill: run §2 and §4 once against the emulators (FIREBASE.md "Operator script") before release, and review after the first real incident.
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
   - revokes the parent account's refresh tokens and sets `tokensValidAfter`: **every session of that account signs out, the phone doing the revoking included**. That phone shows "Sign in again": sign in with the same account (its data and unsynced changes stay; another account is refused). Writes from sessions older than the revocation are refused by the rules;
   - the lost phone erases its copy of the household **the next time the app starts, or syncs, with a network**: its refresh token is revoked, so it shows "Sign in again" (no household data behind it) and asks the server with the token-less `deviceStatus` (signed by its device key, D-M3-48); REVOKED wipes it. A phone that stays offline keeps an encrypted copy behind that gate and the parent PIN until it next comes online. A thief who signs that phone back in with the parent's Google account gets nothing on it either: the status check runs first and the device is REVOKED.
2. **At Google:** remove the phone from the Google account (Google Account → Security → Your devices) and lock or erase it with Find My Device. Sprout reads are gated by membership, not by device: a thief who can sign the phone back in with the parent's Google account could still **read** the household until the account is secured (threat model T33).
3. **Kid devices** need nothing: they keep syncing; they hold anything the lost phone writes after revocation as quarantined.
4. **Re-adding a replacement phone:** sign in on it, then approve it from the remaining parent phone ("New device waiting", compare fingerprints).

**The lost phone was the household's only parent device** (or every phone of the only parent is gone). There is no in-app recovery: a new phone registers PENDING, and only an ACTIVE parent device can approve it or revoke the old one. The operator script does what `revokeDevice` and `approveDevice` would (D-M3-42, D-M8-14, D-M8-43); never edit the registry by hand in the console.

This path is also the one an attacker who took over the parent's Google account would use (threat model T41): they can sign in on their own phone and write to you that "the phone is lost". So the script never revokes a phone of the account that still works (ACTIVE) until the new phone has waited **7 days**; in that time the working phone shows "New device waiting" on the dashboard and lists the new phone in Settings → Devices as "Waiting for approval", where the real parent taps **Remove**. Never shorten the wait in production (`--waiting-period-days` exists for tests only).

1. **The parent signs in on the new phone** with the same Google account. It registers PENDING and shows its fingerprint (four groups of four hex characters).
2. **Find the ids** in the Firebase console: Authentication → search the account → *User UID*; Firestore → `userHouseholds/{uid}` → `hid`. Do not copy the e-mail address into notes; the incident record uses the uid and hid.
3. **Verify the requester controls that Google account** before anything else: send a one-time phrase to the account's address and have them read it back to you from the new phone's session (or reply from the account). A caller who cannot do this is refused; the household's data stays where it is. This proves control of the mailbox, which an attacker with the account also has: it is necessary, not sufficient, hence the dry run's checks below.
4. **Dry run** (from `server/`, with credentials as in FIREBASE.md "Operator script"):

   ```
   export GOOGLE_APPLICATION_CREDENTIALS=$PWD/../sprout-server-service-account.json FIREBASE_PROJECT_ID=sprout-3e1c7
   npm run operator -- recover-household --hid <hid> --new-parent-uid <uid> --production
   ```

   Expected: `Target: PRODUCTION project sprout-3e1c7`, the household's parent devices (`<deviceId> user <uid> <status> registered <time>`), then `admit PENDING parent device <deviceId> of user <uid> as ACTIVE`, one `revoke parent device …` line per other phone of that parent (the lost ones) and `revoke the sessions of user <uid>`, and the exact `--apply --device <deviceId> --confirm <hid>` to use. Check:
   - the first 16 characters of `<deviceId>` are the fingerprint the new phone shows (if the parent registered more than one phone the script lists them and needs `--device <deviceId>`);
   - **an ACTIVE phone in the revoke lines is a red flag**: it was working, so the "lost" phone may not be lost. Ask when it was last used, and whether the requester can still open it (then they approve the new phone there instead). The dry run then also says `--apply would be refused: … wait until <time>` while the new phone registered less than 7 days ago. Tell the requester to keep the new phone signed in and come back after that time; do not apply earlier. If the new phone disappears from the list (the working phone removed it), stop and treat the request as an attack: note it in the incident, tell the account's owner through the household's other contact if there is one;
   - a phone `(admitted by the operator)` shows an earlier recovery.

   It refuses when another parent of the household still has an ACTIVE phone (that parent approves the new phone in the app instead), when the uid is not a parent of that household, or when nothing is PENDING.
5. **Apply:**

   ```
   npm run operator -- recover-household --hid <hid> --new-parent-uid <uid> --device <deviceId> --ticket <incident-id> --apply --confirm <hid> --production
   ```

   The new phone becomes ACTIVE (`approvedBy: "operator"`); each lost phone becomes REVOKED at a lamport above everything it wrote, with its parent keys stamped, as an in-app revocation does (the start of this section); the parent's refresh tokens are revoked and `tokensValidAfter` set, so **every phone of that account signs out, the new one included**: the parent signs in again on it and carries on as after an in-app approval (registering the parent key for signing). A lost phone wipes itself when it next comes online, as above. `recoveries/{id}` records `{ hidHash, at, deviceId, revokedDevices, ticket }` (no uid, no name); put that id in the incident record. Delete the key file afterwards (§7).

   `--apply` always needs the `--device` the dry run admitted. If the run stops after the transaction while revoking the sessions (a network error: it exits 1 and names the devices left), run the same command again without `--device`: with nothing PENDING it completes the session revocation (`complete: revoke the sessions of user <uid> …` in the dry run).

If the parent has also lost the Google account, the household cannot be recovered: Google's account recovery comes first; failing that, the only path is deleting the household (§4).

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

**By e-mail or another channel.** Verify the requester controls the household's parent Google account before doing anything (§2 step 3; find the uid and hid as in §2 step 2). If they still have a parent phone, guide them through the in-app path. If they do not, delete it with the operator script (from `server/`, credentials as in FIREBASE.md "Operator script"). Answer within one month (GDPR Art. 12).

1. Offer an export first (right of access); it has no size limit here and holds personal data, so hand it over securely and delete your copy:

   ```
   npm run operator -- export-household --hid <hid> --out ~/sprout-export-<hid>.json --production
   ```

2. Dry run, then delete (the same path as `deleteHousehold`: member rows, `households/{hid}` recursively, link codes, challenges, `userHouseholds` rows and every member's Auth user, after writing the `deletionRecords` row):

   ```
   npm run operator -- delete-household --hid <hid> --production
   # 2 member(s), 3 device(s), 120 event(s), 14 document(s); 2 Auth user(s) to delete
   npm run operator -- delete-household --hid <hid> --apply --confirm <hid> --production
   # Done: deletionRecords row written (hidHash …); 2 user(s) deleted, 0 failure(s)
   ```

   If Auth users failed to delete, the output lists their uids (`These Auth users were not deleted: …`). Do **not** run the apply again: the household's rows are gone, so a second run finds nothing (`No household`). Delete each uid by hand: Firebase console → Authentication → Users → search the UID → ⋮ → **Delete account** (there is no `firebase` CLI command for it). Note in the incident that they were removed. A count of link code, challenge or `userHouseholds` rows that failed is printed too: expired codes and challenges go with the monthly prune; delete a left-over `userHouseholds/<uid>` row in the console. Record the `hidHash` in the incident, not the hid.

   The in-app deletion logs only counts (`household.deleted`: `userDeleteFailures`, `cleanupFailures`); a failure there leaves an Auth user with no household data behind it. The log holds counts only, by design, so there is no list: when it shows failures, `npx --prefix firestore firebase auth:export /tmp/users.json --project sprout-3e1c7`, delete (as above) each user whose `customAttributes` name a `hid` with no `households/<hid>` document, then delete `/tmp/users.json` (it holds e-mail addresses).

**Retention after deletion.** `deletionRecords` and `recoveries` rows: 12 months (PRIVACY.md), then deleted by the monthly prune below. Server logs: Vercel's log retention (PRIVACY.md); they carry ids and counts only. Link codes, challenges and rate-limit rows expire on their own (TTL on `expiresAt`, FIREBASE.md checklist step 6).

**Monthly prune** (first working day of the month; also the fallback while TTL is not enabled):

```
npm run operator -- prune-expired --production            # counts per collection
npm run operator -- prune-expired --apply --production
```

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

Server, from `server/`, after rendering the real static pages (FIREBASE.md "Static pages": fill `docs/privacy.properties`, `python3 tools/privacy/render.py --properties docs/privacy.properties --strict`, `npm run build:static`):

```
npx vercel@64.7.0 --prod
git checkout public          # back to the committed example pages
```

Vercel's build runs `npm run check:deploy` for every deployment except previews and development builds (a missing `VERCEL_ENV` counts as production), which fails the deployment while the pages carry example or placeholder values or `security.txt` has expired, and warns when `security.txt` expires within 60 days. **Yearly:** before the `Expires` date in `security.txt` (a year after `EFFECTIVE_DATE`), set a new `EFFECTIVE_DATE` or `SECURITY_TXT_EXPIRES` in `docs/privacy.properties`, re-render and redeploy (RELEASE_CHECKLIST item 5). Then the smoke check (§6) and `curl -sI https://<production-url>/privacy` (200, `content-security-policy` and `cache-control: public, max-age=3600`). Never set `SPROUT_EMULATOR`, `ALLOW_UNATTESTED`, `FIRESTORE_EMULATOR_HOST` or `FIREBASE_AUTH_EMULATOR_HOST` on Vercel. A rules change that tightens what clients may write can leave outbox items "sync blocked" on devices that wrote under the old rules; ship the client change first.

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
