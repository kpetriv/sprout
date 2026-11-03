import { FieldValue, Timestamp, type DocumentSnapshot } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { LINK_CODE_TTL_MS, MAX_LIVE_LINK_CODES } from '../config.js';
import { linkCodeId, normaliseLinkCode, parseDeviceKey, randomLinkCode } from '../crypto.js';
import { auth, db } from '../firebase.js';
import { dataOf, parent, requireId, signedIn } from '../guards.js';
import { log } from '../log.js';
import { hit, LIMITS } from '../rateLimit.js';
import { attestDevice } from '../registration.js';
import { deviceDocument } from './registry.js';

/**
 * createLinkCode({ kidId }) → { code, expiresAt }: an 8-character single-use code (10 minutes) a kid device redeems to
 * join the household as that kid (T6). Stored only as `linkCodes/{sha256(code)}`; the code is never logged. At most
 * five live codes per household.
 */
export async function createLinkCode(request: CallableRequest): Promise<{ code: string; expiresAt: number }> {
  const caller = await parent(request, 'createLinkCode');
  const kidId = requireId(dataOf(request).kidId, 'kidId');
  const kid = await db.doc(`households/${caller.hid}/kids/${kidId}`).get();
  if (!kid.exists || kid.get('deleted') === true) throw new HttpsError('not-found', 'No such kid');

  const now = Date.now();
  const code = randomLinkCode();
  const expiresAt = now + LINK_CODE_TTL_MS;
  const live = await db.runTransaction(async (tx) => {
    const outstanding = await tx.get(
      db
        .collection('linkCodes')
        .where('hid', '==', caller.hid)
        .where('used', '==', false)
        .where('expiresAt', '>', Timestamp.fromMillis(now)),
    );
    if (outstanding.size >= MAX_LIVE_LINK_CODES) {
      throw new HttpsError('resource-exhausted', 'Too many active link codes; wait for one to expire');
    }
    tx.create(db.doc(`linkCodes/${linkCodeId(code)}`), {
      hid: caller.hid,
      kidId,
      createdBy: caller.uid,
      createdAt: FieldValue.serverTimestamp(),
      expiresAt: Timestamp.fromMillis(expiresAt),
      used: false,
    });
    return outstanding.size + 1;
  });
  log.info('linkCode.created', { hid: caller.hid, kidId, live });
  return { code, expiresAt };
}

const INVALID_CODE = 'The code is invalid or has expired';

/**
 * redeemLinkCode({ code, devicePublicKey, attestationChain? }) → { hid, kidId, deviceId }: an anonymous user without
 * a household becomes the kid of the code, with the calling device ACTIVE. Wrong, used and expired codes give the
 * same `not-found` answer. Attempts are limited per IP, per uid and per code hash, and counted before the code is
 * looked up.
 */
export async function redeemLinkCode(
  request: CallableRequest,
): Promise<{ hid: string; kidId: string; deviceId: string }> {
  const caller = await signedIn(request, 'redeemLinkCode');
  if (!caller.anonymous) throw new HttpsError('permission-denied', 'Only a kid device can redeem a link code');
  if (caller.hid !== undefined || caller.role !== undefined) {
    throw new HttpsError('failed-precondition', 'This device is already linked');
  }
  const data = dataOf(request);
  const code = normaliseLinkCode(data.code);
  if (code === null) throw new HttpsError('invalid-argument', 'The code must be 8 letters and digits');
  const codeRef = db.doc(`linkCodes/${linkCodeId(code)}`);
  await hit('redeemLinkCode', 'code', codeRef.id, LIMITS.redeemLinkCode.code);

  const user = await auth.getUser(caller.uid);
  if (user.customClaims !== undefined && Object.keys(user.customClaims).length > 0) {
    throw new HttpsError('failed-precondition', 'This device is already linked');
  }
  const key = parseDeviceKey(data.devicePublicKey);
  const usable = (snap: DocumentSnapshot) =>
    snap.exists &&
    snap.get('used') === false &&
    (snap.get('expiresAt') as Timestamp | undefined)?.toMillis() !== undefined &&
    (snap.get('expiresAt') as Timestamp).toMillis() > Date.now();
  if (!usable(await codeRef.get())) {
    log.info('linkCode.rejected', { uid: caller.uid });
    throw new HttpsError('not-found', INVALID_CODE);
  }
  const attestation = await attestDevice(caller.uid, data.attestationChain, key);

  const { hid, kidId } = await db.runTransaction(async (tx) => {
    const snap = await tx.get(codeRef);
    if (!usable(snap)) throw new HttpsError('not-found', INVALID_CODE);
    const hid = snap.get('hid') as string;
    const kidId = snap.get('kidId') as string;
    const household = db.doc(`households/${hid}`);
    const deviceRef = household.collection('devices').doc(key.deviceId);
    const [householdSnap, deviceSnap] = await Promise.all([tx.get(household), tx.get(deviceRef)]);
    if (!householdSnap.exists) throw new HttpsError('not-found', INVALID_CODE);
    if (deviceSnap.exists) throw new HttpsError('already-exists', 'Device already registered');
    tx.update(codeRef, { used: true, usedAt: FieldValue.serverTimestamp(), usedBy: caller.uid });
    tx.create(household.collection('members').doc(caller.uid), {
      role: 'KID',
      kidId,
      addedAt: FieldValue.serverTimestamp(),
    });
    tx.create(deviceRef, deviceDocument(caller.uid, 'KID', key, attestation, 'ACTIVE', kidId));
    return { hid, kidId };
  });
  await auth.setCustomUserClaims(caller.uid, { hid, role: 'KID', kidId });
  log.info('linkCode.redeemed', { hid, kidId, deviceId: key.deviceId, attestation });
  return { hid, kidId, deviceId: key.deviceId };
}
