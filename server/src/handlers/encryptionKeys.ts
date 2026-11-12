import { HttpsError, type CallableRequest } from '../https.js';
import { decodeBase64, parseEncryptionKey, registerEncryptionKeyPayload } from '../crypto.js';
import { proofOf, verifyOwnDeviceProof } from '../deviceProof.js';
import { requireKeyNotBoundElsewhere, verifiedEncryptionKey } from '../encryptionKeys.js';
import { db } from '../firebase.js';
import { dataOf, member } from '../guards.js';
import { log } from '../log.js';

/**
 * registerEncryptionKey({ publicKey, bindingSignature, deviceId, signature }) → { deviceId, created }: stores the
 * calling device's X25519 encryption key on its registry row (M4 §1.1, D-M4-2), for devices registered before M4 (or
 * whose registration did not carry one). Parent and kid devices alike.
 *
 * - The caller is a member (PARENT or KID, member row confirmed, session not older than `tokensValidAfter`);
 *   `deviceId` is one of its ACTIVE devices and `signature` that device's ECDSA P-256/SHA-256 DER signature over
 *   `"sprout/register-encryption-key/v2" || 0x00 || hid || 0x00 || sha256(raw ID token) || 0x00 || publicKey (32 raw
 *   bytes)` (crypto.ts `registerEncryptionKeyPayload`), valid only with the ID token of this request.
 * - `publicKey` is the base64 raw 32-byte X25519 key; `bindingSignature` the same device key's signature over
 *   `"sprout/device-encryption-key/v1" || 0x00 || hid || 0x00 || deviceId || 0x00 || publicKey` (core/domain-ledger
 *   `EncryptionKeyBindingPayload`), which every receiver verifies again from the registry. `permission-denied` if
 *   it does not verify.
 * - Registering the key the row already has answers `created: false` and changes nothing (the stored binding is
 *   kept); a different key already on the row is `failed-precondition` (a changed key needs a new device
 *   registration, D-M4-2), and so is a key another device row of the household holds (D-REL-32). The device is read
 *   again in the write transaction: one revoked meanwhile is refused.
 */
export async function registerEncryptionKey(
  request: CallableRequest,
): Promise<{ deviceId: string; created: boolean }> {
  const caller = await member(request, 'registerEncryptionKey');
  const data = dataOf(request);
  const publicKey = parseEncryptionKey(data.publicKey, 'publicKey');
  const bindingSignature = decodeBase64(data.bindingSignature, 'bindingSignature', 256);
  const proof = proofOf(data, 'deviceId');
  const deviceRef = db.doc(`households/${caller.hid}/devices/${proof.deviceId}`);
  const deviceKey = verifyOwnDeviceProof(
    await deviceRef.get(),
    caller,
    registerEncryptionKeyPayload(caller.hid, caller.idTokenHash, publicKey),
    proof.signature,
  );
  const fields = verifiedEncryptionKey(
    { key: publicKey, signature: bindingSignature },
    caller.hid,
    proof.deviceId,
    deviceKey.key,
  );

  const created = await db.runTransaction(async (tx) => {
    const device = await tx.get(deviceRef);
    if (!device.exists || device.get('uid') !== caller.uid || device.get('status') !== 'ACTIVE') {
      throw new HttpsError('permission-denied', 'The calling device is not an active device of the caller');
    }
    const existing: unknown = device.get('encryptionKey');
    if (existing === fields.encryptionKey) return false;
    if (existing !== undefined && existing !== null) {
      throw new HttpsError('failed-precondition', 'The device already has another encryption key');
    }
    await requireKeyNotBoundElsewhere(tx, caller.hid, proof.deviceId, fields.encryptionKey);
    tx.update(deviceRef, { ...fields });
    return true;
  });
  log.info('encryptionKey.registered', { hid: caller.hid, deviceId: proof.deviceId, role: caller.role, created });
  return { deviceId: proof.deviceId, created };
}
