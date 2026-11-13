/**
 * Account recovery (ADR-0013, D-M3-42): the admit-and-revoke step shared by the in-app recovery
 * (handlers/recovery.ts `recoverParentDevice`) and the operator's `recover-household` (scripts/operator.ts), and the
 * check value of the recovery PIN.
 */
import { createHmac, hkdfSync, timingSafeEqual } from 'node:crypto';
import {
  FieldValue,
  type DocumentReference,
  type DocumentSnapshot,
  type QueryDocumentSnapshot,
  type Transaction,
} from 'firebase-admin/firestore';
import { recoveryPinSecret } from './config.js';
import { sha256Hex } from './crypto.js';
import { applyRevocation, planRevocation, type RevocationOutcome, type RevocationPlan } from './handlers/devices.js';

export {
  finishMarkedRecovery,
  finishRecoverySessions,
  finishRecoverySessionsWithRetry,
  SESSIONS_PENDING_FIELD,
} from './recoverySessions.js';


/** Every PARENT device row of [household], read in [tx]. */
export async function readParentDevices(tx: Transaction, household: DocumentReference): Promise<QueryDocumentSnapshot[]> {
  return (await tx.get(household.collection('devices').where('role', '==', 'PARENT'))).docs;
}

/** The ACTIVE devices among [devices] of a parent other than [uid] (that parent approves new phones in the app). */
export function activeDevicesOfOtherParents(
  devices: readonly QueryDocumentSnapshot[],
  uid: string,
): QueryDocumentSnapshot[] {
  return devices.filter((d) => d.get('uid') !== uid && d.get('status') === 'ACTIVE');
}

/**
 * Plans, inside [tx], the revocation of every parent device of [uid] among [devices] except [admittedId] and those
 * already REVOKED (handlers/devices.ts `planRevocation`). Reads only, so it comes before the first write.
 */
export async function planRecoveryRevocations(
  tx: Transaction,
  household: DocumentReference,
  devices: readonly QueryDocumentSnapshot[],
  uid: string,
  admittedId: string,
): Promise<RevocationPlan[]> {
  const plans: RevocationPlan[] = [];
  for (const device of devices) {
    if (device.get('uid') !== uid || device.id === admittedId || device.get('status') === 'REVOKED') continue;
    plans.push(await planRevocation(tx, household, device));
  }
  return plans;
}

/** How a recovery is written: who admitted the device and the fields that differ between the app and the operator. */
export interface RecoveryWrite {
  /** `approvedBy` of the admitted device: `"recovery"` (the app, ADR-0013) or `"operator"` (`recover-household`). */
  readonly approvedBy: 'recovery' | 'operator';
  /** Written to the admitted device row besides status, approvedBy, approvedAt and recoveryId. */
  readonly deviceFields?: Readonly<Record<string, unknown>>;
  /** Written to the `recoveries` record besides hidHash, at, deviceId and revokedDevices (no uid, no names). */
  readonly recordFields: Readonly<Record<string, unknown>>;
  /** Written to every revoked device row with its revocation. */
  readonly revokedDeviceFields?: Readonly<Record<string, unknown>>;
}

export interface RecoveryOutcome {
  readonly recordId: string;
  readonly outcomes: readonly (RevocationOutcome & { readonly deviceId: string })[];
}

/**
 * Writes a recovery in [tx], after every read: the [plans] applied as revokeDevice applies them (REVOKED,
 * `revokedAtLamport`, parent keys stamped), the PENDING device [admitted] of household [hid] ACTIVE, and
 * `recoveries/{autoId}` = `{ hidHash, at, deviceId, revokedDevices: [{ deviceId, revokedAtLamport }], ...recordFields }`.
 * Sessions are revoked by the caller after the transaction ([finishRecoverySessions] or the operator's own loop).
 */
