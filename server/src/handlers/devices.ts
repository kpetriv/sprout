import {
  FieldValue,
  type DocumentReference,
  type DocumentSnapshot,
  type Transaction,
} from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import {
  callablePayload,
  decodeBase64,
  deviceStatusPayload,
  displayFingerprint,
  parseDeviceKey,
  sha256Hex,
  verifyEcdsaSha256,
} from '../crypto.js';
import { proofOf, verifyDeviceProof } from '../deviceProof.js';
import { lamportOf, maxLamport, nextLamport } from '../lamport.js';
import { auth, db } from '../firebase.js';
import { clientIp, dataOf, parent, requireAppCheck, requireDeviceId, requireId, signedIn } from '../guards.js';
import { log } from '../log.js';
import { hit, LIMITS, noIpLimit } from '../rateLimit.js';
import { attestDevice, issueChallenge } from '../registration.js';
import { encryptionKeyClaimOf, requireKeyNotBoundElsewhere, verifiedEncryptionKey } from '../encryptionKeys.js';
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
 * registerParentDevice({ devicePublicKey, attestationChain?, encryptionKey?, encryptionKeySignature? }) →
 * { deviceId, fingerprint }: a second device of a parent (same account) is registered PENDING until an ACTIVE parent
 * device approves it (T8). Registering the same key again while it is pending is idempotent. The optional encryption
 * key (M4 §1.1) must be bound by the submitted device key to the caller's household and this device id
 * (`permission-denied` otherwise) and must not be held by another device row of the household (`failed-precondition`,
 * D-REL-27); a pending retry adds it when the row has none yet.
 */
export async function registerParentDevice(
  request: CallableRequest,
): Promise<{ deviceId: string; fingerprint: string }> {
  const caller = await parent(request, 'registerParentDevice');
  const data = dataOf(request);
  const key = parseDeviceKey(data.devicePublicKey);
  const keyClaim = encryptionKeyClaimOf(data);
  const encryptionKey = keyClaim && verifiedEncryptionKey(keyClaim, caller.hid, key.deviceId, key.key);
  const attestation = await attestDevice(caller.uid, data.attestationChain, key);
  const ref = db.doc(`households/${caller.hid}/devices/${key.deviceId}`);
  await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) {
      if (existing.get('uid') === caller.uid && existing.get('status') === 'PENDING') {
        if (encryptionKey !== undefined && existing.get('encryptionKey') === undefined) {
          await requireKeyNotBoundElsewhere(tx, caller.hid, key.deviceId, encryptionKey.encryptionKey);
          tx.update(ref, { ...encryptionKey });
        }
        return;
      }
      throw new HttpsError('already-exists', 'Device already registered');
    }
    if (encryptionKey !== undefined) {
      await requireKeyNotBoundElsewhere(tx, caller.hid, key.deviceId, encryptionKey.encryptionKey);
    }
    tx.create(ref, deviceDocument(caller.uid, 'PARENT', key, attestation, 'PENDING', undefined, encryptionKey));
  });
  log.info('device.registered', {
    hid: caller.hid,
    deviceId: key.deviceId,
    status: 'PENDING',
    attestation,
    encryptionKey: encryptionKey !== undefined,
  });
  return { deviceId: key.deviceId, fingerprint: displayFingerprint(key.deviceId) };
}

/**
 * approveDevice({ deviceId, approverDeviceId, signature }) → { deviceId, status }: an ACTIVE parent device of the caller
 * ([approverDeviceId]) approves a PENDING parent device. `signature` is the approver key's ECDSA P-256/SHA-256 DER
 * signature (base64) over `"sprout/approve-device/v2" || 0x00 || hid || 0x00 || sha256(raw ID token) || 0x00 ||
 * {"deviceId":"…"}` (crypto.ts `callablePayload`), so it is valid only with the ID token of this request.
 */
export async function approveDevice(request: CallableRequest): Promise<{ deviceId: string; status: 'ACTIVE' }> {
  const caller = await parent(request, 'approveDevice');
  const data = dataOf(request);
  const deviceId = requireDeviceId(data.deviceId, 'deviceId');
  const proof = proofOf(data, 'approverDeviceId');
  const devices = db.collection(`households/${caller.hid}/devices`);
  const payload = callablePayload('approveDevice', caller.hid, caller.idTokenHash, { deviceId });

  await db.runTransaction(async (tx) => {
    const [approver, target] = await Promise.all([tx.get(devices.doc(proof.deviceId)), tx.get(devices.doc(deviceId))]);
    verifyDeviceProof(approver, caller, payload, proof.signature);
    if (!target.exists || target.get('role') !== 'PARENT') throw new HttpsError('not-found', 'No such device');
    if (target.get('status') !== 'PENDING') throw new HttpsError('failed-precondition', 'Device is not pending');
    tx.update(target.ref, {
      status: 'ACTIVE',
      approvedBy: proof.deviceId,
      approvedAt: FieldValue.serverTimestamp(),
    });
  });
  log.info('device.approved', { hid: caller.hid, deviceId, approverDeviceId: proof.deviceId });
  return { deviceId, status: 'ACTIVE' };
}

