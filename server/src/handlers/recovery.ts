import { FieldValue, type DocumentSnapshot } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import { MAX_RECOVERY_PIN_ATTEMPTS, RECENT_SIGN_IN_MS, RECOVERY_WAITING_DAYS } from '../config.js';
import { callablePayload, decodeBase64 } from '../crypto.js';
import { proofOf, requirePending, verifyDeviceProof, verifyOwnParentDeviceProof } from '../deviceProof.js';
import { db } from '../firebase.js';
import { dataOf, parent, type ParentCaller } from '../guards.js';
import { log } from '../log.js';
import { limitDevice } from '../rateLimit.js';
import {
  activeDevicesOfOtherParents,
  applyRecovery,
  finishMarkedRecovery,
  finishRecoverySessionsWithRetry,
  millisOf,
  planRecoveryRevocations,
  readParentDevices,
  RECOVERY_SALT_BYTES,
  RECOVERY_VERIFIER_BYTES,
  recoveryPinKeyId,
  recoveryPinMac,
  recoveryPinMatches,
  SESSIONS_PENDING_FIELD,
} from '../recovery.js';
import { recoveryRecordOf } from '../recoverySessions.js';

/**
 * Account recovery for a parent who lost every phone (ADR-0013): the check value of the parent PIN
 * (`recoveryPins/{uid}`, set by an ACTIVE parent device) and the two callables of the parent's new, PENDING phone.
 * Rate limits: per IP before anything is read (in the guard), then per device once its proof verified; no per-uid
 * bucket (ADR-0013 §2). Logs ids and counts only: never the salt, the verifier, the MAC or the KDF descriptor.
 */

/** The KDF descriptors a recovering phone can follow (ADR-0013 §4); the server checks only their shape. */
const KDF_ARGON2ID = /^argon2id\$v=19\$m=\d{1,7},t=\d{1,2},p=\d$/;
const KDF_PBKDF2 = /^pbkdf2-sha256\$i=\d{1,8}$/;
const MAX_KDF_LENGTH = 64;

export type RecoveryMethod = 'PIN' | 'WAIT';

/**
 * Strict standard base64 of exactly [bytes] bytes in its canonical form (re-encoding gives the same text, so one value
 * has one spelling in the signed arguments), or `invalid-argument`.
 */
function exactBase64(value: unknown, field: string, bytes: number): Buffer {
  const decoded = decodeBase64(value, field, bytes);
  if (decoded.length !== bytes || decoded.toString('base64') !== value) {
    throw new HttpsError('invalid-argument', `${field} must be ${bytes} bytes of standard base64`);
  }
  return decoded;
}

function requireKdf(value: unknown): string {
  if (typeof value !== 'string' || value.length > MAX_KDF_LENGTH || !(KDF_ARGON2ID.test(value) || KDF_PBKDF2.test(value))) {
    throw new HttpsError('invalid-argument', 'kdf is not a supported descriptor');
  }
  return value;
}

function requireMethod(value: unknown): RecoveryMethod {
  if (value !== 'PIN' && value !== 'WAIT') throw new HttpsError('invalid-argument', 'method must be PIN or WAIT');
  return value;
}

/** The stored count of wrong PINs; a value that is not a non-negative integer counts as all attempts used. */
function failedAttemptsOf(row: DocumentSnapshot): number {
  const value: unknown = row.get('failedAttempts');
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : MAX_RECOVERY_PIN_ATTEMPTS;
}

const attemptsLeftAfter = (failed: number): number => Math.max(0, MAX_RECOVERY_PIN_ATTEMPTS - failed);

/**
 * The PIN check value of [row] for [caller]'s household: present (a row of this hid, made with the current key) and the
 * attempts left. Computing the key id needs RECOVERY_PIN_HMAC_KEY, so with a stored row and no key in production this
 * fails closed (`internal`); without a row it needs no key.
 */
