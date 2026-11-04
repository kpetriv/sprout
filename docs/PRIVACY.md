# Sprout privacy policy

> **DRAFT.** First draft written at the end of M1 as the skeleton for the Play Store listing and data-safety form. It describes the app as planned for v1, with the milestone each part arrives in. Not legal advice and not yet reviewed. M8 finalises it: legal review, contact details, effective date, and the published page. Items marked **TODO (M8)** are open.

- **Last updated:** 2026-11-03 (draft; M3 items resolved)
- **Applies to:** the Sprout Android app (`dev.kpetriv.sprout`)
- **Related:** [threat model](THREAT_MODEL.md), [controls matrix](CONTROLS.md), [ADR-0003 Security model](adr/0003-security-model.md), [ADR-0007 Identity and linking](adr/0007-identity-linking-and-device-registry.md)

## The short version

- Sprout is for parents. A parent sets up the household and decides what goes into it about each child.
- We collect the minimum the app needs to work: a first name, an avatar, a birth month and year, and the household's chores, rewards, and ledger.
- Chat messages and chore photos are end-to-end encrypted. We cannot read them.
- No ads. No analytics in v1. No location, no contacts, no advertising ID.
- All AI features run on your device. What you type and photograph is never sent anywhere for AI, and the usage-metrics uploader in Google's ML Kit libraries is switched off in Sprout. One exception to "no metrics": when a parent scans a receipt, Google Play services records that its scanner was used (never the receipt); scanning is off when the On-device AI switch is off.
- You can export or delete your household's data at any time (from M3).

## Who this is for

Sprout's Play audience is adults (18+). Kid mode is a feature the parent configures on a child's device. Children do not create accounts, do not sign in, and do not give us information directly; everything about a child is entered or chosen by the parent, apart from what the child creates while using kid mode (chore submissions, game results, purchases from the parent's catalogue, chat messages).

## What we collect

### About the household and each child (chosen by the parent)

| Data | Why | Notes |
|---|---|---|
| Child's first name | Shown in the app | No surname, no email, no phone number |
| Avatar | Shown in the app | An id for one of the app's built-in avatars, not a photo |
| Birth month and year | Suggests an age-appropriate kid UI | No day of birth |
| Kid UI tier | Picks the kid-mode layout | Suggested from age; the parent can change it |

### Household activity

| Data | Why | Notes |
|---|---|---|
| Chores and rewards | The core of the app: what needs doing, what it earns, what can be bought | Titles and amounts the parent writes |
| Ledger events | Every credit and debit of pocket money and screen-time minutes, with who made it, from which device, and when | Signed, so tampering is detectable |
| Free text in ledger events | A parent's reason for a manual adjustment (from M6 it may be prefilled from a receipt as "Receipt: <shop>", which the parent reviews before signing), and an optional note when covering an overdraft | Up to 200 characters each. **Not end-to-end encrypted**: like the rest of the ledger, readable by the household's cloud backend from M3. Please don't put sensitive details in these notes. |
| Game results | Score and credit for each brain game played | One signed ledger event per play (from M5): which game and difficulty, how many rounds were right, the credit and the day played. The questions, answers and timings stay on the phone and are not stored. Credit is subject to a daily cap. |
| Time bank sessions | When screen time was started and stopped | Recorded only; the app does not control the device |

### Chat and photos (end-to-end encrypted)

| Data | Why | Notes |
|---|---|---|
| Chat messages (text and preset messages) | Parent and child talk in the app (from M4) | Encrypted on the sending device with a key only the household's current devices hold (ADR-0008). We store ciphertext only and cannot read it. A phone removed from the household gets no key for messages written after its removal. |
| Voice notes | Young children can talk instead of type (from M4) | Recorded only while the child holds the record button, at most 30 seconds, compressed on the phone, then encrypted like messages. The microphone permission is asked for the first time a voice note is recorded. The recording's temporary file is deleted as soon as it is encrypted (and at the next start if the app was closed mid-recording); a voice note is played straight from memory, never written to a file. Removing the phone from the household also clears the app's cache. |
| Photos in chat | A parent can share a photo from the phone's photo picker (from M4) | Shrunk on the phone (at most 1280 pixels), location and other photo metadata removed, then encrypted like messages. Only the photo you pick is read. |
| Chore photos | Optional proof a chore is done | Encrypted on the child's phone with the same household keys and uploaded so a parent's phone can show it (from M4); deleted from the child's phone when the parent decides. Camera access is requested only when you attach a photo. |
| Chat routing data | Delivering and ordering messages | Who sent a message to which child's thread, from which household device, when, its kind (text, preset, voice, photo) and size, and which household devices can decrypt it. Visible to our backend; the content is not. |
| Read-aloud | Reading chat messages to young children | The message text is handed to the phone's own text-to-speech engine (the one chosen in the phone's settings). Sprout asks for an installed voice that works without a network, but cannot see what the engine does: if the phone is set up with an engine that synthesises speech online, that engine's provider may receive the text. Nothing is sent to us. |