export function applyRecovery(
  tx: Transaction,
  hid: string,
  admitted: DocumentSnapshot,
  plans: readonly RevocationPlan[],
  write: RecoveryWrite,
  record: DocumentReference,
): RecoveryOutcome {
  const outcomes = plans.map((plan) => ({
    deviceId: plan.target.id,
    ...applyRevocation(tx, plan, write.revokedDeviceFields),
  }));
  tx.update(admitted.ref, {
    ...(write.deviceFields ?? {}),
    status: 'ACTIVE',
    approvedBy: write.approvedBy,
    approvedAt: FieldValue.serverTimestamp(),
    recoveryId: record.id,
  });
  tx.create(record, {
    ...write.recordFields,
    hidHash: sha256Hex(hid),
    at: FieldValue.serverTimestamp(),
    deviceId: admitted.id,
    revokedDevices: outcomes.map((o) => ({ deviceId: o.deviceId, revokedAtLamport: o.revokedAtLamport })),
  });
  return { recordId: record.id, outcomes };
}

/** A Firestore Timestamp as epoch milliseconds, undefined when [value] is not one. */
export function millisOf(value: unknown): number | undefined {
  const date = (value as { toDate?: () => Date } | undefined)?.toDate?.();
  return date instanceof Date ? date.getTime() : undefined;
}

/** HKDF `info` of the recovery PIN key (ADR-0013 §1, §4), derived from RECOVERY_PIN_HMAC_KEY. */
export const RECOVERY_PIN_KEY_INFO = 'sprout/recovery-pin/v1';

/** Length of a recovery PIN verifier, of its MAC and of the salt (bytes, ADR-0013 §4). */
export const RECOVERY_VERIFIER_BYTES = 32;
export const RECOVERY_SALT_BYTES = 16;

let derived: { from: string; key: Buffer } | undefined;

/**
 * The MAC key of recovery PIN check values: HKDF-SHA256 of [recoveryPinSecret] (RECOVERY_PIN_HMAC_KEY), empty salt,
 * info [RECOVERY_PIN_KEY_INFO], 32 bytes. The secret lives only in the host's sensitive variables, so a copy of
 * `recoveryPins` alone does not allow guessing PINs offline. Read on every use: without the variable in production
 * only the PIN paths fail (`internal`).
 */
function recoveryPinKey(): Buffer {
  const source = recoveryPinSecret();
  if (derived?.from !== source) {
    derived = { from: source, key: Buffer.from(hkdfSync('sha256', source, Buffer.alloc(0), RECOVERY_PIN_KEY_INFO, 32)) };
  }
  return derived.key;
}

const SEPARATOR = Buffer.from([0]);

/** `HMAC-SHA256(recoveryPinKey, utf8(hid) || 0x00 || utf8(uid) || 0x00 || verifier)` (ADR-0013 §4). */
export function recoveryPinMac(hid: string, uid: string, verifier: Buffer): Buffer {
  return createHmac('sha256', recoveryPinKey())
    .update(Buffer.concat([Buffer.from(hid, 'utf8'), SEPARATOR, Buffer.from(uid, 'utf8'), SEPARATOR, verifier]))
    .digest();
}

/**
 * The id of the current recovery PIN key: the first 16 bytes of `HMAC-SHA256(key, "sprout/recovery-pin/key-id")`, hex.
 * Stored with each check value, so a value made under an earlier RECOVERY_PIN_HMAC_KEY (RUNBOOK §5 rotates it) counts as
 * absent instead of as a wrong PIN: it can no longer match, and comparing it would only use up the parent's attempts.
 */
export function recoveryPinKeyId(): string {
  return createHmac('sha256', recoveryPinKey()).update('sprout/recovery-pin/key-id', 'utf8').digest().subarray(0, 16).toString('hex');
}

/** Whether [verifier] matches the stored MAC [stored] (base64) of ([hid], [uid]), compared in constant time. */
export function recoveryPinMatches(stored: unknown, hid: string, uid: string, verifier: Buffer): boolean {
  if (typeof stored !== 'string') return false;
  const expected = Buffer.from(stored, 'base64');
  const actual = recoveryPinMac(hid, uid, verifier);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