function pinState(row: DocumentSnapshot, caller: ParentCaller): { present: boolean; attemptsLeft: number } {
  if (!row.exists || row.get('hid') !== caller.hid || row.get('keyId') !== recoveryPinKeyId()) {
    return { present: false, attemptsLeft: 0 };
  }
  return { present: true, attemptsLeft: attemptsLeftAfter(failedAttemptsOf(row)) };
}

/** When the wait of [device] ends (epoch ms): its `recoveryRequestedAt` + 7 days, or null while none was asked for. */
function waitEndsAtOf(device: DocumentSnapshot): number | null {
  const requested = millisOf(device.get('recoveryRequestedAt'));
  return requested === undefined ? null : requested + RECOVERY_WAITING_DAYS * 24 * 60 * 60 * 1000;
}

/**
 * setRecoveryPin({ kdf, newPin, salt, verifier, callerDeviceId, signature }) → { set: true, stored, attemptsLeft }: stores the
 * check value of the parent PIN (ADR-0013 §1) in `recoveryPins/{uid}` = `{ hid, salt, kdf, mac, keyId, failedAttempts,
 * lastFailedAt?, setAt, setByDeviceId }`, replacing any earlier one. `salt` (16 bytes) and `verifier` (32 bytes, the
 * client's KDF of the PIN) are canonical standard base64; `kdf` is a descriptor of the shape ADR-0013 §4 gives; `mac`
 * is [recoveryPinMac] (base64) and `keyId` [recoveryPinKeyId]; the verifier itself is not stored. `newPin: true` (a PIN
 * just chosen or changed) starts `failedAttempts` at 0; `newPin: false` (the same PIN registered again) keeps
 * `failedAttempts` and `lastFailedAt` of an existing row of the same household, so registering again never hands a
 * guesser new attempts. One value per account (ADR-0013 §1): `newPin: false` leaves an existing row of the same
 * household under the current key untouched when another device of the caller set it and is still ACTIVE (that phone's
 * PIN, set or changed most recently, is the one that restores), and answers from that row with `stored: false` (every
 * call that writes the row answers `stored: true`; `set` is true in both, for clients that read only `attemptsLeft`). Signed by an ACTIVE parent
 * device of the caller over `callablePayload('setRecoveryPin', hid, sha256(raw ID token), { kdf, newPin, salt,
 * verifier })`. The member row, the device and the existing row are read and
 * the row written in one transaction, so a household being deleted (members go first) cannot get its row back.
 */
