import { FieldValue } from 'firebase-admin/firestore';
import type { AttestationRecord } from '../registration.js';
import type { DeviceKey } from '../crypto.js';

export type DeviceStatus = 'PENDING' | 'ACTIVE' | 'REVOKED';

/** A `households/{hid}/devices/{deviceId}` row (M3 §1). Written by functions only. */
export function deviceDocument(
  uid: string,
  role: 'PARENT' | 'KID',
  key: DeviceKey,
  attestation: AttestationRecord,
  status: DeviceStatus,
  kidId?: string,
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
  };
}
