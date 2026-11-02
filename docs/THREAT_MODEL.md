# Sprout threat model

- **Status:** First draft (M0). Reviewed at the end of each milestone and before release (M8).
- **Method:** STRIDE per asset and trust boundary.
- **Related:** [ADR-0002 Ledger as events](adr/0002-ledger-as-events.md), [ADR-0003 Security model](adr/0003-security-model.md). Controls are mapped to SOC 2 criteria in `docs/CONTROLS.md` (M3).

## System overview

Each household has one or more parent devices and one or more kid devices. Every device keeps a local SQLCipher database and syncs through Firebase (Auth, Firestore, Storage, Cloud Functions), with App Check in front. Ledger events are signed and hash-chained per author. Chat messages and chore photos are end-to-end encrypted between devices. All AI runs on device.

## Assets

| Asset | Description | Primary property |
|---|---|---|
| Kid identity data | First name, avatar, birth month and year, UI tier | Confidentiality, privacy |
| Chat content | Messages and voice notes between parent and kid | Confidentiality |
| Photos | Chore submission photos, often taken inside the home | Confidentiality |
| Ledger integrity | Event stream, balances, audit log | Integrity, non-repudiation |
| Parent credentials | Firebase session, passkeys and Google account link, parent PIN | Authentication |
| Device keys | Keystore P-256 signing keys, X25519 agreement keys, thread keys, SQLCipher passphrase | Confidentiality, integrity |

## Actors

| Actor | Capability | Motive |
|---|---|---|
| Curious kid | Uses their own device and may borrow a parent's unlocked phone; no tooling | Peek at parent screens, award themselves credits |
| Malicious kid with a rooted device | Root, Frida, can edit local files, intercept app calls, change the clock | Inflate balance, bypass caps, approve own chores |
| Lost or stolen device | Physical access, possibly unlocked, possibly with a known screen lock | Read chat and photos, impersonate the household |
| Compromised Firebase project credentials | Admin SDK or console access; bypasses security rules | Read or rewrite household data |
| Network attacker | On-path on public Wi-Fi; can block, delay, and replay traffic | Intercept or modify data in transit |
| Repackaged app | Modified APK or script using the real Firebase config | Scrape data, write forged documents, abuse functions |
| Malicious insider at the platform | Access to storage or infrastructure at the cloud provider | Read stored data |

## Trust boundaries

| Boundary | What crosses it | Controls at the boundary |
|---|---|---|
| Device ↔ Firebase | Auth tokens, ledger events, documents, ciphertext, function calls | TLS, App Check, Firebase Auth, Firestore and Storage rules, function-side validation |
| Parent device ↔ kid device (via Firebase) | Signed events, E2EE messages and photos, key material during linking | Device signatures, registered device list, X25519 + HKDF + AES-GCM, link codes |
| App ↔ OS keystore | Sign, decrypt, and unwrap operations; biometric prompts | Non-exportable Keystore keys (StrongBox where available), user-auth-bound parent keys, `CryptoObject` biometric |

Inside the app process, everything is trusted. A compromised OS on an unlocked device is out of scope (see below).

## Threats

STRIDE: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege.

