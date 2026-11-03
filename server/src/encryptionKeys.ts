import { HttpsError } from './https.js';
import { decodeBase64, encryptionKeyBindingPayload, parseEncryptionKey, verifyEcdsaSha256 } from './crypto.js';
import type { KeyObject } from 'node:crypto';

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