/**
 * revokeDevice({ deviceId, callerDeviceId, signature }) → { deviceId, revokedAtLamport }: marks a device REVOKED (T7).
 *
 * The request is signed by `callerDeviceId`, an ACTIVE parent device of the caller other than the target, over
 * `"sprout/revoke-device/v2" || 0x00 || hid || 0x00 || sha256(raw ID token) || 0x00 || {"deviceId":"…"}` (crypto.ts
 * `callablePayload`).
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
  const payload = callablePayload('revokeDevice', caller.hid, caller.idTokenHash, { deviceId });

  const outcome = await db.runTransaction(async (tx) => {
    const [own, target] = await Promise.all([tx.get(devices.doc(proof.deviceId)), tx.get(devices.doc(deviceId))]);
    verifyDeviceProof(own, caller, payload, proof.signature);
    if (!target.exists) throw new HttpsError('not-found', 'No such device');
    const plan = await planRevocation(tx, household, target);
    return applyRevocation(tx, plan);
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

/** What revoking one device does, read inside a transaction by [planRevocation] and written by [applyRevocation]. */
export interface RevocationPlan {
  readonly target: DocumentSnapshot;
  readonly uid: string;
  readonly role: string;
  /** True when the device is already REVOKED: nothing is written, only a pending session revocation is completed. */
  readonly already: boolean;
  readonly revokedAtLamport: number;
  readonly parentKeys: readonly DocumentReference[];
  /** The kid member row to delete (a KID device), else undefined. */
  readonly kidMember: DocumentReference | undefined;
}

/** The outcome of [applyRevocation]: what the caller still does outside the transaction. */
export interface RevocationOutcome {
  readonly revokedAtLamport: number;
  readonly uid: string;
  readonly role: string;
  /** The kid's uid whose user is to be disabled and its tokens revoked, else undefined. */
  readonly kidUid: string | undefined;
  /** True when the owner's parent sessions are still to be revoked ([revokeParentSessions]). */
  readonly sessionsPending: boolean;
  readonly already: boolean;
}

/**
 * Reads, inside [tx], everything revoking the existing device row [target] of [household] needs: its
 * `revokedAtLamport` (one above the highest lamport it wrote, [maxLamport]), its parent keys and, for a kid device,
 * the member row. Writes nothing, so several devices can be planned before the first write of one transaction (the
 * operator's `recover-household`).
 */
export async function planRevocation(
  tx: Transaction,
  household: DocumentReference,
  target: DocumentSnapshot,
): Promise<RevocationPlan> {
  const uid = target.get('uid') as string;
  const role = target.get('role') as string;
  if (target.get('status') === 'REVOKED') {
    return {
      target,
      uid,
      role,
      already: true,
      revokedAtLamport: lamportOf(target.get('revokedAtLamport')),
      parentKeys: [],
      kidMember: undefined,
    };
  }
  const revokedAtLamport = nextLamport(await maxLamport(tx, household, target.id));
  const keys = await tx.get(household.collection('parentKeys').where('deviceId', '==', target.id));
  const member = await tx.get(household.collection('members').doc(uid));
  const kidMember = member.exists && member.get('role') === 'KID' ? member.ref : undefined;
  return { target, uid, role, already: false, revokedAtLamport, parentKeys: keys.docs.map((k) => k.ref), kidMember };
}

/** Writes [plan] in [tx]: the device REVOKED, its parent keys stamped, a kid's member row deleted. */
export function applyRevocation(tx: Transaction, plan: RevocationPlan): RevocationOutcome {
  if (plan.already) {
    return {
      revokedAtLamport: plan.revokedAtLamport,
      uid: plan.uid,
      role: plan.role,
      kidUid: undefined,
      sessionsPending: plan.role === 'PARENT' && plan.target.get('sessionsRevokedAt') === undefined,
      already: true,
    };
  }
  const { revokedAtLamport } = plan;
  tx.update(plan.target.ref, { status: 'REVOKED', revokedAt: FieldValue.serverTimestamp(), revokedAtLamport });
  plan.parentKeys.forEach((k) => tx.update(k, { revokedAtLamport }));
  if (plan.kidMember !== undefined) tx.delete(plan.kidMember);
  return {
    revokedAtLamport,
    uid: plan.uid,
    role: plan.role,
    kidUid: plan.kidMember === undefined ? undefined : plan.uid,
    sessionsPending: plan.role === 'PARENT',
    already: false,
  };
}

