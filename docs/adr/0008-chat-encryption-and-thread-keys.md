# ADR-0008: Chat encryption and thread keys

- **Status:** Accepted
- **Date:** 2026-11-03
- **Milestone:** Decided and implemented in M4 (`core/crypto`, `core/domain-ledger`, `core/database`, `core/sync`, `core/sync-firebase`, `firestore/`, `server/`, `feature/chat`, `app/`)
- **Related:** [ADR-0003](0003-security-model.md) (scoped E2EE), [ADR-0004](0004-parent-authorisation-and-device-identity.md) (device keys), [ADR-0006](0006-sync-protocol-and-document-envelope.md) (signed documents, sync lanes), [ADR-0007](0007-identity-linking-and-device-registry.md) (registry, revocation), [M4 spec](../milestones/M4.md), threat model T5, T17, T37 (metadata)

## Context

ADR-0003 promised end-to-end encryption for chat, voice notes and photos: the backend, and anyone holding the project's credentials, must see ciphertext only. M4 builds it on what M3 left behind: a registry of household devices with attested P-256 device keys, signed documents, sync lanes through `SyncTransport`, and a server that holds no secrets. The constraints:

- **The server holds no keys and sees no plaintext.** It verifies signatures and membership only.
- **Receivers trust the registry, never a claim.** Every key a device encrypts to must be bound to a registered device by something the receiver can verify.
- **Spark plan, no Cloud Storage** (D-M3-29, D-M3-38). New Storage buckets need Blaze.
- **minSdk 28.** JCE `XDH` (X25519) is reliable only from API 33.
- **Families lose phones.** A removed device must not read what is written after its removal; a new device should be usable without a long ceremony.
- **One thread per kid**, between the kid's devices and every parent device; no group threads.

## Decision

### 1. One encryption key per device, bound by the device key

Each device holds one X25519 key pair; the private scalar is a `SecretStore` secret (`chat.x25519.v1`, Keystore-wrapped, erased by the wipe). The public key is **bound** to the device: `sign_P256(device key, "sprout/device-encryption-key/v1" || 0x00 || hid || 0x00 || deviceId || 0x00 || x25519pub)`. The server stores `encryptionKey` and `encryptionKeySignature` on the device row only after verifying the binding against the registered device key, and every receiver verifies it again when it reads the registry; a key whose binding fails is ignored (D-M4-2).

The binding names the household, which `createHousehold` and `redeemLinkCode` only assign in their answer, so those devices (and every device linked before M4) register the key right after joining with the `registerEncryptionKey` callable (device proof, idempotent). The app does this at start-up and after every successful sync while the registry mirror shows no key for the device (D-M4-13). A second parent phone already knows its household and sends the key with `registerParentDevice`, bound by its pending registration key. A device's key never changes: the server answers `failed-precondition` to a different key (D-M4-14), and a phone that lost its key needs a new device registration.

X25519 runs on JCE where `XDH` works and on Tink's pure-Java implementation otherwise (D-M4-1).

### 2. Thread keys per (thread, epoch), wrapped per member

A thread key is a random 32-byte AES-256-GCM key per **(thread, epoch)**, created by a PARENT device and distributed as a signed `thread_key` document (`threadKeys`, PARENT-only writer, docId `kidId.epoch`): `{ threadId, epoch, createdByDeviceId, wraps: { deviceId: wrapped } }`. Each wrap is the thread key under `HKDF(X25519(sender, recipient), salt = sha256(hid), info = label || threadId || epoch)` with AAD naming household, thread, epoch, sender and recipient, so a wrap cannot be moved between documents. The sender wraps for itself too (D-M4-3).

Members of a thread are every ACTIVE PARENT device with a verified encryption key and every ACTIVE KID device of the thread's kid. Kid mode on a parent phone uses the parent device's keys (D-M3-12).

**Epoch rules** (`ThreadKeyPolicy`, pure Kotlin):

- **First key:** the first PARENT device with an encryption key creates epoch 0 when a kid exists.
- **Addition:** a new member gets the *current* epoch re-wrapped (a new document for the same epoch with a superset of wraps; last-writer-wins is safe because every document of an epoch wraps the same key).
- **Removal:** when a member device becomes REVOKED (or the kid is archived), the next PARENT device that notices creates epoch + 1 wrapped for the remaining members only. The server is not involved: `revokeDevice` is unchanged and the rotation is client-driven (D-M4-7). The app runs the upkeep (`ThreadKeyMaintenance`) on every PARENT device at start-up and after every successful pull.
- **Sending** uses the highest epoch the device can unwrap whose wrap set holds no device known to be REVOKED. A device that cannot unwrap such an epoch shows "waiting for a parent device" and does not send.
- Receivers keep every epoch key they could unwrap (`thread_keys`, inside SQLCipher), so old messages stay readable; when two parents race on one epoch, receivers try every key of that epoch and senders use the newest document's key by lamport (D-M4-11).
- New devices receive the current epoch only; older messages stay unreadable on them (D-M4-10).