export async function setRecoveryPin(
  request: CallableRequest,
): Promise<{ set: true; stored: boolean; attemptsLeft: number }> {
  const caller = await parent(request, 'setRecoveryPin');
  const data = dataOf(request);
  exactBase64(data.salt, 'salt', RECOVERY_SALT_BYTES);
  const verifier = exactBase64(data.verifier, 'verifier', RECOVERY_VERIFIER_BYTES);
  const kdf = requireKdf(data.kdf);
  const newPin: unknown = data.newPin;
  if (typeof newPin !== 'boolean') throw new HttpsError('invalid-argument', 'newPin must be true or false');
  const salt = data.salt as string;
  const proof = proofOf(data);
  const payload = callablePayload('setRecoveryPin', caller.hid, caller.idTokenHash, {
    kdf,
    newPin,
    salt,
    verifier: data.verifier,
  });
  const household = db.doc(`households/${caller.hid}`);
  const deviceRef = household.collection('devices').doc(proof.deviceId);
  const callerDevice = await deviceRef.get();
  verifyDeviceProof(callerDevice, caller, payload, proof.signature);
  await limitDevice('setRecoveryPin', caller.hid, proof.deviceId);
  await finishMarkedRecovery(callerDevice, caller.hid, caller.uid);
  const mac = recoveryPinMac(caller.hid, caller.uid, verifier).toString('base64');
  const keyId = recoveryPinKeyId();
  const pinRef = db.doc(`recoveryPins/${caller.uid}`);

  const { failed, keptOther } = await db.runTransaction(async (tx) => {
    const [device, member, row] = await Promise.all([
      tx.get(deviceRef),
      tx.get(household.collection('members').doc(caller.uid)),
      tx.get(pinRef),
    ]);
    verifyDeviceProof(device, caller, payload, proof.signature);
    if (!member.exists || member.get('role') !== 'PARENT') throw new HttpsError('permission-denied', 'Parents only');
    const sameHousehold = row.exists && row.get('hid') === caller.hid;
    // One value per account (ADR-0013 §1): a refresh never replaces the value of the PIN another ACTIVE phone of the
    // account set or changed, made under the current key; that phone's PIN is the one that restores.
    if (!newPin && sameHousehold && row.get('keyId') === keyId) {
      const setBy: unknown = row.get('setByDeviceId');
      if (typeof setBy === 'string' && /^[0-9a-f]{64}$/.test(setBy) && setBy !== proof.deviceId) {
        const setter = await tx.get(household.collection('devices').doc(setBy));
        if (setter.exists && setter.get('status') === 'ACTIVE' && setter.get('uid') === caller.uid) {
          return { failed: failedAttemptsOf(row), keptOther: true };
        }
      }
    }
    const keep = !newPin && sameHousehold;
    const count = keep ? failedAttemptsOf(row) : 0;
    const lastFailedAt: unknown = keep ? row.get('lastFailedAt') : undefined;
    tx.set(pinRef, {
      hid: caller.hid,
      salt,
      kdf,
      mac,
      keyId,
      failedAttempts: count,
      ...(lastFailedAt === undefined ? {} : { lastFailedAt }),
      setAt: FieldValue.serverTimestamp(),
      setByDeviceId: proof.deviceId,
    });
    return { failed: count, keptOther: false };
  });
  const attemptsLeft = attemptsLeftAfter(failed);
  if (keptOther) {
    log.info('recoveryPin.kept', { hid: caller.hid, deviceId: proof.deviceId, attemptsLeft });
  } else {
    log.info('recoveryPin.set', { hid: caller.hid, deviceId: proof.deviceId, newPin, attemptsLeft });
  }
  return { set: true, stored: !keptOther, attemptsLeft };
}

export interface RecoveryStatus {
  readonly pin: { readonly salt: string; readonly kdf: string } | null;
  readonly attemptsLeft: number;
  readonly waitEndsAt: number | null;
}

/**
 * recoveryStatus({ deviceId, signature }) → { pin: { salt, kdf } | null, attemptsLeft, waitEndsAt } for the caller's
 * own PENDING parent phone (ADR-0013 §2), signed by that phone over
 * `callablePayload('recoveryStatus', hid, sha256(raw ID token), {})`. `pin` is null when no check value is stored (or
 * one of another household or an earlier key, or the server has no usable RECOVERY_PIN_HMAC_KEY) or the wrong PINs
 * used it up; `attemptsLeft` is 0 then. `waitEndsAt` is
 * null until the phone asked for the wait (recoverParentDevice `WAIT`), then its `recoveryRequestedAt` + 7 days (epoch
 * ms). Reading the status never starts the wait.
 */
export async function recoveryStatus(request: CallableRequest): Promise<RecoveryStatus> {
  const caller = await parent(request, 'recoveryStatus');
  const proof = proofOf(dataOf(request), 'deviceId');
  const device = await db.doc(`households/${caller.hid}/devices/${proof.deviceId}`).get();
  const payload = callablePayload('recoveryStatus', caller.hid, caller.idTokenHash, {});
  verifyOwnParentDeviceProof(device, caller, payload, proof.signature);
  await limitDevice('recoveryStatus', caller.hid, proof.deviceId);
  requirePending(device);
  const row = await db.doc(`recoveryPins/${caller.uid}`).get();
  let state: { present: boolean; attemptsLeft: number };
  try {
    state = pinState(row, caller);
  } catch (error) {
    // No usable RECOVERY_PIN_HMAC_KEY: the screen can still offer the wait (recovery with the PIN fails closed).
    if (!(error instanceof HttpsError) || error.code !== 'internal') throw error;
    log.warn('recovery.pinKeyUnavailable', { hid: caller.hid, deviceId: proof.deviceId });
    state = { present: false, attemptsLeft: 0 };
  }
  const { present, attemptsLeft } = state;
  const available = present && attemptsLeft > 0;
  log.info('recovery.statusChecked', { hid: caller.hid, deviceId: proof.deviceId, pin: available, attemptsLeft });
  return {
    pin: available ? { salt: row.get('salt') as string, kdf: row.get('kdf') as string } : null,
    attemptsLeft: available ? attemptsLeft : 0,
    waitEndsAt: waitEndsAtOf(device),
  };
}

