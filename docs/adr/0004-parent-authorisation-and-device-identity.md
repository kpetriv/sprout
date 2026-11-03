# ADR-0004: Parent authorisation of ledger events and device identity

- **Status:** Accepted
- **Date:** 2026-11-02
- **Milestone:** Decided at the M1 review and security audit; implemented in M2 (parent signature, PIN pepper) and M3 (verification, registry, attestation, lamport bounds)
- **Amends:** [ADR-0002](0002-ledger-as-events.md) (signed payload, duplicate handling) and [ADR-0003](0003-security-model.md) (parent key policy, PIN hashing, device key provenance). The rest of both stays in force.

## Context

M1 ships the ledger core, crypto primitives, the SQLCipher database, and the wallet, all local-only. The M1 review and security audit found that two of the threat model's ledger controls rest on weaker ground than ADR-0003 describes:

- **Parent-only events trust a self-declared role.** `LedgerRules` rejects `ChoreRewarded`, `AllowancePaid`, `ManualAdjustment`, `InterestAccrued`, and `OverdraftCompensated` unless `envelope.authorRole == PARENT`. That role is a field the author writes. Nothing cryptographic ties it to a parent.
- **Every event is signed by the device key, which has no user-authentication requirement.** The device key (`sprout.device.signing.v1`) must sign without a prompt so background sync and kid-authored events work. A signature from it proves which device wrote an event, not that a parent was present. Anyone holding an unlocked parent phone, or a kid who knows the parent's screen lock, can produce parent-only events that verify (threat model T7), and a parent can later deny a manual adjustment (T16).
- **The device key has no provenance.** Nothing lets the server or another device tell a hardware-backed key from one generated in software on a rooted device or an emulator.

While the app is local-only these gaps have no remote reach: there is no second device to deceive and no server to accept events. They must close before M3 sync, and the cheapest time to change signed-payload formats is now, before any real events exist.

The audit also raised smaller items that need a recorded decision: PIN hash portability, unbounded lamport jumps from remote events, signature domain separation, and what an `eventId` collision with different content means.

## Decision

### 1. Parent signature on parent-only events (M2 write, M3 verify)

From envelope **schema version 2** (M2), every parent-only event (`ChoreRewarded`, `AllowancePaid`, `ManualAdjustment`, `InterestAccrued`, `OverdraftCompensated`) carries a second signature, `parentSignature`, in addition to the device `signature`.

- `parentSignature` is made with the **parent authentication key**, alias `sprout.parent.auth.v1`: EC P-256 in Android Keystore (StrongBox first, TEE otherwise), non-exportable, invalidated on biometric enrolment change.
- Key policy: `setUserAuthenticationRequired(true)` with an authentication **timeout of 0**, so every signature needs its own `BiometricPrompt` bound to that operation through a per-use `CryptoObject`. Allowed authenticators: **`AUTH_BIOMETRIC_STRONG` only**. Device credential (screen-lock PIN, pattern, or password) is **not** accepted, because a kid who has watched the parent unlock the phone knows it.
- The parent signature covers the same domain-separated payload as the device signature (section 5). Both signatures are excluded from the canonical form and the hash.
- **Receivers (M3)** verify `parentSignature` against the household's registered parent keys (the parent-key registry built with the device registry in M3). A parent-only event that lacks `parentSignature`, or whose signature does not verify against a registered parent key, is **quarantined**: excluded from projections and shown in the parent audit view, never silently dropped.
- The local `authorRole` rule in `LedgerRules` stays as a cheap first filter. It is not the control.

Scheduled events that fire without the parent present (allowance) follow ADR-0003: they are signed on the next parent unlock, never with a key that bypasses user authentication.

This supersedes ADR-0003's parent key policy ("biometric or device credential within 30 s") and the 30-second validity window documented for M1 in `core/crypto`.

### 2. Device identity and attestation (M3)

- The M1 device signing key is **provisional**. It identifies the device to itself and lets the local hash chain and signature tests run end to end. It is never registered anywhere.
- At M3 device linking, the app generates the **household identity key** with `setAttestationChallenge`, using a challenge issued by the `link-device` Cloud Function. The function verifies the attestation certificate chain and records whether the key is hardware-backed (TEE or StrongBox) before registering the public key in the household device list.
- **Local-household events are purged, never uploaded.** Events authored before linking were signed by the provisional key in a household the server has never seen. The M3 migration deletes local-household kids, events, and outbox rows. Nothing signed by a provisional key ever leaves the device.

