import { FieldValue } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import { parseDeviceKey, registerParentKeyPayload } from '../crypto.js';
import { proofOf, verifyDeviceProof } from '../deviceProof.js';
import { db } from '../firebase.js';
import { dataOf, parent } from '../guards.js';
import { lamportOf, maxLamport, nextLamport } from '../lamport.js';
import { log } from '../log.js';
import { attestKey } from '../registration.js';

/**
 * registerParentKey({ publicKey, attestationChain?, deviceId, signature }) → { fingerprint, validFromLamport }:
 * registers a parent authorisation key (ADR-0004 §1) as `parentKeys/{fingerprint}`. Clients cannot write parentKeys.
 *
 * - The caller is a PARENT; `deviceId` is one of its ACTIVE parent devices and `signature` is that device's ECDSA
 *   P-256/SHA-256 DER signature over `"sprout/register-parent-key/v1" || 0x00 || hid || 0x00 || publicKey SPKI DER`.
 * - `publicKey` is a base64 EC P-256 SPKI; `fingerprint` = hex SHA-256 of its DER.
 * - With `attestationChain` (attested under the caller's outstanding challenge from beginDeviceRegistration) the key
 *   must be in a TEE or StrongBox and require biometric user authentication for every use (USER_AUTH_TYPE includes
 *   biometric, no AUTH_TIMEOUT or 0, no NO_AUTH_REQUIRED). Without one it is accepted as `attestation: NONE` only in
 *   the emulator or with `ALLOW_UNATTESTED=true`, otherwise `failed-precondition`.
 * - `validFromLamport` is one above the highest lamport of the household's events and documents, read in the write
 *   transaction, so the key can only authorise what is written after it.
 * - Registering the same key again for the same uid and device returns the stored row (idempotent retry); any other
 *   existing row with that fingerprint is `already-exists`.
 */
export async function registerParentKey(
  request: CallableRequest,
): Promise<{ fingerprint: string; validFromLamport: number }> {
  const caller = await parent(request, 'registerParentKey');
  const data = dataOf(request);
  const key = parseDeviceKey(data.publicKey, 'publicKey');
  const proof = proofOf(data, 'deviceId');
  const household = db.doc(`households/${caller.hid}`);
  const deviceRef = household.collection('devices').doc(proof.deviceId);
  verifyDeviceProof(
    await deviceRef.get(),
    caller,
    registerParentKeyPayload(caller.hid, key.spki),
    proof.signature,
  );
  const attestation = await attestKey(caller.uid, data.attestationChain, key, {
    hardwareOnly: true,
    biometricPerUse: true,
  });
  const fingerprint = key.deviceId;
  const ref = household.collection('parentKeys').doc(fingerprint);

  const result = await db.runTransaction(async (tx) => {
    const [existing, device] = await Promise.all([tx.get(ref), tx.get(deviceRef)]);
    if (existing.exists) {
      if (existing.get('uid') === caller.uid && existing.get('deviceId') === proof.deviceId) {
        return { validFromLamport: lamportOf(existing.get('validFromLamport')), created: false };
      }
      throw new HttpsError('already-exists', 'Parent key already registered');
    }
    // The device may have been revoked since the signature was checked.
    if (device.get('status') !== 'ACTIVE') {
      throw new HttpsError('permission-denied', 'The calling device is not an active parent device of the caller');
    }
    const validFromLamport = nextLamport(await maxLamport(tx, household));
    tx.create(ref, {
      publicKey: key.spki.toString('base64'),
      uid: caller.uid,
      deviceId: proof.deviceId,
      validFromLamport,
      registeredAt: FieldValue.serverTimestamp(),
      attestation,
    });
    return { validFromLamport, created: true };
  });
  log.info('parentKey.registered', {
    hid: caller.hid,
    fingerprint,
    deviceId: proof.deviceId,
    validFromLamport: result.validFromLamport,
    attestation,
    created: result.created,
  });
  return { fingerprint, validFromLamport: result.validFromLamport };
}
