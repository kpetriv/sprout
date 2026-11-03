import { FieldValue } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import {
  approvalPayload,
  callablePayload,
  decodeBase64,
  displayFingerprint,
  parseDeviceKey,
  verifyEcdsaSha256,
} from '../crypto.js';
import { proofOf, verifyDeviceProof } from '../deviceProof.js';
import { lamportOf, maxLamport, nextLamport } from '../lamport.js';
import { auth, db } from '../firebase.js';
import { dataOf, parent, requireDeviceId, signedIn } from '../guards.js';
import { log } from '../log.js';
import { attestDevice, issueChallenge } from '../registration.js';
import { deviceDocument } from './registry.js';

/**
 * beginDeviceRegistration() → { challenge }: 32 random bytes (base64) for a key attestation, valid 10 minutes. Its only
 * effect is replacing `challenges/{uid}` (hash and expiry). The same challenge serves a device key (createHousehold,
 * registerParentDevice, redeemLinkCode) and a parent authorisation key (registerParentKey); one is outstanding per uid.
 */
export async function beginDeviceRegistration(request: CallableRequest): Promise<{ challenge: string }> {
  const caller = await signedIn(request, 'beginDeviceRegistration');
  const { challenge } = await issueChallenge(caller.uid);
  log.info('device.challengeIssued', { uid: caller.uid });
  return { challenge };
}

/**
 * registerParentDevice({ devicePublicKey, attestationChain? }) → { deviceId, fingerprint }: a second device of a parent
 * (same account) is registered PENDING until an ACTIVE parent device approves it (T8). Registering the same key again
 * while it is pending is idempotent.
 */
export async function registerParentDevice(
  request: CallableRequest,
): Promise<{ deviceId: string; fingerprint: string }> {
  const caller = await parent(request, 'registerParentDevice');
  const data = dataOf(request);
  const key = parseDeviceKey(data.devicePublicKey);
  const attestation = await attestDevice(caller.uid, data.attestationChain, key);
  const ref = db.doc(`households/${caller.hid}/devices/${key.deviceId}`);
  await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) {
      if (existing.get('uid') === caller.uid && existing.get('status') === 'PENDING') return;
      throw new HttpsError('already-exists', 'Device already registered');
    }
    tx.create(ref, deviceDocument(caller.uid, 'PARENT', key, attestation, 'PENDING'));
  });
  log.info('device.registered', { hid: caller.hid, deviceId: key.deviceId, status: 'PENDING', attestation });
  return { deviceId: key.deviceId, fingerprint: displayFingerprint(key.deviceId) };
}

/**
 * approveDevice({ deviceId, approverDeviceId, signature }) → { deviceId, status }: an ACTIVE parent device of the caller
 * approves a PENDING parent device by signing `"sprout/approve-device/v1" || 0x00 || deviceId` with its registered key
 * (ECDSA P-256, SHA-256, DER, base64).
 */
export async function approveDevice(request: CallableRequest): Promise<{ deviceId: string; status: 'ACTIVE' }> {
  const caller = await parent(request, 'approveDevice');
  const data = dataOf(request);
  const deviceId = requireDeviceId(data.deviceId, 'deviceId');
  const approverDeviceId = requireDeviceId(data.approverDeviceId, 'approverDeviceId');
  const signature = decodeBase64(data.signature, 'signature', 256);
  const devices = db.collection(`households/${caller.hid}/devices`);

  await db.runTransaction(async (tx) => {
    const [approver, target] = await Promise.all([tx.get(devices.doc(approverDeviceId)), tx.get(devices.doc(deviceId))]);
    if (
      !approver.exists ||
      approver.get('uid') !== caller.uid ||
      approver.get('status') !== 'ACTIVE' ||
      approver.get('role') !== 'PARENT'
    ) {
      throw new HttpsError('permission-denied', 'The approving device is not an active parent device');
    }
    const approverKey = parseDeviceKey(approver.get('publicKey'), 'approver key');
    if (!verifyEcdsaSha256(approverKey.key, approvalPayload(deviceId), signature)) {
      throw new HttpsError('permission-denied', 'Approval signature does not verify');
    }
    if (!target.exists || target.get('role') !== 'PARENT') throw new HttpsError('not-found', 'No such device');
    if (target.get('status') !== 'PENDING') throw new HttpsError('failed-precondition', 'Device is not pending');
    tx.update(target.ref, {
      status: 'ACTIVE',
      approvedBy: approverDeviceId,
      approvedAt: FieldValue.serverTimestamp(),
    });
  });
  log.info('device.approved', { hid: caller.hid, deviceId, approverDeviceId });
  return { deviceId, status: 'ACTIVE' };
}

