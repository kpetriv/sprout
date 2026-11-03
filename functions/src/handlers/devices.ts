import { FieldValue } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import {
  approvalPayload,
  decodeBase64,
  displayFingerprint,
  parseDeviceKey,
  verifyEcdsaSha256,
} from '../crypto.js';
import { auth, db } from '../firebase.js';
import { dataOf, parent, requireDeviceId, signedIn } from '../guards.js';
import { log } from '../log.js';
import { attestDevice, issueChallenge } from '../registration.js';
import { deviceDocument } from './registry.js';

/** beginDeviceRegistration() → { challenge }: 32 random bytes (base64) for the key attestation, valid 10 minutes. */
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
 * revokeDevice({ deviceId, callerDeviceId }) → { deviceId, revokedAtLamport }: marks a device REVOKED (T7).
 * `revokedAtLamport` is one above the highest lamport of the device's stored events (1 when it has none): receivers
 * keep its earlier events and quarantine anything at or above it. Every parent key registered from the device gets
 * the same `revokedAtLamport`. A revoked kid device's anonymous user is disabled and its membership removed. A parent
 * cannot revoke the device making the request (`callerDeviceId`, which must be one of the caller's active devices).
 */
export async function revokeDevice(
  request: CallableRequest,
): Promise<{ deviceId: string; revokedAtLamport: number }> {
  const caller = await parent(request, 'revokeDevice');
  const data = dataOf(request);
  const deviceId = requireDeviceId(data.deviceId, 'deviceId');
  const callerDeviceId = requireDeviceId(data.callerDeviceId, 'callerDeviceId');
  if (deviceId === callerDeviceId) {
    throw new HttpsError('failed-precondition', 'A device cannot revoke itself');
  }
  const household = db.doc(`households/${caller.hid}`);
  const devices = household.collection('devices');

  const outcome = await db.runTransaction(async (tx) => {
    const [own, target] = await Promise.all([tx.get(devices.doc(callerDeviceId)), tx.get(devices.doc(deviceId))]);
    if (!own.exists || own.get('uid') !== caller.uid || own.get('status') !== 'ACTIVE') {
      throw new HttpsError('permission-denied', 'callerDeviceId is not an active device of the caller');
    }
    if (!target.exists) throw new HttpsError('not-found', 'No such device');
    if (target.get('status') === 'REVOKED') {
      return { revokedAtLamport: target.get('revokedAtLamport') as number, kidUid: undefined, already: true };
    }
    const latest = await tx.get(
      household.collection('events').where('authorDeviceId', '==', deviceId).orderBy('lamport', 'desc').limit(1),
    );
    const maxLamport = latest.empty ? 0 : Number(latest.docs[0].get('lamport') ?? 0);
    const revokedAtLamport = maxLamport + 1;
    const keys = await tx.get(household.collection('parentKeys').where('deviceId', '==', deviceId));
    const uid = target.get('uid') as string;
    const member = await tx.get(household.collection('members').doc(uid));

    tx.update(target.ref, { status: 'REVOKED', revokedAt: FieldValue.serverTimestamp(), revokedAtLamport });
    keys.docs.forEach((k) => tx.update(k.ref, { revokedAtLamport }));
    const kidUid = member.exists && member.get('role') === 'KID' ? uid : undefined;
    if (kidUid !== undefined) tx.delete(member.ref);
    return { revokedAtLamport, kidUid, already: false };
  });

  if (outcome.kidUid !== undefined) {
    await auth.updateUser(outcome.kidUid, { disabled: true });
    await auth.revokeRefreshTokens(outcome.kidUid);
  }
  log.info('device.revoked', {
    hid: caller.hid,
    deviceId,
    revokedAtLamport: outcome.revokedAtLamport,
    kidUserDisabled: outcome.kidUid !== undefined,
    alreadyRevoked: outcome.already,
  });
  return { deviceId, revokedAtLamport: outcome.revokedAtLamport };
}