### About the parent (from M3)

| Data | Why | Notes |
|---|---|---|
| Google account e-mail address | Signing in with Google through Firebase Authentication, and showing which account is signed in | Held by Firebase Authentication and shown in Settings → Account. Not copied into the household's data. Sprout never sees or stores your Google password. |
| Firebase user id (uid) | Identifies the parent's account inside the household: membership, role, and the author of each ledger entry and document | A random identifier, not derived from your e-mail address. |

### Technical data (from M3)

| Data | Why | Notes |
|---|---|---|
| Device id and device public key | Proving which household device wrote what; listing the household's devices | The device id is the fingerprint (SHA-256) of the device's public signing key. Private keys never leave the device. |
| Key attestation level | Showing whether a device's key is protected by secure hardware (StrongBox, TEE, software) | Verified by our server from the certificate chain the phone's keystore produces at registration; the chain itself is not stored. |
| Kid device account id | A kid's phone gets its own anonymous Firebase account, so it never holds the parent's sign-in | No e-mail, name or phone number. Disabled when the parent removes the device; deleted with the household. |
| IP address | Rate limiting the server's sign-up, linking and admin operations against abuse | Used transiently. Stored only inside a hashed rate-limit bucket id (with the operation name), never in clear, and deleted when the limit window ends (at most an hour). Not logged. |
| App integrity tokens (Firebase App Check, Play Integrity) | Rejecting modified apps and scripts | Checked on each request to our server, not stored, not used for tracking. |
| Server logs | Operating and securing the server | Identifiers (household, account and device ids), counts and outcome codes only: never names, e-mail addresses, link codes, keys, IP addresses or household content. Kept for at most 30 days. |
| Crash reports | Fixing bugs | **Off unless you turn them on.** Personal data is scrubbed before sending. **TODO (M8):** confirm whether Crashlytics ships in v1. |

## What we do not collect

- No advertising, no advertising ID, no ad SDKs.
- No analytics in v1 from us, and none from the SDKs Sprout ships: Google's ML Kit libraries include a usage-metrics uploader, and Sprout removes it. Google Play services records each receipt scan's outcome and timing (see "On-device AI").
- No location.
- No contacts.
- No child's surname, email address, phone number, school, or exact date of birth.
- No access to other apps or app usage. Sprout does not request Accessibility, usage stats, overlay, or device-admin permissions.
- No message, chore, figure or photo is sent off the device for AI.

## On-device AI

AI features (chore suggestions, the weekly parent summary, the "kindness check" before a child sends a message, and reading a receipt to prefill an adjustment) run entirely on your device, using Gemini Nano where the phone supports it and simple built-in rules where it does not. The kindness check only ever nudges the child; it never blocks a message and never reports it to anyone, including the parent.