/**
 * Revokes [uid]'s refresh tokens and mirrors the cut-off into `members/{uid}.tokensValidAfter` (epoch seconds, the
 * Auth backend's own `tokensValidAfterTime`), which the rules and parent() compare with the ID token's `auth_time`.
 */
export async function revokeParentSessions(hid: string, uid: string, deviceId: string): Promise<void> {
  await auth.revokeRefreshTokens(uid);
  const user = await auth.getUser(uid);
  const fromAuth = user.tokensValidAfterTime ? Date.parse(user.tokensValidAfterTime) : NaN;
  const tokensValidAfter = Math.floor((Number.isFinite(fromAuth) ? fromAuth : Date.now()) / 1000);
  const member = db.doc(`households/${hid}/members/${uid}`);
  if ((await member.get()).exists) await member.update({ tokensValidAfter });
  await db.doc(`households/${hid}/devices/${deviceId}`).update({ sessionsRevokedAt: FieldValue.serverTimestamp() });
}

export type DeviceStatusValue = 'ACTIVE' | 'PENDING' | 'REVOKED' | 'UNKNOWN';

/**
 * deviceStatus({ hid, deviceId, signature }) → { status, householdGone }: a device asks for its own registry status
 * without an ID token (the adapter ignores any Authorization header of this callable; App Check is still required
 * outside the emulator). A revoked kid device's user is disabled and a deleted household's users are deleted, so such
 * a device can no longer sign in; this is how it learns why (audit H2).
 *
 * `signature` is the device key's ECDSA P-256/SHA-256 DER signature (base64) over
 * `"sprout/device-status/v1" || 0x00 || hid || 0x00 || deviceId` (crypto.ts `deviceStatusPayload`).
 * - No household or no such device: `{ status: "UNKNOWN", householdGone }`, `householdGone` true only when a
 *   `deletionRecords` row for the household exists (its `hidHash`). Nothing is verified then: there is no key.
 * - Otherwise the signature must verify with the stored public key (`permission-denied` if not), whatever the status,
 *   and the answer is `{ status, householdGone: false }`.
 *
 * Nothing else is revealed (no role, uid, lamport or timestamps). Every call counts against its IP (or the shared
 * no-address bucket) before anything is read; the per-device bucket, keyed on (hid, deviceId), counts only requests
 * whose signature verified, so an unsigned flood cannot lock a device out of its own status check. An UNKNOWN answer
 * is unsigned and verifies nothing (docs/FIREBASE.md says what it reveals).
 */
export async function deviceStatus(
  request: CallableRequest,
): Promise<{ status: DeviceStatusValue; householdGone: boolean }> {
  requireAppCheck(request);
  const data = dataOf(request);
  const hid = requireId(data.hid, 'hid');
  const deviceId = requireDeviceId(data.deviceId, 'deviceId');
  const signature = decodeBase64(data.signature, 'signature', 256);
  const ip = clientIp(request);
  if (ip === null) {
    await hit('deviceStatus', 'noip', 'shared', noIpLimit('deviceStatus'));
  } else {
    await hit('deviceStatus', 'ip', ip, LIMITS.deviceStatus.ip);
  }

  const [household, device] = await Promise.all([
    db.doc(`households/${hid}`).get(),
    db.doc(`households/${hid}/devices/${deviceId}`).get(),
  ]);
  if (!household.exists || !device.exists) {
    const hidHash = sha256Hex(hid);
    const records = await db.collection('deletionRecords').where('hidHash', '==', hidHash).limit(1).get();
    const householdGone = !records.empty;
    log.info('device.statusChecked', { deviceId, status: 'UNKNOWN', householdGone });
    return { status: 'UNKNOWN', householdGone };
  }
  const key = parseDeviceKey(device.get('publicKey'), 'device key');
  if (!verifyEcdsaSha256(key.key, deviceStatusPayload(hid, deviceId), signature)) {
    log.warn('device.statusRefused', { deviceId });
    throw new HttpsError('permission-denied', 'Device signature does not verify');
  }
  // Counted only for a verified request, so nobody without the key can use up a device's allowance.
  await hit('deviceStatus', 'device', `${hid}|${deviceId}`, LIMITS.deviceStatus.device);
  const stored: unknown = device.get('status');
  const status: DeviceStatusValue = stored === 'ACTIVE' || stored === 'PENDING' || stored === 'REVOKED' ? stored : 'UNKNOWN';
  log.info('device.statusChecked', { hid, deviceId, status, householdGone: false });
  return { status, householdGone: false };
}