function refused(
  caller: ParentCaller,
  deviceId: string,
  reason: string,
  details: Record<string, string | number> = {},
): void {
  log.warn('device.recoveryRefused', { hid: caller.hid, deviceId, reason, ...details });
}

export interface RecoveryAnswer {
  readonly deviceId: string;
  readonly status: 'ACTIVE';
  readonly revokedDevices: number;
  readonly method: RecoveryMethod;
  readonly attemptsLeft: number;
  readonly signInAgain: true;
}

type Outcome =
  | { readonly kind: 'wrong-pin'; readonly attemptsLeft: number }
  | { readonly kind: 'wait-started' }
  | { readonly kind: 'finish'; readonly recoveryId: unknown }
  | {
      readonly kind: 'recovered';
      readonly revokedDeviceIds: readonly string[];
      readonly attemptsLeft: number;
      readonly recordId: string;
    };

/**
 * recoverParentDevice({ method: "PIN", verifier } | { method: "WAIT" }, deviceId, signature) →
 * { deviceId, status: "ACTIVE", revokedDevices, method, attemptsLeft, signInAgain: true } (ADR-0013 §2): the caller's
 * own PENDING parent phone, signed by it over `callablePayload('recoverParentDevice', hid, sha256(raw ID token),
 * { method, verifier? })`, becomes ACTIVE and every other parent device of the caller is revoked as revokeDevice
 * revokes it; `revokedDevices` is their count.
 *
 * - The session must have signed in within [RECENT_SIGN_IN_MS] (`failed-precondition`, reason `recent-sign-in`), and
 *   not before the member's `tokensValidAfter` (`unauthenticated`, also re-checked in the transaction).
 * - Refused (`failed-precondition`, reason `other-parent`) while another parent of the household has an ACTIVE device.
 * - `PIN`: in one transaction, the check value is read; none, another household's, one made under an earlier key, or
 *   [MAX_RECOVERY_PIN_ATTEMPTS] wrong PINs used → `failed-precondition` (reason `pin-unavailable`). A MAC mismatch
 *   (constant time) increments `failedAttempts`, commits, and answers `permission-denied` (reason `wrong-pin`,
 *   `attemptsLeft`): concurrent calls serialise on the row, so no more than [MAX_RECOVERY_PIN_ATTEMPTS] comparisons ever
 *   fail per PIN. A match recovers and keeps the row and its count (audit M1).
 * - `WAIT`: the first call writes `recoveryRequestedAt` (server time, once, never moved) on the device row; it and
 *   every call before that + [RECOVERY_WAITING_DAYS] days answer `failed-precondition` (reason `wait`, `waitEndsAt`).
 *   After that it recovers and deletes `recoveryPins/{uid}` (audit M2: the wait no longer starts at registration).
 * - Recovering ([applyRecovery], shared with the operator's `recover-household`): the phone ACTIVE with
 *   `approvedBy: "recovery"`, `recoveredAt`, `recoveryMethod` and the mark `sessionsRevocationPending`; a `recoveries`
 *   record `{ hidHash, at, deviceId, revokedDevices, method, ticket: null }`; `members/{uid}.tokensValidAfter` raised to
 *   this token's `auth_time` (never lowered). After the transaction the account's sessions are revoked as revokeDevice
 *   revokes them ([finishRecoverySessions]: refresh tokens, `tokensValidAfter` from Auth, `sessionsRevokedAt` on the
 *   revoked rows, the mark removed), so this phone signs in again too (`signInAgain`; audit H1). If that fails the
 *   answer is `internal` and the mark stays; the same call repeated from the now ACTIVE phone finishes it and answers as
 *   the first call would have.
 */
