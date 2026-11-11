# Release checklist (owner)

Every step between the M8 tree and Sprout on the Play production track that needs a console, a key, a real device or a legal decision. The autonomous run prepared the code, documents and assets for each; items ticked "(done 2026-11-10)" were done in the owner's session that day (evidence in the SECURITY_REVIEW log), the rest is still to do. Work top to bottom: later items depend on earlier ones.

- **Conventions:** commands run from the repository root unless they `cd`. `sprout-3e1c7` is the Firebase project (FIREBASE.md). `<host>` is the production server's host (the Vercel production URL or a custom domain); `<hid>` a household id. Production host since 2026-11-10: `sprout-server-kpetriv.vercel.app`. On macOS `base64 -i <file>`, on Linux `base64 -w0 <file>`.
- **Never commit** the keystore, its passwords, `google-services.json`, a service-account key, `docs/privacy.properties` or the PCAPs. All of them stay outside the repository or in gitignored paths (CLAUDE.md).
- **Evidence:** each item ends with the line to paste into [SECURITY_REVIEW.md](SECURITY_REVIEW.md#evidence-log) (or into the item's notes here). No personal data in evidence.
- **Related:** [FIREBASE.md "Production project checklist"](FIREBASE.md#production-project-checklist) (the console steps in detail), [RUNBOOK.md](RUNBOOK.md), [DATA_SAFETY.md](DATA_SAFETY.md), [play/listing/README.md](../play/listing/README.md).

### 1. Prerequisites and project facts

- [ ] Tools: JDK 17, Android SDK (API 37 platform), Node 24, Python 3.9+, `gcloud`, `gh`, `bundletool` (`brew install bundletool`).
- [x] The Firebase project `sprout-3e1c7` is on **Spark** and its Firestore database is in **`eur3`** (Firebase console → Firestore → Settings, or `gcloud firestore databases describe --project sprout-3e1c7 --format='value(locationId)'` → `eur3`). (done 2026-11-10: `firebase firestore:databases:get` → `(default)` in `eur3`)
- [x] Authentication provider **Google** is enabled (FIREBASE.md step 3). (done 2026-11-10: the config carries the web OAuth client and the debug and upload Android clients)
- [x] The **Anonymous** provider is enabled (kid devices; Firebase console → Authentication → Sign-in method). (done 2026-11-11 by the owner)

Evidence to paste: "Firestore location: eur3 (checked <date>)" → CONTROLS DP-11.

### 2. Create the upload keystore

```
mkdir -p ~/.sprout && tools/release/create-upload-keystore.sh ~/.sprout/sprout-upload.jks     # alias defaults to sprout-upload
```

The script asks for the password twice (never on the command line), writes a PKCS12 keystore outside the repository (one password for the store and the key, so `SPROUT_UPLOAD_KEYSTORE_PASSWORD` and `SPROUT_UPLOAD_KEY_PASSWORD` are the same value) and prints the upload key's SHA-1 and SHA-256 (D-M8-3). Keep the keystore and the password in a password manager plus an offline backup; Play App Signing holds the app signing key, so a lost upload key can be reset through Play support, but not quickly.

To print the fingerprints again later (debug and upload key): `tools/release/print-signing-fingerprints.sh ~/.sprout/sprout-upload.jks`.

For local release builds put the values in `~/.gradle/gradle.properties` (never in the repository), or export them as environment variables (`SPROUT_UPLOAD_KEYSTORE`, `SPROUT_UPLOAD_KEYSTORE_PASSWORD`, `SPROUT_UPLOAD_KEY_ALIAS`, `SPROUT_UPLOAD_KEY_PASSWORD`):

```
sprout.upload.keystore=/Users/<you>/.sprout/sprout-upload.jks
sprout.upload.keystorePassword=<from the password manager>
sprout.upload.keyAlias=sprout-upload
sprout.upload.keyPassword=<from the password manager>
```

Done 2026-11-10 by the owner: `~/.sprout/sprout-upload.jks`, alias `sprout-upload`.

Evidence to paste: "Upload keystore created <date>; SHA-256 <first 8 hex>…" (the fingerprint is not secret, but the prefix is enough).

### 3. Register fingerprints and the OAuth web client id

- [x] Debug and upload key fingerprints: `tools/release/print-signing-fingerprints.sh ~/.sprout/sprout-upload.jks` (or `./gradlew :app:signingReport` for the debug key). (done 2026-11-10)
- [x] Firebase console → Project settings → Your apps → `dev.kpetriv.sprout` → Add fingerprint: SHA-1 **and** SHA-256 of the **upload key** (item 2) and of the **debug key** (FIREBASE.md step 10). The **app signing key** is added after the first upload (item 9). (done 2026-11-10 for the upload and debug keys)
- [x] Download `google-services.json` again to `app/` (gitignored). (done 2026-11-10)
- [x] Google Cloud console → APIs & Services → Credentials → "Web client (auto created by Google Service)" → copy its client id (FIREBASE.md step 12). Add to `~/.gradle/gradle.properties`: `sprout.googleServerClientId=<id>.apps.googleusercontent.com`. (done 2026-11-10; also the `release` secret, item 9)
- [x] Also there: `sprout.functionsBaseUrl=https://<host>` and, if the privacy page is not `<host>/privacy`, `sprout.privacyUrl=https://<host>/privacy`. (done 2026-11-10; also the `release` secrets, item 9)

Check: `./gradlew :app:checkReleaseConfiguration` → BUILD SUCCESSFUL (it lists every missing value otherwise).

Evidence to paste: "Fingerprints registered (upload, debug) <date>; checkReleaseConfiguration passes".

### 4. Privacy policy values, legal review and rendering

- [x] Legal review of [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md): every **Owner (legal)** note (controller identity, lawful bases, parental consent D-M8-33, processors, retention figures, the 30-day e-mail deletion, safe harbour). Change the Markdown, not the rendered page. (done 2026-11-11 by the owner, without a lawyer; every note resolved in the Markdown, D-REL-4)
- [x] Decide the D-M6-51 question ([DATA_SAFETY.md](DATA_SAFETY.md#the-d-m6-51-question-owner-and-legal-review)) and confirm the Firebase Instance ID disclosure (D-M8-32: it cannot be excluded, D-M8-41). (done 2026-11-11: the event is listed as recommended; the installation id stays disclosed, D-REL-4)
- [x] Fill the values and render (done 2026-11-10; re-rendered and redeployed 2026-11-11 with `EFFECTIVE_DATE=2026-11-11` after the legal review):

```
cp docs/privacy.properties.example docs/privacy.properties
$EDITOR docs/privacy.properties          # CONTACT_EMAIL, DEVELOPER_NAME, POSTAL_ADDRESS, EFFECTIVE_DATE, SECURITY_CONTACT, PRIVACY_URL
python3 tools/privacy/render.py --properties docs/privacy.properties --strict
npm --prefix server ci && npm --prefix server run build:static
```

Expected: `wrote server/public/privacy/index.html`, `wrote server/public/security/index.html`, `Wrote 2 files`. `--strict` fails if an `example.invalid` address or "(to be filled)" is left. Open `server/public/privacy/index.html` in a browser, in light and dark mode, and read it once end to end.

Evidence to paste: "Legal review <date> by <role>; rendered with EFFECTIVE_DATE <date>" → CONTROLS DP-12.

### 5. Deploy the server and publish the pages

Follow FIREBASE.md "Service account (least privilege)" and "Deploying the server (Vercel)" (step 5 of its checklist): create the `sprout-server` service account, set `FIREBASE_SERVICE_ACCOUNT_JSON`, `FIREBASE_PROJECT_ID` and `LINK_CODE_HMAC_KEY` as sensitive Vercel variables, delete the local key file, then from the working tree that holds the rendered real pages (item 4):

```
cd server && npx vercel@64.7.0 --prod
```

`LINK_CODE_HMAC_KEY` is required: link codes and the rate limit of every callable are keyed from it (D-M8-44), so without it every callable answers `internal`. Vercel's build runs `npm run check:deploy`, which refuses placeholder pages. Then restore the committed example pages: `git checkout server/public` (or `python3 tools/privacy/render.py && npm --prefix server run build:static -- --example`), and check `git status` is clean.

Verify:

```
curl -sI https://<host>/privacy                       # 200, text/html, the CSP and nosniff headers (FIREBASE.md "Static pages")
curl -sI https://<host>/security                      # 200
curl -s  https://<host>/.well-known/security.txt      # Contact, Expires (a year ahead), Policy, Canonical
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<host>/deviceStatus -H 'Content-Type: application/json' -d '{"data":{}}'   # 401
```

- [x] Server deployed and verified as above (done 2026-11-10: Vercel project `sprout-server`, `https://sprout-server-kpetriv.vercel.app`, region `fra1`; all four checks as expected; Deployment Protection "Only Preview Deployments", Node.js 24.x and the `jose` override, FIREBASE.md "Deploying the server (Vercel)", D-REL-1, D-REL-3).
- [x] Deploy the Firestore rules and indexes: `npx --no-install --prefix firestore firebase deploy --only firestore --project sprout-3e1c7`. (done 2026-11-10; one compiler warning at `firestore.rules:588:108`, follow-up D-REL-2)

- [ ] **Yearly reminder:** put a calendar reminder 60 days before the `Expires` date `security.txt` shows (a year after `EFFECTIVE_DATE`): set a new `EFFECTIVE_DATE` (or `SECURITY_TXT_EXPIRES`) in `docs/privacy.properties`, re-render, `npm --prefix server run build:static`, redeploy as above. `check:deploy` warns from 60 days before and refuses an expired file.

Evidence to paste: "Server deployed <date>, region fra1; /privacy 200; security.txt Expires <date>; unauthenticated callable 401; rules deployed" → CONTROLS AV-8, SE-2, IA-21, CM-11, DP-12, LM-9.

### 6. App Check enforcement, TTL, alerts and vendor terms

- [ ] App Check → register `dev.kpetriv.sprout` with Play Integrity (needs the app signing key's SHA-256 from item 9 and the Play ↔ Cloud link from item 7). After a few days of internal-track use with "verified" close to 100 % for Cloud Firestore: **Enforce** (FIREBASE.md step 4, [SECURITY_REVIEW P3](SECURITY_REVIEW.md#p3-play-integrity-and-app-check)).
- [ ] Firestore TTL policies (FIREBASE.md step 6):

```
for c in linkCodes challenges rateLimits; do
  gcloud firestore fields ttls update expiresAt --collection-group=$c --enable-ttl --project sprout-3e1c7
done
gcloud firestore fields ttls list --project sprout-3e1c7          # each ACTIVE
```

- [ ] Schedule the 12-month clean-up of deletion and recovery records: monthly, `npm --prefix server run operator -- prune-expired --production` (dry run), then with `--apply` (FIREBASE.md "Operator script", RUNBOOK §4).
- [ ] Alerts (FIREBASE.md step 8): Firebase usage dashboard bookmarked; Vercel spend and usage notifications on; a weekly look at the function error rate.
- [ ] Vendor terms ([SECURITY_REVIEW "Vendors"](SECURITY_REVIEW.md#vendors)): accept Firebase's Data Processing and Security Terms and Vercel's DPA; note Vercel's log retention for the plan in use and make sure PRIVACY.md's "up to 30 days unless Vercel's retention is longer" still holds.

Evidence to paste: "App Check enforced on Firestore <date> (verified <n> %); TTL ACTIVE x3; prune-expired scheduled; DPAs accepted <date>" → CONTROLS IA-16, DP-7, LM-6, SC-6.

### 7. Play Console: the app, signing and policy declarations

Menu names as of November 2026 (the console was reorganised; the old "App access" form is now "Sign-in details").

- [x] Create the app (Play Console home → **Create app**): name "Sprout", default language English (United Kingdom), app, free, contact e-mail the privacy contact. The same form accepts the Developer Program Policies, US export laws and the **Play App Signing** terms; signing itself is configured on the first release (item 9; FIREBASE.md step 9). (done 2026-11-11 by the owner)
- [x] Play Console → Test and release → App integrity → Play Integrity API → **Link a Cloud project** → `sprout-3e1c7` (FIREBASE.md step 11). (done 2026-11-11 by the owner)
- [x] **Trader status** (Manage account → Account details → About you; every account must declare it, new accounts at creation): **non-trader** (free app, nothing sold; EEA and UK users see a notice that consumer-rights laws do not apply). (done 2026-11-11 by the owner)
- [ ] The declarations below are cards on **Monitor and improve → Policy and programs → App content** ("Start declaration" or "Start", later "Manage"). **Privacy policy:** `https://sprout-server-kpetriv.vercel.app/privacy` (public, no login, not a PDF; also linked from the store listing and inside the app).
- [ ] **Target audience and content:** ages **18 and over** only; the app is not designed for children (kid mode is a parent-configured feature; PLAN "Play policy notes"). Answer "No" to appealing to children; expect no Families Policy obligations.
- [ ] **Content ratings** questionnaire (IARC): category "Utility, productivity, communication or other"; user-to-user communication: **yes** (chat within a household, not public); shares location: no; digital purchases: no; unrestricted internet: no. Expected rating: low (PEGI 3 / Everyone, with an "Users interact" notice).
- [ ] **Ads:** no.
- [ ] **Sign-in details** (formerly App access): "All or some functionality is restricted" → **+ Add new instructions** → name "Any Google account", no username or password fields, and in "Any other instructions": "All features require sign-in with a Google account. Any Google account works: signing in creates a new household. The parent PIN is chosen on first run. No separate test credentials exist." Add a demo account only if review asks for one.
- [ ] **Financial features**, **Health**, **Government apps**, **News**: answer every card the console shows with "none" or "no" (the current help page no longer lists them all).
- [ ] Permissions declarations: none of Sprout's permissions needs a declaration form (no SMS, call log, location, accessibility, all-files, exact alarm or foreground-service-type declarations beyond WorkManager's defaults); re-check the Console's list after the first upload.

Evidence to paste: "Play app created <date>; Play App Signing on; Cloud project linked; trader status non-trader; target audience 18+; content rating <rating>".

### 8. Play Console: data safety and the store listing

- [ ] **Data safety** (App content → Data safety; exempt while the app is only on the internal track, required before closed testing): enter [DATA_SAFETY.md](DATA_SAFETY.md) section by section (Section 1, every row of Section 2, Section 3). Delete account URL: `https://<host>/privacy#how-to-delete-your-account-and-data`. Before submitting, `python3 tools/privacy/inventory.py --check` passes on the commit being released.
- [ ] **Store listing** (Grow users → Store presence → Store listings; the support e-mail is under Store settings) from [`play/listing`](../play/listing/README.md): title, short and full description (`play/listing/en-GB/*.txt`), app icon (512 × 512), feature graphic (1024 × 500), phone screenshots, 7-inch and 10-inch tablet screenshots, in the upload order of `play/listing/README.md`. Category: Parenting. Contact e-mail: the privacy contact. Website: `https://<host>/privacy` or the repository.
- [ ] To regenerate the images after a UI change: `python3 tools/store/compose.py` (needs Pillow; see `play/listing/README.md`).

Evidence to paste: "Data safety submitted <date> from DATA_SAFETY.md at <commit>; listing uploaded <date>".

### 9. Build and upload the release (internal track)

**The `release` environment** (D-M8-42, audit M2). The repository is public: a repository-wide secret could be read by any workflow on any branch, so the signing secrets live only in a protected environment. Once:

- [x] GitHub → the repository → Settings → Environments → **New environment** `release` → *Deployment branches and tags*: **Selected branches and tags** → add `main` only → *Required reviewers*: yourself → *Prevent self-review* off (you are the only reviewer) → Save. With `gh` (your numeric id: `gh api user --jq .id`): `gh api -X PUT repos/<owner>/<repo>/environments/release -F 'deployment_branch_policy[protected_branches]=false' -F 'deployment_branch_policy[custom_branch_policies]=true' -f 'reviewers[][type]=User' -F 'reviewers[][id]=<your id>'`, then `gh api -X POST repos/<owner>/<repo>/environments/release/deployment-branch-policies -f name=main -f type=branch`. Check in Settings → Environments that it shows `main` and you as reviewer. (done 2026-11-10)
- [ ] Delete any repository-level copies of these secrets (`gh secret list`; `gh secret delete <name>`).
- [x] Optional: a tag ruleset (Settings → Rules → Rulesets → New tag ruleset, target `v*`, restrict creations to yourself). (done 2026-11-10: "Release tags", `v*` creation, update and deletion by the admin role only)

Environment secrets (names as in `.github/workflows/release.yml`), set once with `gh` (each `gh secret set` without `--body` asks for the value; never type it on the command line):

```
base64 -i app/google-services.json        | gh secret set GOOGLE_SERVICES_JSON_B64 --env release
base64 -i ~/.sprout/sprout-upload.jks      | gh secret set SPROUT_UPLOAD_KEYSTORE_BASE64 --env release
gh secret set SPROUT_UPLOAD_KEYSTORE_PASSWORD --env release      # paste when asked
gh secret set SPROUT_UPLOAD_KEY_ALIAS --env release
gh secret set SPROUT_UPLOAD_KEY_PASSWORD --env release
gh secret set SPROUT_FUNCTIONS_BASE_URL --env release --body 'https://<host>'
gh secret set SPROUT_GOOGLE_SERVER_CLIENT_ID --env release --body '<id>.apps.googleusercontent.com'
gh secret set SPROUT_PRIVACY_URL --env release --body 'https://<host>/privacy'
# optional: only when the disclosure page is not /security on the privacy page's host
gh secret set SPROUT_SECURITY_URL --env release --body 'https://<host>/security'
# optional, publishes to the internal track (off unless set): a Play service account with release permissions
gh secret set PLAY_SERVICE_ACCOUNT_JSON --env release < play-service-account.json && rm play-service-account.json
```

- [x] `SPROUT_FUNCTIONS_BASE_URL`, `SPROUT_PRIVACY_URL`, `SPROUT_SECURITY_URL`, `SPROUT_GOOGLE_SERVER_CLIENT_ID` (done 2026-11-10).
- [ ] The keystore and password secrets (`SPROUT_UPLOAD_KEYSTORE_BASE64`, `SPROUT_UPLOAD_KEYSTORE_PASSWORD`, `SPROUT_UPLOAD_KEY_ALIAS`, `SPROUT_UPLOAD_KEY_PASSWORD`): the owner.

Tag a commit on `main` that CI built green, and run the workflow from `main` (the version name comes from the tag; see "Versioning" below):

```
git switch main && git pull && gh run list --workflow ci.yml --branch main --limit 1   # completed, success
git tag -a v1.0.0 -m "Sprout 1.0.0" && git push origin v1.0.0
gh workflow run release.yml --ref main -f tag=v1.0.0
# approve the deployment when GitHub asks (the run page, or the e-mail), then:
gh run watch                                       # tag on main, CI passed, static checks, bundleRelease, the checks
gh run download --name sprout-v1.0.0               # the signed AAB and mapping.txt
```

The run summary shows the versionCode it built. The workflow refuses a tag whose commit is not on `main`, a commit without a successful CI run (tag the head commit of a push), a `gradle.properties` that sets `sprout.allowUnconfiguredRelease`, and any missing secret (by name).

Without the Play secret, upload the AAB by hand: Play Console → Test and release → Internal testing → Create release → upload → add testers (a Google group or e-mails) → Save and roll out.

**Local alternative** (the same Gradle checks; it skips the workflow's tag, main and CI checks and the inventory step, so run `python3 tools/privacy/inventory.py --check` yourself): on a clean checkout of the tag, with `app/google-services.json` in place, the signing values from item 2 and the configuration from item 3 in `~/.gradle/gradle.properties`, or on the command line:

```
git switch --detach v1.0.0
./gradlew :app:bundleRelease \
  -Psprout.functionsBaseUrl=https://<host> \
  -Psprout.googleServerClientId=<id>.apps.googleusercontent.com \
  -Psprout.privacyUrl=https://<host>/privacy        # only if not <host>/privacy
# -> app/build/outputs/bundle/release/app-release.aab
```

`-Psprout.allowUnconfiguredRelease=true` waives the configuration check for a local test bundle only; never upload one built that way.

Local signed `bundleRelease` proof (2026-11-10): signed AAB 18.7 MB by CN=kpetriv (alias sprout-upload), versionCode 91, versionName from a temporary local v0.1.0-rc.1 tag (deleted; never pushed), jarsigner verified, mapping.txt present, checkReleaseConfiguration/checkMergedManifestForTelemetryRelease/checkReleaseMapping/checkReleaseAppCheck all ran

- [ ] After the first upload: Play Console → Test and release → App integrity → App signing → copy the **app signing key** SHA-1 and SHA-256 → add both in Firebase (item 3) and download `google-services.json` again; update the secret: `base64 -i app/google-services.json | gh secret set GOOGLE_SERVICES_JSON_B64 --env release`.
- [ ] Keep `mapping.txt` with the release (Play Console also takes it for deobfuscated crash stacks).

#### Versioning

- **versionName** is the newest `v*` tag reachable from the commit, without the `v` (`v1.2.0` builds `1.2.0`). Without a tag a build is `0.1.0-dev`, which `checkReleaseConfiguration` refuses for a Play bundle (D-M8-46).
- **versionCode** is the number of commits on the tagged commit (`git rev-list --count HEAD`), or the `version_code` input (`-f version_code=<n>`; locally `-PversionCode=<n>`).
- **Release from `main` only:** the commit count grows only along one branch, so a tag on another branch could go backwards.
- **Every upload needs a higher versionCode than the last one Play has:** Play refuses a code it has seen ("Version code N has already been used"). Re-running the workflow for the same tag, or a second tag on the same commit, builds the same code: pass `-f version_code=<last + 1>` (the last code is in Play Console → App bundle explorer, or the previous run's summary), or make a new commit and tag.
- **Full history:** a shallow clone counts only the fetched commits (versionCode 1); the workflow checks out with full history.
- Debug builds carry the same numbers.

Evidence to paste: "v1.0.0 (versionCode <n>) built by release.yml run <id> on <date> in the release environment; internal track rolled out; app signing SHA registered" → CONTROLS SE-6.

### 10. Device verification

Run [SECURITY_REVIEW.md](SECURITY_REVIEW.md#owner-procedures) P1 to P10 on the internal-track build: the network capture including a receipt scan (P1), the release build walk (P2), Play Integrity and App Check (P3; then enforce, item 6), Google sign-in (P4), key attestation (P5), voice notes (P6), time bank auto-stop (P7), process death (P8), Gemini Nano on a Pixel 9 or later (P9), TalkBack (P10).

Evidence to paste: one row per procedure in the SECURITY_REVIEW evidence log; then update the CONTROLS rows it names.

### 11. Closed testing, then production

Promote internal → **closed testing** when:

- [ ] P1 to P5 passed and their rows are updated; App Check is enforced on Firestore.
- [ ] Data safety and the listing are submitted; the privacy page is live with real values.
- [ ] CI is green on the release commit, including "Privacy pages up to date" and "Data-safety inventory".

Play requires, for a new personal developer account, a closed test with at least 12 testers opted in for 14 continuous days before production access; plan for it.

Promote closed → **production** when:

- [ ] The closed test ran its required time with no crash or ANR above Play's bad-behaviour thresholds (Android vitals) and no data-loss or sync report open.
- [ ] P6 to P10 passed, or each failure has a fix released to the closed track.
- [x] The legal review is complete and every **Owner (legal)** note is resolved. (done 2026-11-11, D-REL-4)
- [ ] RUNBOOK contacts are current; the operator script has been run once against production in dry-run mode (`npm --prefix server run operator -- prune-expired --production`).
- [ ] Staged rollout: 10 %, then 50 %, then 100 %, watching vitals and the server error rate between steps.

Evidence to paste: "Closed test <dates>, <n> testers; production rollout started <date>".
