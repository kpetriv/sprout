/**
 * The session step of an account recovery (ADR-0013 §2, audit H1): revoking every session of the parent account after
 * the recovering phone was admitted, as revokeDevice does, and finishing it later when it failed. Kept apart from
 * src/recovery.ts so the device-proof checks can use it without importing the handlers.
 */
import { FieldValue, type DocumentSnapshot } from 'firebase-admin/firestore';
import { auth, db } from './firebase.js';
import { HttpsError } from './https.js';
import { log } from './log.js';

/**
 * The mark on a device admitted by the app's recovery while the account's sessions are still to be revoked
 * ([finishRecoverySessions]); recoverParentDevice, the recovered phone's next device-signed call and the operator's
 * `recover-household` finish it.
 */
export const SESSIONS_PENDING_FIELD = 'sessionsRevocationPending';

/**
 * Revokes the sessions of parent [uid] after a recovery, as revokeDevice does (handlers/devices.ts
 * `revokeParentSessions`): the account's refresh tokens once, `members/{uid}.tokensValidAfter` from Auth's cut-off
 * (never lowered), then `sessionsRevokedAt` on each of [revokedDeviceIds] and, last, the [SESSIONS_PENDING_FIELD] mark
 * of [admittedId] removed. Nothing is stamped before Auth answered, so a failure leaves the mark for a retry.
 */
export async function finishRecoverySessions(
  hid: string,
  uid: string,
  admittedId: string,
  revokedDeviceIds: readonly string[],
): Promise<void> {
  await auth.revokeRefreshTokens(uid);
  const user = await auth.getUser(uid);
  const fromAuth = user.tokensValidAfterTime ? Date.parse(user.tokensValidAfterTime) : NaN;
  const cutOff = Math.floor((Number.isFinite(fromAuth) ? fromAuth : Date.now()) / 1000);
  const member = db.doc(`households/${hid}/members/${uid}`);
  await db.runTransaction(async (tx) => {
    const row = await tx.get(member);
    if (!row.exists) return;
    const stored: unknown = row.get('tokensValidAfter');
    const previous = typeof stored === 'number' && Number.isFinite(stored) ? stored : 0;
    tx.update(member, { tokensValidAfter: Math.max(previous, cutOff) });
  });
  const devices = db.collection(`households/${hid}/devices`);
  for (const id of revokedDeviceIds) {
    await devices.doc(id).update({ sessionsRevokedAt: FieldValue.serverTimestamp() });
  }
  await devices.doc(admittedId).update({ [SESSIONS_PENDING_FIELD]: FieldValue.delete() });
}

/** Attempts of [finishRecoverySessionsWithRetry] within one request, and the pause before each retry (ms). */
export const SESSION_STEP_ATTEMPTS = 3;
export const SESSION_STEP_PAUSE_MS = 250;

/**
 * [finishRecoverySessions] tried up to [SESSION_STEP_ATTEMPTS] times with a short pause between attempts (a passing
 * Auth error should not leave the lost phones working); after the last failure it logs the error code and answers
 * `internal`, leaving the mark for a later finish.
 */
export async function finishRecoverySessionsWithRetry(
  hid: string,
  uid: string,
  admittedId: string,
  revokedDeviceIds: readonly string[],
): Promise<void> {
  for (let attempt = 1; ; attempt++) {
    try {
      await finishRecoverySessions(hid, uid, admittedId, revokedDeviceIds);
      return;
    } catch (error) {
      const code: unknown = (error as { code?: unknown } | null)?.code;
      log.error('device.recoverySessionsPending', {
        hid,
        deviceId: admittedId,
        attempt,
        error: typeof code === 'string' || typeof code === 'number' ? String(code) : 'unknown',
      });
      if (attempt >= SESSION_STEP_ATTEMPTS) throw new HttpsError('internal', 'INTERNAL');
      await new Promise((resolve) => setTimeout(resolve, SESSION_STEP_PAUSE_MS * attempt));
    }
  }
}

/** The method and the revoked device ids of `recoveries/{recoveryId}`; `internal` when the record is unusable. */
export async function recoveryRecordOf(
  hid: string,
  deviceId: string,
  recoveryId: unknown,
): Promise<{ id: string; method: 'PIN' | 'WAIT'; revoked: string[] }> {
  const record = typeof recoveryId === 'string' ? await db.doc(`recoveries/${recoveryId}`).get() : undefined;
  const method: unknown = record?.get('method');
  if (record === undefined || !record.exists || (method !== 'PIN' && method !== 'WAIT')) {
    log.error('device.recoveryRecordMissing', { hid, deviceId });
    throw new HttpsError('internal', 'INTERNAL');
  }
  const revoked = ((record.get('revokedDevices') as { deviceId?: unknown }[] | undefined) ?? [])
    .map((d) => d.deviceId)
    .filter((id): id is string => typeof id === 'string');
  return { id: record.id, method, revoked };
}

/**
 * Called where a device-signed request of an ACTIVE parent phone was verified (the device-proof checks of
 * deviceProof.ts `requireDeviceProof`, registerParentKey, registerEncryptionKey and setRecoveryPin): when [device] is a
 * phone the app's recovery admitted whose session revocation did not finish (`approvedBy: "recovery"` and the
 * [SESSIONS_PENDING_FIELD] mark), finishes it and answers `unauthenticated`, so the phone signs in again, as it does
 * after a successful restore. Any other device: nothing.
 */
export async function finishMarkedRecovery(device: DocumentSnapshot, hid: string, uid: string): Promise<void> {
  if (
    !device.exists ||
    device.get(SESSIONS_PENDING_FIELD) !== true ||
    device.get('approvedBy') !== 'recovery' ||
    device.get('status') !== 'ACTIVE' ||
    device.get('uid') !== uid
  ) {
    return;
  }
  const record = await recoveryRecordOf(hid, device.id, device.get('recoveryId'));
  await finishRecoverySessionsWithRetry(hid, uid, device.id, record.revoked);
  log.info('device.recoverySessionsFinished', { hid, deviceId: device.id, revokedDevices: record.revoked.length });
  throw new HttpsError('unauthenticated', 'Sign in again');
}
