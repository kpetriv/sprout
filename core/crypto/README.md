# core/crypto

Byte-oriented crypto primitives for Sprout. Every primitive sits behind a small interface: JVM unit tests use the pure-JCE implementations, and `di/CryptoModule` binds the Android Keystore ones in the app. In-memory fakes live in `src/testFixtures`, never in `main`; other modules use them through `testImplementation(testFixtures(projects.core.crypto))` (and `androidTestImplementation(...)` for instrumented tests). Nothing here knows about ledger events or chat messages; callers pass canonical bytes in and get bytes out.

The module never logs, so no key material, PIN, or plaintext can reach a log. `toString` on every secret-holding type prints `<redacted>`.

## Primitives

| Interface | Production (Hilt) | JVM / tests | Notes |
|---|---|---|---|
| `DeviceKeyStore` | `AndroidKeystoreDeviceKeyStore` | `InMemoryDeviceKeyStore` (test fixture) | EC P-256, StrongBox first and TEE if StrongBox refuses, SHA-256 only, **no user auth** (background sync must sign). `DeviceSigner` is the entry point for signing. `keyProperties()` returns self-reported `KeyInfo` (`DeviceKeyProperties`), **not attestation**. The M1 key is provisional: M3 generates the household identity key at link time with `setAttestationChallenge`. |
| `ParentAuthKeyStore` | `AndroidKeystoreParentAuthKeyStore` | none | EC P-256, user auth required: strong biometric or device credential within 30 s; invalidated when biometric enrolment changes. `initSignature()` returns a `Signature` for a `BiometricPrompt.CryptoObject`. |
| `Signer` / `Verifier` | `JceEcdsaSigner` / `JceEcdsaVerifier` | same; `generateSoftwareSigningKey()` (test fixture) | ECDSA P-256 / SHA-256, DER signatures, X.509 SPKI public keys (`PublicKeyBytes`). The verifier rejects keys on other curves and returns false for any malformed input; it never throws on untrusted bytes (every `GeneralSecurityException` and `RuntimeException` means "not valid"). |
| `Aead` | `AesGcmAead` | same | AES-256-GCM, 12-byte `SecureRandom` nonce per call, 128-bit tag. Output `0x01‖nonce‖ciphertext‖tag`; the version byte is bound into the tag (GCM associated data is `version‖associatedData`), and unknown versions are rejected. Associated data must be non-empty (`IllegalArgumentException` otherwise). |
| `X25519KeyAgreement` | `JceX25519KeyAgreement` | same | X25519 (RFC 7748) on raw 32-byte keys; rejects all-zero shared secrets. Named for the curve so it does not clash with `javax.crypto.KeyAgreement`. |
| `Hkdf` | object | same | HMAC-SHA256 extract and expand (RFC 5869). |
| `HouseholdKeyDerivation` | `@Inject` | same | `deriveThreadKey(sharedSecret, salt, info)`: HKDF with a versioned domain label, giving a 32-byte AES key (M4). |
| `PinHasher` | `Argon2idPinHasher` (m=64 MiB, t=3, p=1) | `Pbkdf2PinHasher` (600,000 iterations) | 16-byte salt, 32-byte hash, encoded `v1$<alg>$<params>$<saltB64>$<hashB64>`, constant-time compare. Parsing accepts only 16–64-byte salts and hashes. The Argon2 hasher still verifies `pbkdf2-sha256` hashes and flags them through `needsRehash`. |
| `PinLockoutPolicy` | `@Inject` (needs `Clock`) | same with `FakeClock` | Lockouts of 0, 0, 0, 30 s, 60 s, 120 s and so on, capped at 1 h. A wall-clock rollback cannot lengthen a lockout. |
| `SecretStore` | `WrappedSecretStore` + `AndroidKeystoreKeyWrapper` + `FilesDirBlobStorage` | `WrappedSecretStore` + `InMemoryKeyWrapper` + `InMemoryBlobStorage` (test fixtures) | Random 32-byte secrets (for example the SQLCipher passphrase), wrapped with AES-256-GCM. The secret's name is bound in as associated data. If a stored secret cannot be unwrapped, this throws; it never regenerates the secret. `getOrCreate(name, createIfMissing = false)` throws `SecretUnavailableException` instead of creating a missing secret; the database layer passes `createIfMissing = !databaseFileExists` so a lost blob cannot orphan an existing database. |

Secrets (`SymmetricKey`, `SharedSecret`, `X25519PrivateKey`) are `AutoCloseable` and zero their bytes on `close()`. Copies made inside JCE are outside this guarantee.

## Key aliases (`KeyAliases`)