- **No content is sent to the network for AI,** to us or anyone else: no message, chore, figure, name or photo. There is no cloud AI in Sprout.
- **ML Kit's own usage metrics are switched off; receipt scanning is the exception.** Sprout uses Google's ML Kit libraries for Gemini Nano and for text recognition. They include an uploader that would send Google usage metrics (device and app details, identifiers, timings and input and output sizes, never the text or the photo). Sprout removes that uploader from the app, and the build fails if it comes back. Receipt text recognition, however, runs as part of Google Play services, which records one usage event per scan (an event id, an error code and start and end times; never the picture or the text read from it) and sends it to Google like its other APIs, under Google's terms. Sprout cannot switch that off, so receipt scanning runs only while the On-device AI switch is on, and the "Scan a receipt" note says so. A network capture on a real phone, including a receipt scan, is part of the pre-release security review (M8).
- **What each feature gives the on-device model.** Kindness check: the message the child is about to send. Chore suggestions: the child's age band, the titles of their existing chores and the household currency. Weekly summary: that week's figures (amounts earned, spent and saved, chores done, games played) and each child's first name. Receipts never use the model: the photo is read by on-device text recognition (part of Google Play services) and parsed by built-in rules.
- **The models.** Gemini Nano runs inside AICore, a Google system service on the phone. Sprout asks for the model to be downloaded only after a parent agrees once in Settings → On-device AI; the system then downloads it in the background, usually on Wi-Fi (Sprout cannot guarantee Wi-Fi). Until then, and on phones without AICore, the built-in rules do everything. The text-recognition model for receipts is downloaded by Google Play services when Sprout is installed; it holds no user data, and the receipt picture is read on the phone by Google Play services' on-device text recognition (Play services code loaded into Sprout). AICore and Play services are Google system components: they download and run the models under Google's own terms, outside Sprout's control.
- **The switch covers this phone only.** AI can be switched off in Settings → On-device AI; the rules keep every feature working, and receipt scanning is off while it is. A child's own linked phone has no parent area, so in this version it always uses the built-in rules for the kindness check, whatever any switch says; a parent setting for kid phones comes later. On a parent's phone in kid mode, the kindness check may run Gemini Nano on the child's message under that phone's own switch and download consent, both set by the parent in the parent area; it still runs only on that phone.
- **Nothing is kept.** Suggestions, summaries and kindness results are worked out when shown and never stored or logged. A receipt prefill only fills the adjustment form: the amount and the shop's name (as "Receipt: <shop>") enter the ledger only if the parent reviews and signs it, and then the reason is plaintext like any other adjustment reason (see "Free text in ledger events"). The picked receipt photo is read in memory and not stored.
- **Settings stay on the device.** The AI switch and the download consent are stored on each phone, not synced, and excluded from backups.
- **Data-safety form note:** on-device AI processing is not "collection" (no content leaves the device for it), so it adds no user-content data type to the form; the receipt-derived reason is covered by the existing free-text row once the parent signs it. Two conditions: ML Kit's uploader must stay removed (the build check; if it ever ships, the form must list device or other identifiers and app performance data collected by an SDK), and **TODO (M8):** decide with the legal review whether Google Play services' per-scan usage event (event id, error code, timings) must be listed as app performance data (diagnostics) collected through an SDK.

## Where your data is stored

- **On your devices.** Everything is stored in an encrypted database (SQLCipher) whose key is protected by the phone's hardware-backed keystore. Sprout's data is excluded from Android cloud backup and device-to-device transfer.
- **In the cloud (from M3).** Household data syncs between your family's devices through Google Firebase: **Cloud Firestore in the `eur3` multi-region (Europe)** holds the household's data, and Firebase Authentication (a global Google service, not pinned to the EU) holds the sign-in accounts. Account and admin operations (creating a household, linking devices, export, deletion) run on **our server, hosted by Vercel in Frankfurt (`fra1`)**, which processes requests and stores nothing of its own beyond logs. Data is encrypted in transit and, by Google, at rest. Cloud Storage is used from M4 for encrypted photos and voice notes.
- **Before you set up sync, nothing leaves the device.** A household tried out on one phone stays there. When that phone joins a real household, the app tells you the trial data will be deleted, and deletes it; it is never uploaded.

## Who can see your data