### 3. PIN hashing pepper and lockout (M2)

- The Argon2id input is the PIN **peppered with a non-exportable Keystore HMAC key**: the hasher computes `HMAC(keystoreKey, PIN)` inside Keystore and feeds the result to Argon2id with the per-install salt. A hash extracted from a rooted device cannot be brute-forced off-device, because the 10^4–10^6 PIN space can only be searched through this device's Keystore.
- The PBKDF2 fallback gets the same pepper.
- **Lockout keeps served time across reboots.** The policy persists the time already served (measured with elapsed realtime while the device stays up) so rebooting does not reset or shorten a lockout, and moving the wall clock does not either.
- **After the lockout cap, the PIN path closes** and the parent must re-authenticate with a strong biometric (section 1's prompt) instead of waiting out another period.

### 4. Lamport bounds (M3)

- A remote event whose `lamport` exceeds the local maximum by more than a policy bound is **quarantined**. The bound is a `LedgerPolicy` value, set in M3.
- `LamportClock.observe` ignores such jumps, so one forged or corrupt event cannot push every device's clock towards `Long.MAX_VALUE` and exhaust the ordering space.

### 5. Signed payload domain separation (M1)

Signatures cover

```
UTF-8("sprout/ledger-event/v1") || 0x00 || canonicalBytes
```

not the bare canonical bytes. The prefix stops a signature made over a ledger event being replayed as a valid signature over another kind of payload (chat message, link request) or the reverse, and the version in the label lets the scheme change without ambiguity. Because no real events exist yet, this applies from the first event rather than through a schema bump. The hash chain still hashes the canonical bytes without the prefix (ADR-0002).

### 6. Duplicate `eventId`s (M1)

Idempotency compares **content**, not just the id:

- same `eventId`, same hash: a true duplicate (outbox retry, repeated pull). No-op.
- same `eventId`, different hash: a **conflicting duplicate**. **Neither copy is applied.** Both are excluded from projections and surfaced as a `ConflictingDuplicate` anomaly in the parent audit view.

Applying "the first one seen" would let whichever copy arrives first win, which differs between devices and breaks convergence. Applying neither is deterministic everywhere.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **App lock only** (PIN or biometric gate in front of the parent area, no second signature) | Stops a casual kid on an unlocked phone, but leaves no cryptographic evidence on the event. Receivers cannot tell a parent's adjustment from one written by anything that can drive the device key, and a parent can deny an adjustment (T16). The lock still exists (M2) as the UX gate; the signature is the evidence. |
| **Make the device key user-auth-bound on parent devices** | Background sync and scheduled work could no longer sign, and every event (including routine ones) would prompt. Two keys with two policies is simpler and keeps prompts to money events. |
| **Accept device credential as well as biometrics** | Kids routinely learn a parent's screen-lock PIN or pattern. Accepting it reduces T7 to "knows the screen lock". |
| **30-second validity window instead of per-use `CryptoObject`** | One unlock would authorise any number of signatures for 30 s, including ones the parent did not see. Per-use binding ties each prompt to exactly one event. |
| **Upload M1 local-household events at link time** | They are signed by an unattested provisional key in a household the server never authorised. Re-signing them would launder unverified history. M1 data is demo-scale and disposable. |
| **Apply the first-seen copy of a conflicting duplicate** | Order of arrival differs per device, so devices diverge. |
| **Clamp lamport jumps instead of quarantining** | Rewrites the author's signed lamport, which breaks the signature and the chain. |

## Consequences

**Easier**

- T1, T7, and T16 rest on a key that only a present parent with an enrolled strong biometric can use, and every parent-only event carries evidence of it.
- The server can refuse unattested or software-backed keys at link time.
- Domain separation and content-based duplicate handling are in from the first event, so no migration is needed for them.

**Harder**

- Envelope schema version 2 changes the stored and wire form. Decoding must accept both versions; canonical form already tolerates unknown fields (ADR-0002 amendments).
- One biometric prompt per parent-only event. Batch operations (approving several chores) need a design that shows the parent what each prompt signs; settled in M2.
- A parent device without an enrolled strong biometric cannot produce `parentSignature`. How such a parent authors money events is an open M2 question; accepting the device credential is ruled out.
- The M3 migration is destructive for local-household data. It must be explicit in the UI and tested.
- A parent-key registry, a revocation path, and key rotation on biometric re-enrolment (which invalidates the key) are needed in M3.
- Until M2/M3 land, T1 and T16 are only partially mitigated; the threat model records that.

## Amendments (M2 review, 2026-11-03)

The M2 review and security audit refined how the parent key comes into existence, how it is renewed, and what the lockout does when no usable biometric exists. None of this reverses the decision above; it tightens it. Money-flow atomicity and scheduling are recorded separately in [ADR-0005](0005-scheduled-payments-and-money-flow-atomicity.md).

### Parent key enrolment is PIN-gated; no lazy creation

The parent authentication key is **never created lazily** (for example, the first time a money action needs a signature). It is created and registered only in a PIN-gated enrolment flow:

- **at PIN setup**, when a strong biometric is available (the default; D-M2-8), or
- **later from Settings**, after the parent enters a fresh PIN.

Lazy creation let anyone who could pass a strong-biometric prompt mint the household's parent key: a kid with their own fingerprint enrolled on the parent's phone (which needs only the screen lock) could create the key and then sign parent-only events without ever knowing the parent PIN.

Biometric unlock of the parent session and per-event parent authorisation are offered **only when** a parent key is registered in the household policy **and** the local Keystore key byte-equals the registered public key. The biometric unlock challenge is signed with that key and verified against the registered key, not against whatever key happens to sit under the alias.

### Key renewal after a biometric enrolment change is PIN-gated

`setInvalidatedByBiometricEnrollment(true)` makes the key permanently unusable when the set of enrolled biometrics changes. Renewal is a PIN-gated flow:

1. fresh PIN entry, under the normal lockout policy;
2. delete the invalidated Keystore key;
3. create a new key under the same policy;
4. a proof prompt: one `BiometricPrompt` signature with the new key, verified locally;
5. replace the registered key in the household policy, **keeping the original from-lamport** (the lamport from which events must be parent-signed), so renewal never opens a window of unsigned events.

### Lockout cap without a usable biometric

Section 3 closes the PIN path after the lockout cap and requires a strong biometric instead. That leaves a parent with no way in when the parent key is invalidated or the device has no strong biometric. **While the parent key is invalidated or no strong biometric exists, the capped state allows one PIN attempt per hour** instead of requiring a biometric, so the PIN path never closes permanently (D-M2-7).

Accepted deviation: an attacker with the device gets about 24 guesses a day against a 10^6 PIN space (6 digits), so an expected search takes decades. The pepper (section 3) keeps the search on this device.

### Unregistered signatures count as unsigned

An event whose `parentSignature` was made by a key that is not the registered parent key counts as **unsigned**: it fails `ParentSignatureMissing` locally and, in M3, is quarantined on receipt. A valid ECDSA signature by some other key proves nothing about the parent.

### No unpeppered PIN hashes

Unpeppered v1 PIN hashes are **not accepted**. No M1 build shipped, so there are no v1 hashes to migrate; accepting them would only leave a downgrade path.

### Scheduled payments are signed on demand

Scheduled parent-only events (allowance, interest) are signed from a **manual "Sign now"** action on the parent dashboard, not automatically at the next parent unlock (D-M2-6). This replaces section 1's "signed on the next parent unlock". Every prompt names the kid, the amount, and the period it pays for, so the parent sees what each per-use signature covers. Validation and queue handling are in ADR-0005.

### M3 requirements recorded at the M2 review

These are requirements for M3, not M2 work:

- **Drop the from-lamport exemption for pulled events.** Local-household events are purged at linking (section 2), so a pulled parent-only event without a valid registered parent signature is quarantined regardless of its lamport.
- **Parent-wins conflict resolution needs proof.** Resolving a conflicting duplicate in the parent's favour (D-M1-1) must use the role from the device registry and a verified parent signature. Without both, neither copy is applied (section 6's default).
- **Reward catalogue definitions become parent-signed documents**, so a receiver can verify the cost and minutes a `RewardPurchased` and its `TimeBankCredited` claim against what the parent defined.
- **Parent key generated with a server challenge**, and the attestation checked at registration: hardware-backed, `AUTH_BIOMETRIC_STRONG` only, per-use (timeout 0), invalidated on biometric enrolment.
- **Instrumented runs on API 28 and 29** for the per-use key policy, where `setUserAuthenticationParameters` is unavailable and the deprecated validity-duration API is used instead.