| Alias | Type | User auth | Purpose |
|---|---|---|---|
| `sprout.device.signing.v1` | EC P-256 sign/verify | No | Signs every ledger event and chat message this device writes (provisional until M3's attested household identity key) |
| `sprout.parent.auth.v1` | EC P-256 sign/verify | Yes, 30 s, biometric strong or device credential | Parent-only operations, through feature/lock (M2) |
| `sprout.secret.wrap.v1` | AES-256-GCM | No | Wraps local secrets such as the SQLCipher passphrase |

All three are generated through `AndroidKeystore.generate`: StrongBox first when the device has it; if StrongBox throws a `ProviderException` (including `StrongBoxUnavailableException`) or a `KeyStoreException`, any partial entry under the alias is deleted and generation repeats in the TEE (`withStrongBoxFallback`, unit tested with lambdas).

Wrapped blobs live in `noBackupFilesDir/sprout_secrets/<name>.bin` and are written atomically.

## Threat model coverage ([docs/THREAT_MODEL.md](../../docs/THREAT_MODEL.md))

| Threat | What this module provides |
|---|---|
| T1 Kid forges parent actions | `Verifier` plus the registered public keys let receivers reject parent-only events signed by a kid device key |
| T3 Forged author or device | Device signing key (non-exportable, hardware-backed) and `Verifier` |
| T4 Local DB edits on a rooted device | `SecretStore` provides the Keystore-wrapped SQLCipher passphrase |
| T5 Operator reads chat | `X25519KeyAgreement`, `HouseholdKeyDerivation`, and `Aead` are the building blocks for E2EE (wired up in M4) |
| T7 Stolen parent phone | **Partially addressed.** `PinHasher` (Argon2id), `PinLockoutPolicy`, and the user-auth-bound `ParentAuthKeyStore`. Deferred to M2 (ADR-0004, marked `TODO(M2, ADR-0004)` in code): a Keystore HMAC pepper on the Argon2 hash so a dumped hash cannot be brute-forced off-device, and a per-use, strong-biometric-only parent key |
| T9 PII in logs | No logging in this module; redacted `toString` on secrets |
| T10 Supply chain | argon2kt is pinned in the version catalog and checksummed in `gradle/verification-metadata.xml`; its unused `androidx.appcompat` runtime dependency is excluded |
| T13 In-transit modification | Signatures and AEAD detect modification independently of TLS |
| T14 Backup extraction | Keystore keys are non-exportable; wrapped blobs go in `noBackupFilesDir` |
| T16 Parent repudiation | **Partially addressed.** The parent key is bound to user authentication, but within a 30-second window that a device credential can also open. Deferred to M2 (ADR-0004): timeout 0 with a per-use `CryptoObject`, `BIOMETRIC_STRONG` only |
| T17 Removed device reads chat | Thread keys come from HKDF with an epoch in `info`; rotation means a new epoch (M4) |

## Caveats for consumers

- **ECDSA malleability.** `(r, s)` and `(r, n−s)` both verify. Hash chains and event ids must be computed over the payload, never over signature bytes.
- **X25519 on Android.** JCE `XDH` comes from Conscrypt on recent Android releases. Before M4 ships chat on minSdk 28, check that it is available on API 28–32 and add a fallback (Tink or BouncyCastle) behind `KeyAgreement` if it is not.
- **Parent key and `CryptoObject`.** The key uses a 30-second validity window, not per-use authentication. After a `BiometricPrompt` success, `initSignature()` works for 30 s. M2 moves it to a timeout of 0 with a per-use `CryptoObject` and `BIOMETRIC_STRONG` only (ADR-0004); that needs a new key alias, since Keystore key parameters cannot change after creation.
- **Key properties are not attestation.** `DeviceKeyStore.keyProperties()` and `ParentAuthKeyStore.keyProperties()` report what the local OS says through `KeyInfo`. Never base a trust decision about another device on them.
- **AEAD format.** `AesGcmAead` output starts with a version byte (`0x01`). Data encrypted before this change (no version byte) does not decrypt; nothing persisted `Aead` output before M4, so there is no migration.
- **Lockout and clock changes.** `PinLockoutPolicy` uses the injected wall clock. A forward clock jump shortens a lockout, so feature/lock should also keep an elapsed-realtime deadline.
- **Argon2 is Android-only.** argon2kt ships JNI libraries only (16 KB page aligned). JVM tests use PBKDF2. `src/androidTest` covers Argon2 and Keystore.

## Tests

- `./gradlew :core:crypto:testDebugUnitTest` runs on the JVM with no Robolectric. It covers ECDSA round trip and tampering plus malformed DER, malformed keys, and a provider that throws at verify time; AES-GCM (round trip, wrong or empty associated data, unknown and relabelled version bytes, tampering, truncation, 10,000 unique nonces); the HKDF RFC 5869 cases 1–3; the X25519 RFC 7748 §6.1 vector plus agreement in both directions; the PIN hash format, length bounds, verify and rehash; the lockout schedule; the wrapped secret store including the `createIfMissing` guard; and the StrongBox-to-TEE fallback logic.
- `src/androidTest/.../AndroidKeystoreInstrumentedTest` needs a device or emulator. It covers Keystore signing, Keystore wrapping with file storage, and Argon2id. The StrongBox fallback against a real secure element is only exercised on hardware that has one.
