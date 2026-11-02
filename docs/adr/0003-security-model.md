# ADR-0003: Security model: identity, signing, and scoped E2EE

- **Status:** Accepted
- **Date:** 2026-11-02
- **Milestone:** M0 (implemented across M1–M4, audited in M7)

## Context

Sprout handles data about children (first names, birth month and year, what they write in chat, photos of their room) and a ledger of real pocket money. Several devices in one household write to a shared backend, and one class of user (kids) has both the motive and, on a rooted device, the means to cheat.

The target bar is controls that would stand up in a SOC 2 Type I style review across the Security, Availability, Confidentiality, and Privacy criteria. SOC 2 asks for demonstrable controls: access control, encryption, logging, change management, incident handling. It does not require hiding all data from the server operator. Firestore already encrypts at rest on a SOC 2 certified platform.

Threats driving this ADR are catalogued in the [threat model](../THREAT_MODEL.md).

## Decision

### Identity

- **Parents** sign in with Firebase Auth through Credential Manager (Google sign-in and passkeys). No passwords are handled by the app.
- **Kid devices** get their own Firebase Auth identity: anonymous auth, upgraded at link time with custom claims `{householdId, role: KID, kidId}` set by a Cloud Function. A kid device never holds parent credentials or tokens.
- Roles come only from custom claims, which only server code can set. The client never asserts its own role.

### Device linking

1. Parent taps "Link device". A Cloud Function generates an 8-character code from a CSPRNG, stores only its hash with a 10-minute TTL, and returns it to the parent screen.
2. The kid device signs in anonymously, generates its device key pair, and submits the code with its public key.
3. The function checks the hash, TTL, and single-use flag, then registers the public key in the household device list, sets the custom claims, and burns the code.
4. Attempts are rate-limited per uid and per IP. Codes are never put in URLs and never logged.

A **second parent device** cannot use a code. It must be approved on an existing parent device by scanning a QR code that carries the new device's public key fingerprint.

### Device keys and signed events

- Each device generates a P-256 key pair in Android Keystore: StrongBox when available, hardware-backed TEE otherwise. Private keys are non-exportable. Parent keys set `setUserAuthenticationRequired`, so signing a parent-only event needs a recent biometric or device credential.
- The public key is registered at link time under the household's device list.
- Every ledger event and chat message is signed (ECDSA P-256, SHA-256) over its canonical encoding, and ledger events form a per-author hash chain (ADR-0002).
- Receivers verify signature, registered device, author role, and chain before applying. Failures are quarantined and surfaced in the parent audit view, never silently dropped.

### Server-side gate: Firestore rules and App Check

Firestore and Storage rules are the authority on what may be written. They enforce:

- household membership from custom claims, so there is no cross-household read or write;
- `authorUid == request.auth.uid` on every write;
- `authorDeviceId` present in the registered device list for that uid;
- role-gated document types: a kid cannot write approvals, rewards, allowance, or kid profiles, and cannot author parent-only ledger events;
- ledger events are create-only: no update and no delete;
- schema validation of required fields and types.

Rules have emulator tests in CI. Signature verification happens on clients because rules cannot verify ECDSA. Rules stop unauthenticated, wrong-role, and wrong-device writes; signatures stop forgeries that pass rules (for example, written with stolen backend credentials).

**App Check** with Play Integrity is enforced on Firestore, Storage, and Cloud Functions, so repackaged apps and scripted clients are rejected before rules run.

### End-to-end encryption scope: chat and photos only

- At link time, parent and kid device keys perform X25519 agreement. HKDF derives a per-thread AES-256-GCM key. Messages and chore photos are encrypted on device; Firestore and Storage hold only ciphertext plus routing metadata (thread, sender device, timestamp, size).
- Removing a device rotates thread keys; a removed device cannot read new messages.
- The on-device kindness check reads plaintext before encryption on the kid's device. Nothing is sent anywhere for AI.
- **The ledger is signed but not encrypted.** Integrity of money events is what matters, and signatures plus the hash chain provide it. Keeping ledger data readable to the household's backend means:
  - Cloud Functions can run the interest cron guard, export, and delete without holding keys;
  - a parent who loses every device can still recover balances after re-authenticating;
  - there is no key-escrow or recovery-phrase flow for the data most likely to need recovery.

Rationale: SOC 2 is about demonstrable controls, not about hiding all data from the server. E2EE on the most sensitive data (what a child says and photographs), plus cryptographic integrity for money, gives the strongest story without the key-recovery burden of encrypting everything.

### Data at rest on device

- Room runs on **SQLCipher**. The passphrase is generated once and wrapped by a Keystore AES key. DataStore secrets are encrypted with a Tink keyset held in Keystore.
- **Parent PIN** is hashed with Argon2id and a per-install salt (PBKDF2-HMAC-SHA256 with a high iteration count if Argon2 is not available). Failed attempts trigger exponential lockout. Biometric unlock uses `BiometricPrompt` with a `CryptoObject`, so it releases a key rather than returning a boolean. The parent area auto-locks when the app goes to the background.
- `allowBackup=false`, and backup rules exclude the database and keys.
- `FLAG_SECURE` on parent screens and chat blocks screenshots, screen recording, and task-switcher thumbnails.

### Transport and hardening

TLS only through Firebase SDKs; a Network Security Config forbids cleartext. R8 full mode, non-debuggable release builds, and no PII in logs (Timber release tree drops everything below WARN; a custom lint rule forbids logging model types). Crashlytics is opt-in with PII scrubbing. No ads and no third-party analytics.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Encrypt everything end to end** (ledger, profiles, chores) | Strongest confidentiality, but server-side functions (export, delete verification, interest guard) would need keys or would stop working. Losing all devices would mean losing the ledger unless we build key escrow or recovery phrases, which are a known source of user lockout and support burden, especially for families. It also makes server-side schema validation impossible. The marginal confidentiality gain over Firestore's own at-rest encryption is small for non-sensitive data such as chore titles and amounts. |
| **Rely on Firestore rules only** (no device keys or signatures) | Rules cover most attacks from honest-but-curious clients, but anyone holding project admin credentials (a leaked service account, a malicious insider) can write any document and bypass rules entirely. Without signatures, such forged ledger events are indistinguishable from real ones. Rules also cannot express "this event was produced by the device the parent approved". Signing makes the ledger tamper-evident even against the backend. |

## Consequences

**Easier**

- A clear, testable story per threat: rules tests, signature tests, ciphertext-only transport tests, and lockout tests map directly to controls in `docs/CONTROLS.md` (M3).
- Kid devices can be revoked individually without touching parent credentials.
- No key-recovery UX for the ledger.

**Harder**

- Key rotation on device removal must be correct and tested before chat ships (M4).
- Chat history is lost if every device in the household is lost. Accepted risk; see the threat model.
- Biometric-bound parent keys make some background operations (scheduled allowance) need a design that does not require signing at fire time. The working design: due allowance events are signed on the next parent unlock, never with a key that bypasses user authentication. Finalised in M2.
- App Check adds a dependency on Play Integrity; debug builds use the debug provider, which must never ship in release (checked in CI).
- Metadata (who talks to whom, when, message sizes) remains visible to the backend.