### 3. Messages: signed envelope, AEAD bound to the header

A message is a `MessageEnvelope` (household, thread, message id, author uid, device and role, per-device `seq`, lamport, `createdAt`, epoch, kind TEXT | PRESET | VOICE | PHOTO, ciphertext, optional blob id), signed by the author device over `"sprout/message/v1" || 0x00 || canonical(envelope minus signature)`. The ciphertext is `AES-GCM(threadKey[epoch], plaintext, aad = canonical header)`, so it cannot be transplanted into another envelope. Receivers verify like ledger events (registry lookup, lamport bound, role and thread rule: KID devices only in their own thread, signature, `seq` continuity with gaps **held** and duplicates ignored; a gap held for more than 24 hours is skipped and audited, D-M4-12). A message whose epoch key is not here yet is stored LOCKED and opened when the key arrives; that is not a quarantine. The rules bound the ciphertext to 8 KB and scope reads: KID members read only their kid's thread.

### 4. Photos and voice notes as encrypted blobs in Firestore

Without Cloud Storage, photos and voice notes travel as **encrypted chunks in a Firestore `blobs` subcollection**: at most two `bytes` fields of at most 700 KB, each chunk header signed by the author device together with the chunk's SHA-256 (D-M4-5). Blobs are encrypted with the thread key of their kid's thread; photos are downscaled (≤ 1280 px, JPEG q80, ≤ 500 KB) and voice notes are ≤ 30 s AAC-LC at 32 kbps before encryption. Blobs are fetched on demand, verified against the registry, cached encrypted under `noBackupFilesDir/blobs` (50 MB LRU) and decrypted only for display.

**Chore photos** use the same path: the kid's phone uploads the submission photo as a blob with the kid's thread key once that key is there and writes the submission again with `photoRef.blobId` (D-M4-15); the parent's approvals inbox fetches it and checks its SHA-256 against the reference before showing it. "Photo on the kid's device" stays the fallback while the blob is absent (closes D-M2-3, D-M3-9).

Fallback if Firestore quotas bite: Vercel Blob (D-M4-5).

### 5. What the backend still sees

Ciphertext, plus the routing metadata needed to deliver and order messages: household, thread (= kid id), author uid, device and role, `seq`, lamport, timestamps, kind, sizes, epoch, and which devices a thread key is wrapped for. This is accepted (threat model T37).

## Consequences

- The backend, and anyone with project credentials, reads no chat content, voice note or photo (T5). Export contains message envelopes and blob metadata, never blob bytes; a plaintext export from a device is M7 (D-M4-6).
- **No forward secrecy** (D-M4-4): wraps use static-static ECDH, so a device that held an epoch key can read that epoch forever, and anyone who later extracts a device's X25519 private key can unwrap every thread key ever wrapped for it. Rotation on removal protects messages written after the removal only (T17).
- Rotation depends on a PARENT device coming online after the revocation. Until it does, senders keep using the current epoch only if its wrap set holds no REVOKED device; otherwise they wait. A household with no parent device online cannot rotate.
- A revoked device that never syncs again keeps the epochs it already had, inside its SQLCipher database; it wipes everything on its next launch with a network (ADR-0007).
- Losing every household device loses the chat history (accepted risk in the threat model): no escrow.
- Firestore quotas now carry media: two 700 KB chunks per photo at worst; the Spark plan's daily limits are watched at M8.

## Alternatives considered

- **Pairwise keys per device pair** (the original T5 wording). Every message would be encrypted once per recipient device, multiplying ciphertext and Firestore writes by the number of members, and adding a device would mean re-sending history. Rejected for the per-epoch thread key.
- **MLS or a Signal-style double ratchet** for forward secrecy. Correct, but a large, hard-to-review dependency or a lot of new protocol code for a family chat whose threat model does not include a compromised household device with key extraction. Rejected for v1; static-static wrapping is documented as a known limit.
- **Server-held or escrowed thread keys.** Would make recovery after losing every device possible, but breaks the E2EE claim. Rejected.
- **Cloud Storage for media.** Needs the Blaze plan for a new bucket (D-M3-29). Rejected; Firestore `bytes` chunks fit the photo and voice-note budgets.
- **Rotating keys on the server at `revokeDevice`.** The server holds no keys, so it cannot. Rotation is the job of the next PARENT device that notices the revocation.
