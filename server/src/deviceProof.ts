import type { DocumentSnapshot } from 'firebase-admin/firestore';
import { HttpsError } from './https.js';
import {
  callablePayload,
  decodeBase64,
  parseDeviceKey,
  verifyEcdsaSha256,
  type DeviceKey,
  type SignedCallable,
} from './crypto.js';
import { db } from './firebase.js';
import { requireDeviceId, type Caller, type ParentCaller } from './guards.js';

/** The device proof of a privileged request (T7/T8): which device signed it, and the DER signature. */
export interface DeviceProof {
  readonly deviceId: string;
  readonly signature: Buffer;
}

/** Reads `[deviceField]` (default `callerDeviceId`) and `signature` from the request data. */
export function proofOf(data: Record<string, unknown>, deviceField = 'callerDeviceId'): DeviceProof {
  return {
    deviceId: requireDeviceId(data[deviceField], deviceField),
    signature: decodeBase64(data.signature, 'signature', 256),
  };
}

/**
 * Requires [device] (the snapshot of `devices/{proof.deviceId}`) to be an ACTIVE parent device of the caller and
 * [signature] to verify over [payload] with its registered key (ECDSA P-256, SHA-256, DER). Throws
 * `permission-denied` otherwise.
 */
export function verifyDeviceProof(device: DocumentSnapshot, caller: ParentCaller, payload: Buffer, signature: Buffer): void {
  if (
    !device.exists ||
    device.get('uid') !== caller.uid ||
    device.get('status') !== 'ACTIVE' ||
    device.get('role') !== 'PARENT'
  ) {
    throw new HttpsError('permission-denied', 'The calling device is not an active parent device of the caller');
  }
  const key = parseDeviceKey(device.get('publicKey'), 'device key');
  if (!verifyEcdsaSha256(key.key, payload, signature)) {
    throw new HttpsError('permission-denied', 'Device signature does not verify');
  }
}

/**
 * Requires [device] (the snapshot of `devices/{deviceId}`) to be an ACTIVE device of the caller, parent or kid, and
 * [signature] to verify over [payload] with its registered key; returns that key. `permission-denied` otherwise. For
 * requests a device makes about itself (registerEncryptionKey).
 */
export function verifyOwnDeviceProof(
  device: DocumentSnapshot,
  caller: Caller,
  payload: Buffer,
  signature: Buffer,
): DeviceKey {
  if (!device.exists || device.get('uid') !== caller.uid || device.get('status') !== 'ACTIVE') {
    throw new HttpsError('permission-denied', 'The calling device is not an active device of the caller');
  }
  const key = parseDeviceKey(device.get('publicKey'), 'device key');
  if (!verifyEcdsaSha256(key.key, payload, signature)) {
    throw new HttpsError('permission-denied', 'Device signature does not verify');
  }
  return key;
}

/**
 * Checks the device proof of a call to [callable] with the signed arguments [args] (everything but `callerDeviceId`
 * and `signature`, see crypto.ts `callablePayload`), bound to the ID token of this request, and returns the calling
 * device id.
 */
export async function requireDeviceProof(
  caller: ParentCaller,
  data: Record<string, unknown>,
  callable: SignedCallable,
  args: Record<string, unknown>,
): Promise<string> {
  const proof = proofOf(data);
  const device = await db.doc(`households/${caller.hid}/devices/${proof.deviceId}`).get();
  verifyDeviceProof(device, caller, callablePayload(callable, caller.hid, caller.idTokenHash, args), proof.signature);
  return proof.deviceId;
}
