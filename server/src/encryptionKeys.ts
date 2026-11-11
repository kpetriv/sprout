import { HttpsError } from './https.js';
import { decodeBase64, encryptionKeyBindingPayload, parseEncryptionKey, verifyEcdsaSha256 } from './crypto.js';
import type { KeyObject } from 'node:crypto';
import type { Transaction } from 'firebase-admin/firestore';
import { db } from './firebase.js';

/** The two device-row fields of a verified encryption key (M4 §1.1), both base64. */
export interface EncryptionKeyFields {
  readonly encryptionKey: string;
  readonly encryptionKeySignature: string;
}

/** A submitted encryption key before its binding is verified: the raw key and the binding signature. */
export interface EncryptionKeyClaim {
  readonly key: Buffer;
  readonly signature: Buffer;
}

/**
 * The optional `encryptionKey` + `encryptionKeySignature` arguments of a registration callable: both absent (null or
 * undefined) gives undefined, one without the other or a malformed value is `invalid-argument`.
 */
export function encryptionKeyClaimOf(data: Record<string, unknown>): EncryptionKeyClaim | undefined {
  const key: unknown = data.encryptionKey;
  const signature: unknown = data.encryptionKeySignature;
  const absent = (v: unknown) => v === undefined || v === null;
  if (absent(key) && absent(signature)) return undefined;
  if (absent(key) || absent(signature)) {
    throw new HttpsError('invalid-argument', 'encryptionKey and encryptionKeySignature go together');
  }
  return {
    key: parseEncryptionKey(key, 'encryptionKey'),
    signature: decodeBase64(signature, 'encryptionKeySignature', 256),
  };
}

/**
 * Verifies [claim]'s binding (core/domain-ledger `EncryptionKeyBindingPayload`: hid, deviceId, key) against the
 * device key [deviceKey] and returns the fields to store; `permission-denied` when it does not verify (another
 * household, another device, another key, or not this device's signature).
 */
export function verifiedEncryptionKey(
  claim: EncryptionKeyClaim,
  hid: string,
  deviceId: string,
  deviceKey: KeyObject,
): EncryptionKeyFields {
  if (!verifyEcdsaSha256(deviceKey, encryptionKeyBindingPayload(hid, deviceId, claim.key), claim.signature)) {
    throw new HttpsError('permission-denied', 'Encryption key binding does not verify');
  }
  return {
    encryptionKey: claim.key.toString('base64'),
    encryptionKeySignature: claim.signature.toString('base64'),
  };
}

/**
 * Inside [tx], before any write: refuses (`failed-precondition`) an encryption key (base64, as stored) that another
 * device row of household [hid] already holds (D-REL-27, defence in depth). A device's chat key is created on the phone
 * for one device id and is never moved to another (a kept key is stashed before the phone registers again), so a key
 * bound to two rows means a key that leaked or a client bug; either way the second row must not get it, since every
 * wrap for it would also open for the first device. The device's own row ([deviceId]) may hold it (an idempotent retry).
 */
export async function requireKeyNotBoundElsewhere(
  tx: Transaction,
  hid: string,
  deviceId: string,
  encryptionKey: string,
): Promise<void> {
  const holders = await tx.get(
    db.collection(`households/${hid}/devices`).where('encryptionKey', '==', encryptionKey).limit(2),
  );
  if (holders.docs.some((doc) => doc.id !== deviceId)) {
    throw new HttpsError('failed-precondition', 'The encryption key is registered for another device');
  }
}
