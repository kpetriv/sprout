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
- All AI features run on your device. Nothing is sent anywhere for AI.
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
| Free text in ledger events | A parent's reason for a manual adjustment, and an optional note when covering an overdraft | Up to 200 characters each. **Not end-to-end encrypted**: like the rest of the ledger, readable by the household's cloud backend from M3. Please don't put sensitive details in these notes. |
| Game results | Score and credit for each brain game played | Subject to a daily cap |
| Time bank sessions | When screen time was started and stopped | Recorded only; the app does not control the device |

### Chat and photos (end-to-end encrypted)

| Data | Why | Notes |
|---|---|---|
| Chat messages and voice notes | Parent and child talk in the app | Encrypted on the sending device; only household devices hold the keys. We store ciphertext only. |
| Chore photos | Optional proof a chore is done | Encrypted the same way. Camera access is requested only when you attach a photo. |
| Chat routing data | Delivering messages and notifications | Who sent a message to which thread, when, and its size. Visible to our backend; the content is not. |

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
- No analytics in v1, from us or anyone else.
- No location.
- No contacts.
- No child's surname, email address, phone number, school, or exact date of birth.
- No access to other apps or app usage. Sprout does not request Accessibility, usage stats, overlay, or device-admin permissions.
- Nothing is sent off the device for AI.

## On-device AI

AI features (chore suggestions, the weekly parent summary, the "kindness check" before a child sends a message, and reading a receipt to prefill an adjustment) run entirely on your device, using Gemini Nano where the phone supports it and simple built-in rules where it does not. The kindness check only ever nudges the child; it never blocks a message and never reports it to anyone, including the parent. **TODO (M6/M8):** describe the on-device model download (Wi-Fi, with consent).

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
- **Export (M3):** a parent can download the household's data from Settings → Data → Export household: one JSON file with the household, its members' roles, its devices and parent keys, every kid profile, chore, submission, reward, schedule, goal and policy, and every ledger entry, plus a `ledger.csv` made on the phone. The parent chooses where to save it. Households above 4 MB cannot export yet. Chat content (from M4) is exported from a device that can decrypt it.
- **Delete household (M3):** a parent can delete the household from Settings → Data → Delete household, after typing the household id and signing in again if the last sign-in is older than 5 minutes. This removes all of the household's cloud data, the sign-in accounts of every parent and kid device linked to it, its link codes and registration records, and wipes local data on every household device the next time it syncs. We keep one record that a deletion happened: a one-way hash of the household id, the time, and how many members, devices, events and documents were deleted, with no household content, for 12 months. Sprout keeps no backups of its own on the current plan; Google's own retention of deleted data follows its data processing terms. **TODO (M8):** confirm the processor retention figures for the published policy.
- **Chat retention (M7):** parents can choose to auto-delete chat messages after a set number of days.
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