/**
 * revokeDevice({ deviceId, callerDeviceId, signature }) → { deviceId, revokedAtLamport }: marks a device REVOKED (T7).
 *
 * The request is signed by `callerDeviceId`, an ACTIVE parent device of the caller other than the target, over
 * `"sprout/revoke-device/v1" || 0x00 || hid || 0x00 || {"deviceId":"…"}` (crypto.ts `callablePayload`).
 * `revokedAtLamport` is one above the highest lamport of everything the device wrote (its events and its documents in
 * every collection; 1 when it wrote nothing), read in the transaction: receivers keep its earlier writes and
 * quarantine anything at or above it. Every parent key registered from the device gets the same `revokedAtLamport`.
 * - A KID device: its anonymous user is disabled, its refresh tokens revoked and its membership removed.
 * - A PARENT device: the owner's refresh tokens are revoked and `members/{uid}.tokensValidAfter` is set to the
 *   revocation time (epoch seconds), so the rules and the callables refuse every session signed in before it (the
 *   caller's own included: it signs in again). `sessionsRevokedAt` on the device marks that step done, so a retry
 *   after a partial failure completes it without signing the parent out again.
 */
export async function revokeDevice(
  request: CallableRequest,
): Promise<{ deviceId: string; revokedAtLamport: number }> {
  const caller = await parent(request, 'revokeDevice');
  const data = dataOf(request);
  const deviceId = requireDeviceId(data.deviceId, 'deviceId');
  const proof = proofOf(data);
  if (deviceId === proof.deviceId) {
    throw new HttpsError('failed-precondition', 'A device cannot revoke itself');
  }
  const household = db.doc(`households/${caller.hid}`);
  const devices = household.collection('devices');
  const payload = callablePayload('revokeDevice', caller.hid, { deviceId });

  const outcome = await db.runTransaction(async (tx) => {
    const [own, target] = await Promise.all([tx.get(devices.doc(proof.deviceId)), tx.get(devices.doc(deviceId))]);
    verifyDeviceProof(own, caller, payload, proof.signature);
    if (!target.exists) throw new HttpsError('not-found', 'No such device');
    const uid = target.get('uid') as string;
    const role = target.get('role') as string;
    if (target.get('status') === 'REVOKED') {
      return {
        revokedAtLamport: lamportOf(target.get('revokedAtLamport')),
        uid,
        role,
        kidUid: undefined,
        sessionsPending: role === 'PARENT' && target.get('sessionsRevokedAt') === undefined,
        already: true,
      };
    }
    const revokedAtLamport = nextLamport(await maxLamport(tx, household, deviceId));
    const keys = await tx.get(household.collection('parentKeys').where('deviceId', '==', deviceId));
    const member = await tx.get(household.collection('members').doc(uid));

    tx.update(target.ref, { status: 'REVOKED', revokedAt: FieldValue.serverTimestamp(), revokedAtLamport });
    keys.docs.forEach((k) => tx.update(k.ref, { revokedAtLamport }));
    const kidUid = member.exists && member.get('role') === 'KID' ? uid : undefined;
    if (kidUid !== undefined) tx.delete(member.ref);
    return { revokedAtLamport, uid, role, kidUid, sessionsPending: role === 'PARENT', already: false };
  });

  if (outcome.kidUid !== undefined) {
    await auth.updateUser(outcome.kidUid, { disabled: true });
    await auth.revokeRefreshTokens(outcome.kidUid);
  }
  if (outcome.sessionsPending) {
    await revokeParentSessions(caller.hid, outcome.uid, deviceId);
  }
  log.info('device.revoked', {
    hid: caller.hid,
    deviceId,
    revokedAtLamport: outcome.revokedAtLamport,
    kidUserDisabled: outcome.kidUid !== undefined,
    parentSessionsRevoked: outcome.sessionsPending,
    alreadyRevoked: outcome.already,
  });
  return { deviceId, revokedAtLamport: outcome.revokedAtLamport };
}

/**
 * Revokes [uid]'s refresh tokens and mirrors the cut-off into `members/{uid}.tokensValidAfter` (epoch seconds, the
 * Auth backend's own `tokensValidAfterTime`), which the rules and parent() compare with the ID token's `auth_time`.
 */
async function revokeParentSessions(hid: string, uid: string, deviceId: string): Promise<void> {
  await auth.revokeRefreshTokens(uid);
  const user = await auth.getUser(uid);
  const fromAuth = user.tokensValidAfterTime ? Date.parse(user.tokensValidAfterTime) : NaN;
  const tokensValidAfter = Math.floor((Number.isFinite(fromAuth) ? fromAuth : Date.now()) / 1000);
  const member = db.doc(`households/${hid}/members/${uid}`);
  if ((await member.get()).exists) await member.update({ tokensValidAfter });
  await db.doc(`households/${hid}/devices/${deviceId}`).update({ sessionsRevokedAt: FieldValue.serverTimestamp() });
}
