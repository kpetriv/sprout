# Sprout privacy policy

> **DRAFT.** First draft written at the end of M1 as the skeleton for the Play Store listing and data-safety form. It describes the app as planned for v1, with the milestone each part arrives in. Not legal advice and not yet reviewed. M8 finalises it: legal review, contact details, effective date, and the published page. Items marked **TODO (M8)** are open.

- **Last updated:** 2026-11-02 (draft)
- **Applies to:** the Sprout Android app (`dev.kpetriv.sprout`)
- **Related:** [threat model](THREAT_MODEL.md), [ADR-0003 Security model](adr/0003-security-model.md)

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
| Sign-in identity | Signing in with Google or a passkey through Firebase Authentication | The account identifier and the email address your Google account provides. Sprout never sees or stores a password. |

### Technical data (from M3)

| Data | Why | Notes |
|---|---|---|
| Device public keys and device ids | Proving which household device wrote what, and encrypting chat to the right devices | Private keys never leave the device |
| App integrity tokens (Firebase App Check, Play Integrity) | Rejecting modified apps and scripts | Not used for tracking |
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
- **In the cloud (from M3).** Household data syncs between your family's devices through Google Firebase (Authentication, Firestore, Cloud Storage, Cloud Functions). Firebase encrypts data at rest and in transit. We will use an EU region if one is available for the services Sprout needs. **TODO (M3):** confirm the region and list it here.
- **Until M3, nothing leaves the device.** The app is local-only.

## Who can see your data

| Who | What they can see |
|---|---|
| Parents in the household | Everything in the household, including the full ledger and audit log |
| A child, in kid mode | Their own chores, balance, ledger entries, shop, and chat with the parent; never parent settings or other children's data |
| Sprout's backend and its operator | Household data and the ledger, in order to sync it, run scheduled jobs (interest), and export or delete it. **Not** the content of chat messages or photos. |
| Google, as our cloud provider | Stores the data on our behalf under its data processing terms |
| Anyone else | Nobody. We do not sell or share data, and there are no advertising or analytics partners. |

We will disclose data only if the law requires it, and then only what is required. **TODO (M8):** legal review of this statement.

## How long we keep it, and how to delete it

- **Your household's data** is kept for as long as the household exists.
- **Export (M3):** a parent can download the household's data (profiles, chores, rewards, ledger) as a JSON and CSV bundle. Chat content is exported from a device that can decrypt it.
- **Delete household (M3):** a parent can delete the household. This removes its cloud data (database records, stored files, and sign-in accounts for linked devices) and wipes local data on every household device the next time it syncs. We keep a minimal record that a deletion happened, with no household content. **TODO (M8):** state how long backups take to expire.
- **Chat retention (M7):** parents can choose to auto-delete chat messages after a set number of days.
- **Short-lived data:** device-linking codes expire after 10 minutes and are stored only as a hash. **TODO (M3):** retention period for audit records.
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
- All network traffic over TLS; modified apps rejected by App Check (from M3).

## Changes to this policy

We will update this page when the app's handling of data changes and show the date at the top. Material changes will be announced in the app before they take effect.

## Contact

**TODO (M8):** a privacy contact email address, the developer's name and postal address as required by Play, and a link to `security.txt` for reporting vulnerabilities.
