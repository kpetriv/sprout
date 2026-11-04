# Sprout privacy policy

<!-- repo-only -->
> **Source of the published page.** This file is rendered to `server/public/privacy/index.html` by `tools/privacy/render.py`, which fills the `{{...}}` placeholders from `docs/privacy.properties` (gitignored; the committed page is rendered from `docs/privacy.properties.example`, so it shows `example.invalid` addresses) and leaves out every block marked `repo-only`, like this note. CI fails when the committed page no longer matches this file (`python3 tools/privacy/check.py`). The Play data-safety answers are derived from this file and a build inventory: [DATA_SAFETY.md](DATA_SAFETY.md).
>
> **Not legal advice, and the legal review has not happened.** The structure and the facts are final for v1 and match the code as of M8; the paragraphs marked **Owner (legal)** in repo-only notes need a decision by the owner and a lawyer before the page is published ([release checklist](RELEASE_CHECKLIST.md) item 4). Related: [threat model](THREAT_MODEL.md), [controls matrix](CONTROLS.md), [security review](SECURITY_REVIEW.md), [ADR-0003](adr/0003-security-model.md), [ADR-0007](adr/0007-identity-linking-and-device-registry.md), [ADR-0010](adr/0010-on-device-ai.md).
<!-- /repo-only -->

- **Effective date:** {{EFFECTIVE_DATE}}
- **Applies to:** the Sprout Android app (`dev.kpetriv.sprout`) and the Sprout server it talks to
- **Who we are:** {{DEVELOPER_NAME}}, {{POSTAL_ADDRESS}}, the developer of Sprout ("we", "us")
- **Contact:** [{{CONTACT_EMAIL}}](mailto:{{CONTACT_EMAIL}})

<!-- repo-only -->
> **Owner (legal):** the controller identity, the lawful basis for each purpose (contract for running the household; legitimate interests for security and abuse prevention are the likely candidates), and how parental consent fits the parent-as-controller-of-their-household model. Recorded consent at household creation is not built (CONTROLS DP-13, D-M8-33); this page therefore does not claim it.
<!-- /repo-only -->

## The short version

- Sprout is for parents. A parent sets up the household and decides what goes into it about each child.
- We collect the minimum the app needs to work: a first name, an avatar, a birth month and year, and the household's chores, rewards and pocket-money ledger.
- Chat messages, voice notes and photos are end-to-end encrypted. We cannot read them.
- No ads, no analytics, no crash reporting. No location, no contacts, no advertising ID.
- All AI features run on your phone. What you type and photograph is never sent anywhere for AI. One exception to "no metrics": when a parent scans a receipt, Google Play services records that its scanner was used (never the receipt), and receipt scanning is off whenever the On-device AI switch is off.
- You can export or delete your household's data at any time, from the app.

## Who this is for