| Who | What they can see |
|---|---|
| Parents in the household | Everything in the household, including the full ledger and audit log |
| A child, in kid mode | Their own chores, balance, ledger entries, shop, and chat with the parent; never parent settings or other children's data |
| Sprout's backend and its operator | Household data and the ledger, in order to sync it and to export or delete it. **Not** the content of chat messages or photos. Allowance and interest are calculated on the parent's phone, not on the server. |
| Google, as our cloud provider | Stores the data on our behalf under its data processing terms |
| Vercel, as host of our server | Processes requests to the server on our behalf; sees the request contents in transit and keeps request logs |
| Anyone else | Nobody. We do not sell or share data, and there are no advertising or analytics partners. |

We will disclose data only if the law requires it, and then only what is required. **TODO (M8):** legal review of this statement.

## How long we keep it, and how to delete it

- **Your household's data** is kept for as long as the household exists.
- **Export (M3):** a parent can download the household's data from Settings → Data → Export household: one JSON file with the household, its members' roles, its devices and parent keys, every kid profile, chore, submission, reward, schedule, goal and policy, and every ledger entry, plus a `ledger.csv` made on the phone. The parent chooses where to save it. Households above 4 MB cannot export yet. From M4 the export also lists chat messages and photo and voice-note records in their encrypted form, which we cannot read; a readable chat export from a phone that can decrypt it comes in M7.
- **Delete household (M3):** a parent can delete the household from Settings → Data → Delete household, after typing the household id and signing in again if the last sign-in is older than 5 minutes. This removes all of the household's cloud data, the sign-in accounts of every parent and kid device linked to it, its link codes and registration records, and wipes local data on every household device the next time it syncs. We keep one record that a deletion happened: a one-way hash of the household id, the time, and how many members, devices, events and documents were deleted, with no household content, for 12 months. Sprout keeps no backups of its own on the current plan; Google's own retention of deleted data follows its data processing terms. **TODO (M8):** confirm the processor retention figures for the published policy.
- **Chat (from M4):** messages, voice notes and photos are kept, encrypted, for as long as the household exists; deleting the household deletes them. Each phone keeps a copy it can decrypt in its encrypted database and a cache of at most 50 MB of encrypted photos and voice notes, erased with the phone's data. **Chat retention (M7):** parents will be able to auto-delete chat messages after a set number of days. If every household phone is lost, chat history cannot be recovered: we hold no keys.
- **Short-lived data:** device-linking codes expire after 10 minutes, work once, and are stored only as a keyed hash; device registration challenges expire after 10 minutes; rate-limit records after the limit window (at most an hour).
- **Audit records:** the household's audit log lives on each device, holds ids and reason codes only, and is deleted with the device's data. Deletion records are kept for 12 months and server logs for at most 30 days (proposed in M3; enforcement of the 12 months is still to be set up, and both figures are confirmed at M8).
- **Uninstalling** the app removes all local data from that device. Cloud data remains until the household is deleted.

## Children's data and your rights

The parent decides what Sprout holds about their child and can view, correct, export, or delete it at any time. Consent is asked for when the household is created.

Depending on where you live (for example under the GDPR or UK GDPR), you have the right to access, correct, export, and erase your data, to object to or restrict processing, and to complain to your data protection authority. Most of these you can do yourself in the app; for anything else, contact us. **TODO (M8):** controller identity, lawful basis wording, and how parental consent interacts with the parent-as-controller model in the plan.

## Security

Summary for the listing; the detail is in the [threat model](THREAT_MODEL.md).

- Local database encrypted with a key held in the phone's hardware keystore.
- Parent area behind a PIN or biometric; money events signed by the parent's biometric-bound key (from M2).
- Every ledger event signed and chained, so tampering is detectable.
- Chat and photos end-to-end encrypted (from M4).
- All network traffic over TLS; modified apps rejected by App Check on our server (from M3) and on the database (from M8).
- New devices join only with a code or an approval from an existing parent device, and a lost device can be removed from another parent phone (from M3).

## Changes to this policy

We will update this page when the app's handling of data changes and show the date at the top. Material changes will be announced in the app before they take effect.

## Contact

**TODO (M8):** a privacy contact email address, the developer's name and postal address as required by Play, and a link to `security.txt` for reporting vulnerabilities.