export async function recoverParentDevice(request: CallableRequest): Promise<RecoveryAnswer> {
  const caller = await parent(request, 'recoverParentDevice');
  const data = dataOf(request);
  const method = requireMethod(data.method);
  let verifier: Buffer | undefined;
  let args: Record<string, unknown>;
  if (method === 'PIN') {
    verifier = exactBase64(data.verifier, 'verifier', RECOVERY_VERIFIER_BYTES);
    args = { method, verifier: data.verifier };
  } else {
    if (data.verifier !== undefined) throw new HttpsError('invalid-argument', 'verifier is not taken with WAIT');
    args = { method };
  }
  const proof = proofOf(data, 'deviceId');
  if (Date.now() - caller.authTime * 1000 > RECENT_SIGN_IN_MS) {
    refused(caller, proof.deviceId, 'recent-sign-in');
    throw new HttpsError('failed-precondition', 'Sign in again to restore the account', { reason: 'recent-sign-in' });
  }
  const payload = callablePayload('recoverParentDevice', caller.hid, caller.idTokenHash, args);
  const household = db.doc(`households/${caller.hid}`);
  const deviceRef = household.collection('devices').doc(proof.deviceId);
  const memberRef = household.collection('members').doc(caller.uid);
  const pinRef = db.doc(`recoveryPins/${caller.uid}`);
  verifyOwnParentDeviceProof(await deviceRef.get(), caller, payload, proof.signature);
  await limitDevice('recoverParentDevice', caller.hid, proof.deviceId);
  const record = db.collection('recoveries').doc();

  const outcome = await db.runTransaction(async (tx): Promise<Outcome> => {
    const [device, member, row, devices] = await Promise.all([
      tx.get(deviceRef),
      tx.get(memberRef),
      tx.get(pinRef),
      readParentDevices(tx, household),
    ]);
    verifyOwnParentDeviceProof(device, caller, payload, proof.signature);
    if (!member.exists || member.get('role') !== 'PARENT') throw new HttpsError('permission-denied', 'Parents only');
    const validAfter: unknown = member.get('tokensValidAfter');
    const previous = typeof validAfter === 'number' && Number.isFinite(validAfter) ? validAfter : 0;
    if (previous > caller.authTime) throw new HttpsError('unauthenticated', 'Sign in again');
    if (
      device.get('status') === 'ACTIVE' &&
      device.get('approvedBy') === 'recovery' &&
      device.get(SESSIONS_PENDING_FIELD) === true
    ) {
      return { kind: 'finish', recoveryId: device.get('recoveryId') };
    }
    requirePending(device);
    if (activeDevicesOfOtherParents(devices, caller.uid).length > 0) {
      refused(caller, proof.deviceId, 'other-parent');
      throw new HttpsError('failed-precondition', 'Another parent can approve this phone', { reason: 'other-parent' });
    }
    let attemptsLeft = 0;
    if (method === 'PIN') {
      const state = pinState(row, caller);
      if (!state.present || state.attemptsLeft === 0) {
        refused(caller, proof.deviceId, 'pin-unavailable');
        throw new HttpsError('failed-precondition', 'PIN recovery is not available', { reason: 'pin-unavailable' });
      }
      if (!recoveryPinMatches(row.get('mac'), caller.hid, caller.uid, verifier!)) {
        tx.update(pinRef, { failedAttempts: failedAttemptsOf(row) + 1, lastFailedAt: FieldValue.serverTimestamp() });
        return { kind: 'wrong-pin', attemptsLeft: state.attemptsLeft - 1 };
      }
      attemptsLeft = state.attemptsLeft;
    } else {
      const ends = waitEndsAtOf(device);
      if (ends === null) {
        tx.update(deviceRef, { recoveryRequestedAt: FieldValue.serverTimestamp() });
        return { kind: 'wait-started' };
      }
      if (Date.now() < ends) {
        refused(caller, proof.deviceId, 'wait', { waitEndsAt: ends });
        throw new HttpsError('failed-precondition', 'The waiting period is not over', { reason: 'wait', waitEndsAt: ends });
      }
    }
    const plans = await planRecoveryRevocations(tx, household, devices, caller.uid, proof.deviceId);
    // All reads are done: the writes follow.
    const applied = applyRecovery(
      tx,
      caller.hid,
      device,
      plans,
      {
        approvedBy: 'recovery',
        deviceFields: {
          recoveredAt: FieldValue.serverTimestamp(),
          recoveryMethod: method,
          [SESSIONS_PENDING_FIELD]: true,
        },
        recordFields: { method, ticket: null },
      },
      record,
    );
    tx.update(memberRef, { tokensValidAfter: Math.max(previous, caller.authTime) });
    // PIN: the row and its count stay (audit M1). WAIT: the PIN is forgotten.
    if (method === 'WAIT') tx.delete(pinRef);
    return {
      kind: 'recovered',
      revokedDeviceIds: applied.outcomes.map((o) => o.deviceId),
      attemptsLeft,
      recordId: applied.recordId,
    };
  });

  switch (outcome.kind) {
    case 'wrong-pin':
      refused(caller, proof.deviceId, 'wrong-pin', { attemptsLeft: outcome.attemptsLeft });
      throw new HttpsError('permission-denied', 'That PIN does not match', {
        reason: 'wrong-pin',
        attemptsLeft: outcome.attemptsLeft,
      });
    case 'wait-started': {
      const ends = waitEndsAtOf(await deviceRef.get());
      log.info('device.recoveryWaitStarted', { hid: caller.hid, deviceId: proof.deviceId, waitEndsAt: ends });
      throw new HttpsError('failed-precondition', 'The waiting period has started', { reason: 'wait', waitEndsAt: ends });
    }
    case 'finish':
      return finishPending(caller, proof.deviceId, outcome.recoveryId);
    case 'recovered':
      await finishRecoverySessionsWithRetry(caller.hid, caller.uid, proof.deviceId, outcome.revokedDeviceIds);
      log.info('device.recovered', {
        hid: caller.hid,
        deviceId: proof.deviceId,
        method,
        revokedDevices: outcome.revokedDeviceIds.length,
        recoveryId: outcome.recordId,
      });
      return {
        deviceId: proof.deviceId,
        status: 'ACTIVE',
        revokedDevices: outcome.revokedDeviceIds.length,
        method,
        attemptsLeft: outcome.attemptsLeft,
        signInAgain: true,
      };
  }
}

/**
 * A repeated recoverParentDevice from a phone this recovery admitted whose session revocation did not finish: finishes
 * it from the `recoveries` record and answers as the first call would have.
 */
async function finishPending(caller: ParentCaller, deviceId: string, recoveryId: unknown): Promise<RecoveryAnswer> {
  const record = await recoveryRecordOf(caller.hid, deviceId, recoveryId);
  await finishRecoverySessionsWithRetry(caller.hid, caller.uid, deviceId, record.revoked);
  const attemptsLeft =
    record.method === 'PIN' ? pinState(await db.doc(`recoveryPins/${caller.uid}`).get(), caller).attemptsLeft : 0;
  log.info('device.recovered', {
    hid: caller.hid,
    deviceId,
    method: record.method,
    revokedDevices: record.revoked.length,
    recoveryId: record.id,
    finished: true,
  });
  return {
    deviceId,
    status: 'ACTIVE',
    revokedDevices: record.revoked.length,
    method: record.method,
    attemptsLeft,
    signInAgain: true,
  };
}
