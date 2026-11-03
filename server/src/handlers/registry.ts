import { FieldValue } from 'firebase-admin/firestore';
import type { AttestationRecord } from '../registration.js';
import type { DeviceKey } from '../crypto.js';
import type { EncryptionKeyFields } from '../encryptionKeys.js';

export type DeviceStatus = 'PENDING' | 'ACTIVE' | 'REVOKED';

/**
 * A `households/{hid}/devices/{deviceId}` row (M3 §1), with the device's verified encryption key when it registered
 * one (`encryptionKey`, `encryptionKeySignature`, M4 §1.1). Written by the server only.
 */
export function deviceDocument(
  uid: string,
  role: 'PARENT' | 'KID',
  key: DeviceKey,
  attestation: AttestationRecord,
  status: DeviceStatus,
  kidId?: string,
  encryptionKey?: EncryptionKeyFields,
): Record<string, unknown> {
  return {
    uid,
    role,
    ...(kidId === undefined ? {} : { kidId }),
    publicKey: key.spki.toString('base64'),
    attestation,
    status,
    registeredAt: FieldValue.serverTimestamp(),
    lamport: 0,
    ...(encryptionKey ?? {}),
  };
}