Sprout's Play audience is adults (18+). Kid mode is a feature the parent configures on a child's phone. Children do not create accounts, do not sign in, and do not give us information directly; everything about a child is entered or chosen by the parent, apart from what the child creates while using kid mode (chore submissions, game results, purchases from the parent's catalogue, chat messages).

## What we collect

### About the household and each child (chosen by the parent)

| Data | Why | Notes |
|---|---|---|
| Child's first name | Shown in the app | No surname, no e-mail address, no phone number |
| Avatar | Shown in the app | An id for one of the app's built-in avatars, not a photo |
| Birth month and year | Suggests an age-appropriate kid UI | No day of birth |
| Kid UI tier | Picks the kid-mode layout | Suggested from age; the parent can change it |

### Household activity

| Data | Why | Notes |
|---|---|---|
| Chores, rewards and savings goals | The core of the app: what needs doing, what it earns, what can be bought | Titles and amounts the parent writes |
| Ledger entries | Every credit and debit of pocket money and screen-time minutes, with who made it, from which household phone, and when | Signed, so tampering is detectable. These are household pocket-money records, not payments: Sprout never handles real money or card details. |
| Free text in ledger entries | A parent's reason for a manual adjustment (it may be prefilled from a receipt as "Receipt: &lt;shop&gt;", which the parent reviews before signing), and an optional note when covering an overdraft | Up to 200 characters each. **Not end-to-end encrypted**: like the rest of the ledger, readable by our server. Please don't put sensitive details in these notes. |
| Game results | Score and credit for each brain game played | One signed ledger entry per play: which game and difficulty, how many rounds were right, the credit and the day played. The questions, answers and timings stay on the phone and are not stored. Credit is subject to a daily cap. |
| Time bank sessions | When screen time was started and stopped | Recorded only; the app does not control the phone |

### Chat and photos (end-to-end encrypted)

| Data | Why | Notes |
|---|---|---|
| Chat messages (text and preset messages) | Parent and child talk in the app | Encrypted on the sending phone with a key only the household's current phones hold. We store ciphertext only and cannot read it. A phone removed from the household gets no key for messages written after its removal. |
| Voice notes | Young children can talk instead of type | Recorded only while the child holds the record button, at most 30 seconds, compressed on the phone, then encrypted like messages. The microphone permission is asked for the first time a voice note is recorded. The recording's temporary file is deleted as soon as it is encrypted (and at the next start if the app was closed mid-recording); a voice note is played straight from memory, never written to a file. |
| Photos in chat | A parent can share a photo from the phone's photo picker | Shrunk on the phone (at most 1280 pixels), location and other photo metadata removed, then encrypted like messages. Only the photo you pick is read. |
| Chore photos | Optional proof a chore is done | Taken with the phone's own camera app, which hands Sprout the one photo taken: Sprout does not ask for the camera permission and cannot use the camera itself. Encrypted on the child's phone with the household keys and uploaded so a parent's phone can show it; deleted from the child's phone when the parent decides. |
| Chat routing data | Delivering and ordering messages | Who sent a message to which child's thread, from which household phone, when, its kind (text, preset, voice, photo) and size, and which household phones can decrypt it. Visible to our server; the content is not. |

Encrypted photos and voice notes are stored as encrypted chunks in the household's Cloud Firestore database, next to the messages. Sprout does not use Cloud Storage.

### About the parent

| Data | Why | Notes |
|---|---|---|
| Google account e-mail address | Signing in with Google through Firebase Authentication, and showing which account is signed in | Held by Firebase Authentication and shown in Settings → Account. Not copied into the household's data. Sprout never sees or stores your Google password. |
| Google account name and profile picture link | Not used by Sprout | Firebase Authentication copies them from your Google account into its sign-in record when you sign in with Google, as it does for every app. Sprout does not read, show or copy them. They are deleted with the sign-in account. |
| Firebase user id (uid) | Identifies the parent's account inside the household: membership, role, and the author of each ledger entry and document | A random identifier, not derived from your e-mail address. |

### Technical data

| Data | Why | Notes |
|---|---|---|
| Device id and device public key | Proving which household phone wrote what; listing the household's phones | The device id is the fingerprint (SHA-256) of the phone's public signing key. Private keys never leave the phone. |
| Key attestation level | Showing whether a phone's key is protected by secure hardware (StrongBox, TEE, software) | Verified by our server from the certificate chain the phone's keystore produces at registration; the chain itself is not stored. |
| Kid phone account id | A kid's phone gets its own anonymous Firebase account, so it never holds the parent's sign-in | No e-mail address, name or phone number. Disabled when the parent removes the phone; deleted with the household. |
| IP address | Rate limiting the server's sign-up, linking and admin operations against abuse | Used transiently by our server. Stored only inside a rate-limit record id as a keyed hash (with the operation name; the key is a secret only our server holds, so the address cannot be worked back out from the record), never in clear, and deleted when the limit window ends (at most an hour). Our server does not log it; our host (Vercel) and Google record it as part of handling each request, under their terms (see "Server logs"). |
| App integrity tokens (Firebase App Check with Play Integrity) | Rejecting modified apps and scripts | Each request to our server and to the database carries a short-lived token from Firebase App Check, which Google issues after a Play Integrity check of the app and the phone. Checked, not stored, not used for tracking. |
| Firebase installation id and instance token | Not used by Sprout | The Firebase library Sprout uses to call our server includes Firebase Instance ID, which can create a Firebase installation id for the app on the phone, register it with Google, and attach an instance token to requests to our server. Sprout does not use push messages or these identifiers; our server neither stores nor logs the token. Google processes the installation id under its terms. |
| Server logs | Operating and securing the server | Our server logs identifiers (household, account and phone ids), counts and outcome codes only: never names, e-mail addresses, link codes, keys, IP addresses or household content. Our host, Vercel, keeps these logs together with its own request records (time, path, status, the caller's IP address) for up to 30 days, unless Vercel's retention is longer: see [Vercel's privacy policy](https://vercel.com/legal/privacy-policy). |
| Receipt-scan usage event | Recorded by Google Play services, not by Sprout | One event per receipt scan: an event id, an error code, and start and end times; never the picture or the text read from it. See "On-device AI". |

<!-- repo-only -->
> **Code pointers for the technical rows** (kept current by the [data-safety inventory](DATA_SAFETY.md)): device id `core/crypto` `DeviceKeyStore`; attestation `server/src/attestation.ts`; anonymous kid accounts `FirebaseAuthSession.signInAnonymously`; IP rate limits `server/src/rateLimit.ts`; App Check `core/sync-firebase` `AppCheckInstaller.kt`, `server/src/callable.ts`; the installation id and instance token come from `com.google.firebase:firebase-iid` under `firebase-functions` (the server passes `Firebase-Instance-ID-Token` through and never reads it, `server/src/callable.ts`); excluding it stops Firebase from starting (D-M8-41), so it ships and is disclosed here. Server logs `server/src/log.ts`. The receipt event: D-M6-51.
<!-- /repo-only -->

## What we do not collect

- No advertising, no advertising ID, no ad SDKs.
- No analytics and no crash reporting, from us or from the libraries Sprout ships. Google's ML Kit libraries include a usage-metrics uploader, and Sprout removes it; the build fails if it comes back. Google Play services records each receipt scan (see "On-device AI").
- No location.
- No contacts, calendar, call logs, SMS or files outside what you pick.
- No child's surname, e-mail address, phone number, school, or exact date of birth.
- No access to other apps or app usage. Sprout does not request Accessibility, usage stats, overlay or device-admin permissions.
- No message, chore, figure or photo is sent off the phone for AI.

## Permissions Sprout asks for

| Permission | What for | When |
|---|---|---|
| Internet and network state | Syncing the household and calling our server | Granted at install |
| Microphone | Recording a voice note | Asked the first time a child records one; chat works without it |
| Notifications | The "screen time running" notification of the time bank | Asked the first time the time bank is started; the timer works without it |
| Biometric | Unlocking the parent area and signing money entries with the parent's fingerprint | Granted at install; used only when the parent chooses to |
| Run at startup, wake lock, foreground service | Background sync and clean-up jobs (Android WorkManager), and keeping the home screen widget current | Granted at install |
| AICore | Asking Android's on-device AI service (Gemini Nano) for an answer | Granted at install; used only while the On-device AI switch is on |

Some Google libraries Sprout includes declare further permissions of their own (for Firebase's messaging client and Google services configuration). Sprout does not use push messages, and none of those permissions gives Sprout access to your data.

<!-- repo-only -->
> The full merged-manifest list, with the library each one comes from, is in [DATA_SAFETY.md](DATA_SAFETY.md) (inventory checked in CI).
<!-- /repo-only -->

## Google services Sprout relies on

Sprout runs on Google's infrastructure for sign-in, sync and a few phone features. Each one processes some data under Google's own terms:

- **Firebase Authentication** holds the parent's sign-in account (Google sign-in) and each kid phone's anonymous account.
- **Cloud Firestore** stores the household's data, in Europe (see "Where your data is stored").
- **Firebase App Check and Google Play Integrity** check that requests come from the genuine Sprout app on a genuine phone.
- **Credential Manager and Google sign-in** (part of Android and Google Play services) show the Google account picker and hand Sprout a sign-in token for the account you choose.
- **Google Play services text recognition** reads a receipt on the phone when a parent scans one, and records one usage event per scan.
- **AICore** (Gemini Nano) runs the on-device model on supported phones.
- **Firebase Instance ID**, included by the Firebase library Sprout uses to call our server, may register an installation id with Google.

## On-device AI

AI features (chore suggestions, the weekly parent summary, the "kindness check" before a child sends a message, and reading a receipt to prefill an adjustment) run entirely on your phone, using Gemini Nano where the phone supports it and simple built-in rules where it does not. The kindness check only ever nudges the child; it never blocks a message and never reports it to anyone, including the parent.

- **No content is sent to the network for AI,** to us or anyone else: no message, chore, figure, name or photo. There is no cloud AI in Sprout.
- **ML Kit's own usage metrics are switched off; receipt scanning is the exception.** Sprout uses Google's ML Kit libraries for Gemini Nano and for text recognition. They include an uploader that would send Google usage metrics (device and app details, identifiers, timings and input and output sizes, never the text or the photo). Sprout removes that uploader from the app, and the build fails if it comes back. Receipt text recognition, however, runs as part of Google Play services, which records one usage event per scan (an event id, an error code and start and end times; never the picture or the text read from it) and sends it to Google like its other services, under Google's terms. Sprout cannot switch that off, so receipt scanning runs only while the On-device AI switch is on, and the "Scan a receipt" note says so.
- **What each feature gives the on-device model.** Kindness check: the message the child is about to send. Chore suggestions: the child's age band, the titles of their existing chores and the household currency. Weekly summary: that week's figures (amounts earned, spent and saved, chores done, games played) and each child's first name. Receipts never use the model: the photo is read by on-device text recognition and parsed by built-in rules.
- **The models.** Gemini Nano runs inside AICore, a Google system service on the phone. Sprout asks for the model to be downloaded only after a parent agrees once in Settings → On-device AI; the system then downloads it in the background, usually on Wi-Fi (Sprout cannot guarantee Wi-Fi). Until then, and on phones without AICore, the built-in rules do everything. The text-recognition model for receipts is downloaded by Google Play services; it holds no user data, and the receipt picture is read on the phone. AICore and Google Play services are Google system components: they download and run the models under Google's own terms, outside Sprout's control.
- **The switch covers this phone only.** AI can be switched off in Settings → On-device AI; the rules keep every feature working, and receipt scanning is off while it is. A child's own linked phone has no parent area, so it always uses the built-in rules for the kindness check. On a parent's phone in kid mode, the kindness check may run Gemini Nano on the child's message under that phone's own switch and download consent, both set by the parent in the parent area; it still runs only on that phone.
- **Nothing is kept.** Suggestions, summaries and kindness results are worked out when shown and never stored or logged. A receipt prefill only fills the adjustment form: the amount and the shop's name (as "Receipt: &lt;shop&gt;") enter the ledger only if the parent reviews and signs it, and then the reason is plain text like any other adjustment reason. The picked receipt photo is read in memory and not stored.
- **Settings stay on the phone.** The AI switch and the download consent are stored on each phone, not synced, and excluded from backups.

## Reading messages aloud

For young children Sprout can read chat messages aloud. The message text is handed to the phone's own text-to-speech engine (the one chosen in the phone's settings). Sprout asks for an installed voice that works without a network, but cannot see what the engine does: if the phone is set up with an engine that synthesises speech online, that engine's provider may receive the text. Nothing is sent to us.

## Where your data is stored

- **On your phones.** Everything is stored in an encrypted database (SQLCipher) whose key is protected by the phone's hardware-backed keystore. Sprout's data is excluded from Android cloud backup and phone-to-phone transfer.
- **In the cloud.** Household data syncs between your family's phones through Google Firebase: **Cloud Firestore in the `eur3` multi-region (Europe)** holds the household's data, including the encrypted chat, photos and voice notes, and Firebase Authentication (a global Google service, not pinned to the EU) holds the sign-in accounts. Account and admin operations (creating a household, linking phones, export, deletion, chat clean-up) run on **our server, hosted by Vercel in Frankfurt (`fra1`)**, which processes requests and stores nothing of its own beyond logs. Data is encrypted in transit (TLS) and, by Google, at rest.
- **Before you set up sync, nothing leaves the phone.** A household tried out on one phone stays there. When that phone joins a real household, the app tells you the trial data will be deleted, and deletes it; it is never uploaded.

## Who can see your data

| Who | What they can see |
|---|---|
| Parents in the household | Everything in the household, including the full ledger and audit log |
| A child, in kid mode | Their own chores, balance, ledger entries, shop, and chat with the parent; never parent settings or other children's data |
| Our server and its operator | Household data and the ledger, in order to sync it and to export or delete it. **Not** the content of chat messages, voice notes or photos. Allowance and interest are calculated on the parent's phone, not on the server. |
| Google, as our cloud provider | Stores and processes the data on our behalf under its data processing terms, and runs the Google services listed above under its own terms |
| Vercel, as host of our server | Processes requests to the server on our behalf; sees the request contents in transit and keeps request logs |
| Anyone else | Nobody. We do not sell or share data, and there are no advertising or analytics partners. |

We will disclose data only if the law requires it, and then only what is required.

<!-- repo-only -->
> **Owner (legal):** the legal-disclosure sentence above, and whether Google and Vercel are named as processors with links to their data processing terms (CONTROLS SC-6, vendor review).
<!-- /repo-only -->

## How long we keep it

| Data | How long | How it is enforced |
|---|---|---|
| The household's data (profiles, chores, rewards, ledger, devices) | As long as the household exists | Deleted in full when a parent deletes the household |
| Chat messages, voice notes and photos | As the household chooses: forever (the default), or 30, 90 or 365 days | Each phone deletes its own older messages daily and after it syncs; a parent's phone asks our server to delete its copy at most once a day. A phone that is never opened again keeps its copy. |
| Records that chat was cleaned up | As long as the household exists | When and how many messages and file parts; never content |
| Device-linking codes | 10 minutes, single use | Refused after expiry by our server; stored only as a keyed hash |
| Device registration challenges | 10 minutes | Refused after expiry by our server |
| Rate-limit records | The limit window (at most an hour) | Ignored after expiry by our server; removed by an automatic database clean-up |
| Household deletion records | 12 months | Removed by our operator's monthly clean-up |
| Account recovery records | 12 months, also after the household is deleted | Written only when we restore a parent's access to a household whose parent phones were all lost: a one-way hash of the household id, the phone ids involved, the time and our ticket reference. Removed by our operator's monthly clean-up |
| Our server's logs | Up to 30 days, unless Vercel's retention is longer | Vercel's log retention |
| Sign-in accounts | Until the household is deleted (kid phone accounts are disabled when the phone is removed) | Deleted with the household |
| Data on a phone | Until the app is uninstalled, the parent signs out, or the phone is removed from the household | Erased by the app; uninstalling removes it too |
| Export files | As long as you keep them | Saved where you choose; we never receive them |

Sprout keeps no backups of its own. Google's own retention of deleted data follows its data processing terms.

<!-- repo-only -->
> **Enforced vs. proposed (owner).** Enforced in code today: codes, challenges and rate-limit records are refused after expiry (CONTROLS DP-7, `linking.test.ts`, `registration.ts`, `rateLimit.ts`); household deletion (DP-4); chat retention and purge (M7). **Proposed until the console and operator steps are done:** removing expired rate-limit rows, codes and challenges needs the Firestore TTL policies (FIREBASE.md production checklist; RELEASE_CHECKLIST item 6); `deletionRecords` and `recoveries` have no TTL field, so their 12 months depend on the owner's monthly `prune-expired` run (`server/scripts/operator.ts`, RELEASE_CHECKLIST item 6, D-M8-51). Vercel's log retention depends on the Vercel plan; confirm it on the project and keep "up to 30 days unless Vercel's retention is longer". **Owner (legal):** confirm the processor retention figures.
<!-- /repo-only -->

## Your data, your copy

A parent can download the household's data from Settings → Household data → Export household. The phone saves **one zip file** where the parent chooses:

- `household.json`: the household, its members' roles, its phones and parent keys, every kid profile, chore, submission, reward, schedule, goal and policy, and every ledger entry, plus chat messages and photo and voice-note records in their encrypted form;
- `ledger.csv`: the ledger as a spreadsheet, made on the phone;
- `chat/`: after the parent confirms with their fingerprint or parent PIN, each child's chat in readable form (`chat/<child>.json`: time, who wrote it, the text, preset or voice-note length), the photos and voice notes the phone can decrypt (`chat/media/`), and a list of any it could not include (`chat/missing.json`).

The readable chat is decrypted on the parent's phone from its own copy and written straight into the file the parent chose; nothing readable is sent to us. Keep the file safe: it is no longer encrypted. Households above 4 MB of server data cannot be exported in one file yet; contact us and we will export it for you.

## How to delete your account and data

**In the app (recommended).** A parent can delete the household from Settings → Household data → Delete household, after typing the household id and signing in again if the last sign-in is older than 5 minutes. This removes all of the household's cloud data, the sign-in accounts of every parent and kid phone linked to it, its link codes and registration records, and wipes the local data on every household phone the next time it connects. We keep one record that a deletion happened: a one-way hash of the household id, the time, and how many members, phones, entries and documents were deleted, with no household content, for 12 months. If we ever restored a parent's access to the household, the record of that recovery (a one-way hash of the household id, the phone ids and the time) is also kept for 12 months from the recovery.

**Chat only.** Settings → Household data → Delete chat history now (after typing DELETE and confirming with a fingerprint or the parent PIN) deletes every chat message up to that moment on that phone and on our server. Other household phones keep their own copy until their retention period removes it, they are wiped, or the household is deleted. If every household phone is lost, chat history cannot be recovered: we hold no keys.

**Removing one phone.** A parent can remove a lost or old phone in Settings → Devices; it loses access, and its copy is erased the next time it connects.

**By e-mail.** If you can no longer use the app (for example, every parent phone is lost), e-mail [{{CONTACT_EMAIL}}](mailto:{{CONTACT_EMAIL}}) from the Google account that created the household, with the household id if you have it. We confirm the request and delete the household with the same steps as the app, usually within 30 days, and tell you when it is done.

<!-- repo-only -->
> **Owner (legal):** the 30-day figure and how the requester is verified (RUNBOOK §4; slice A's `operator.ts delete-household`). This section is the Play Console "delete account" web link: `{{PRIVACY_URL}}#how-to-delete-your-account-and-data`.
<!-- /repo-only -->

**Uninstalling** the app removes all local data from that phone. Cloud data remains until the household is deleted.

## Children's data and your rights

The parent decides what Sprout holds about their child and can view, correct, export or delete it at any time from the app.

Depending on where you live (for example under the GDPR or UK GDPR), you have the right to access, correct, export and erase your data, to object to or restrict processing, and to complain to your data protection authority. Most of these you can do yourself in the app; for anything else, contact us at [{{CONTACT_EMAIL}}](mailto:{{CONTACT_EMAIL}}).

## Security

- The local database is encrypted with a key held in the phone's hardware keystore.
- The parent area is behind a PIN or biometric; money entries are signed by the parent's biometric-bound key.
- Every ledger entry is signed and chained, so tampering is detectable.
- Chat, voice notes and photos are end-to-end encrypted.
- All network traffic uses TLS; modified apps are rejected by Firebase App Check on our server and on the database.
- New phones join only with a code or an approval from an existing parent phone, and a lost phone can be removed from another parent phone.

To report a vulnerability, see our [security and disclosure policy](SECURITY.md) and [`security.txt`]({{SECURITY_TXT_URL}}).

<!-- repo-only -->
> App Check enforcement on Firestore is a console step (CONTROLS IA-16, release checklist item 6); until it is switched on, "and on the database" above is not true. The page must not be published before that step.
<!-- /repo-only -->

## Changes to this policy

We will update this page when the app's handling of data changes and change the effective date at the top. Material changes will be announced in the app before they take effect.

## Contact

{{DEVELOPER_NAME}}, {{POSTAL_ADDRESS}}. E-mail: [{{CONTACT_EMAIL}}](mailto:{{CONTACT_EMAIL}}).