| # | Threat | STRIDE | Asset | Mitigation | Status |
|---|---|---|---|---|---|
| T1 | Kid device submits a chore approval or reward as if from a parent | S, E | Ledger integrity | Custom claims set only by Cloud Functions; rules gate approvals and parent-only event types on `role == PARENT`; receivers reject parent-only events signed by a non-parent device key | Planned M3 (rules), M1 (client checks) |
| T2 | Replayed ledger event (re-sent by a rooted client or a network attacker) | T | Ledger integrity | Idempotency by `eventId` (duplicate is a no-op); per-author monotonic lamport; hash chain rejects out-of-sequence events; TLS | Planned M1 |
| T3 | Forged event carrying another author's `authorUid` or `authorDeviceId` | S, R | Ledger integrity | Rules require `authorUid == request.auth.uid` and a registered `authorDeviceId` for that uid; signature verified against that device's registered public key; failures quarantined and shown in the audit view | Planned M1 (signing), M3 (rules) |
| T4 | Kid edits the local database on a rooted device to raise a balance | T, E | Ledger integrity | SQLCipher with a Keystore-wrapped passphrase; balances are projections, not stored truth; other devices recompute from signed events, so local edits never propagate; kid-authored credits limited to capped `GamePlayed` | Planned M1 |
| T5 | Platform operator or holder of project credentials reads chat in the Firestore console | I | Chat content, photos | E2EE: only ciphertext and routing metadata stored; keys never leave devices; thread keys derived per device pair | Planned M4 |
| T6 | Linking code brute force | S, E | Kid identity data, ledger integrity | 8-char CSPRNG code, 10-minute TTL, single use, hashed at rest, rate-limited per uid and IP, App Check on the function; never in URLs or logs | Planned M3 |
| T7 | Stolen parent phone | S, I, E | Parent credentials, all assets | Parent area behind PIN or biometric with exponential lockout and auto-lock on background; parent signing key requires user authentication; `FLAG_SECURE`; remote device removal from another parent device rotates chat keys and revokes the device | Planned M2 (lock), M3 (revoke), M4 (rotation) |
| T8 | Kid device (or an attacker's device) added to the household without approval | S, E | All household data | Kid linking requires a code generated by a signed-in parent; a second parent device requires QR approval on an existing parent device with key fingerprint check; all linked devices listed in settings and audit log | Planned M3 |
| T9 | PII in crash logs or analytics | I | Kid identity data, chat content | No analytics SDKs; Crashlytics opt-in with PII scrubbing; Timber release tree drops below WARN; lint forbids `android.util.Log` and logging model types; no chat plaintext or names in logs | Partial M0 (Detekt bans `android.util.Log`); Timber release tree M1; Crashlytics M3 |
| T10 | Compromised dependency or build plugin | T, E | All | Gradle dependency verification with SHA-256 checksums only (no PGP signature verification; trust on first use), refreshed by a workflow on Renovate branches; Renovate with reviewed, grouped PRs; OWASP Dependency-Check in a weekly and manual workflow; Actions pinned by SHA; branch protection; secret scanning | Done M0 (checksums, Renovate, Dependency-Check workflow, SHA-pinned Actions); secret scanning and branch protection are repo settings, enable in repo settings |
| T11 | Repackaged app or script calling Firebase with the real config | S, E, D | All household data | App Check with Play Integrity enforced on Firestore, Storage, and Functions; rules still apply to anything that passes; function rate limits | Planned M3 |
| T12 | Kid changes the device clock to game daily caps or allowance timing | T | Ledger integrity | Ordering by lamport, not `createdAt`; daily cap counted against server timestamp on sync; allowance driven by parent devices | Planned M1, M5 |
| T13 | Network attacker intercepts or modifies traffic | I, T | All in transit | TLS via Firebase SDKs; Network Security Config forbids cleartext; signatures and AEAD detect modification independently of TLS | Done M0 (Network Security Config); M3 (Firebase TLS), M1/M4 (signatures, AEAD) |
| T14 | Backup or device transfer extracts the database or keys | I | Device keys, local data | `allowBackup=false`; data extraction rules exclude every domain from cloud backup and device transfer; Keystore keys are non-exportable | Done M0 |
| T15 | Screenshot, screen recording, or task switcher leaks parent or chat screens | I | Chat content, kid identity data | `FLAG_SECURE` on parent and chat screens; lint check for missing flag on sensitive screens | Planned M2, audited M7 |
| T16 | Parent denies having made a manual adjustment | R | Ledger integrity | Every event signed by a user-auth-bound parent key; append-only audit log with author, device, and time | Planned M1 |
| T17 | Removed device keeps reading new chat messages | I | Chat content | Thread key rotation on device removal; claims revoked; rules deny the removed device | Planned M4 |
| T18 | Flooding the outbox or functions to exhaust quotas | D | Availability | Per-uid rate limits on functions; App Check; Firestore write size limits in rules; budget alerts | Planned M3 |

## Accepted risks

| Risk | Rationale |
|---|---|
| **Time bank runs on the honour system.** A kid can start the time bank and keep using the device after it runs out, or never start it. | Enforcement needs Accessibility, UsageStats, overlay, or device-admin permissions, which bring Play policy review and a much larger attack surface. v1 records time spent; it does not enforce it. The ledger still records every purchase and start/stop accurately. |
| **E2EE chat history is lost if every household device is lost.** | Keys exist only on devices. Server-side key escrow would undermine the E2EE claim, and recovery phrases are a lockout risk for families. Chat is not the system of record for anything; the ledger is plaintext-signed and recoverable after re-authentication. Parents are told this at chat setup. |
| Backend can see chat metadata (who, when, size). | Needed for routing and notifications. Content stays encrypted. |

## Out of scope for v1

- **Device enforcement** of screen time (blocking apps, locking the device). If it is ever built, it goes in an isolated module behind a feature flag with its own policy review and threat model.
- **OS-level compromise of an unlocked parent device** (malware with root or Accessibility on the parent's phone while unlocked). Such an attacker can drive the UI and use user-auth-bound keys as the parent would. Mitigations stop at Keystore non-exportability and auto-lock.
