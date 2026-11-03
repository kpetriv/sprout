import { FieldValue, Timestamp, type DocumentSnapshot } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import { LINK_CODE_TTL_MS, linkCodeKey, MAX_LIVE_LINK_CODES } from '../config.js';
import { linkCodeId, normaliseLinkCode, parseDeviceKey, randomLinkCode } from '../crypto.js';
import { requireDeviceProof } from '../deviceProof.js';
import { auth, db } from '../firebase.js';
import { dataOf, parent, requireId, signedIn } from '../guards.js';
import { log } from '../log.js';
import { hit, LIMITS } from '../rateLimit.js';
import { attestDevice } from '../registration.js';
import { encryptionKeyClaimOf, verifiedEncryptionKey } from '../encryptionKeys.js';
import { deviceDocument } from './registry.js';

/**
 * createLinkCode({ kidId, callerDeviceId, signature }) → { code, expiresAt }: an 8-character single-use code (10
 * minutes) a kid device redeems to join the household as that kid (T6). Signed by an ACTIVE parent device of the
 * caller over `"sprout/create-link-code/v2" || 0x00 || hid || 0x00 || sha256(raw ID token) || 0x00 ||
 * {"kidId":"…"}` (crypto.ts `callablePayload`). Stored only as
 * `linkCodes/{hmacSha256(LINK_CODE_HMAC_KEY, code)}`; the code is never logged. At most five live codes per household.
 */
export async function createLinkCode(request: CallableRequest): Promise<{ code: string; expiresAt: number }> {
  const caller = await parent(request, 'createLinkCode');
  const data = dataOf(request);
  const kidId = requireId(data.kidId, 'kidId');
  await requireDeviceProof(caller, data, 'createLinkCode', { kidId });
  const key = linkCodeKey();
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
    tx.create(db.doc(`linkCodes/${linkCodeId(code, key)}`), {
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
 * redeemLinkCode({ code, devicePublicKey, attestationChain?, encryptionKey?, encryptionKeySignature? }) →
 * { hid, kidId, deviceId }: an anonymous user without a household becomes the kid of the code, with the calling device
 * ACTIVE. The optional encryption key (M4 §1.1) is stored on the device row when its binding verifies against the
 * submitted device key for the code's household (checked in the transaction, before anything is written; otherwise
 * `permission-denied` and the code stays unused). The household is only known from the code, so a kid device normally
 * registers its key afterwards (registerEncryptionKey). Wrong, used and expired codes give the
 * same `not-found` answer. Attempts are limited per IP, per uid and per code id (its HMAC), and counted before the
 * code is looked up. The transaction creates `userHouseholds/{uid}`, so one uid never joins two households even
 * under concurrent redemptions.
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
  const codeRef = db.doc(`linkCodes/${linkCodeId(code, linkCodeKey())}`);
  await hit('redeemLinkCode', 'code', codeRef.id, LIMITS.redeemLinkCode.code);

  const user = await auth.getUser(caller.uid);
  if (user.customClaims !== undefined && Object.keys(user.customClaims).length > 0) {
    throw new HttpsError('failed-precondition', 'This device is already linked');
  }
  const key = parseDeviceKey(data.devicePublicKey);
  const keyClaim = encryptionKeyClaimOf(data);
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

  const { hid, kidId, withKey } = await db.runTransaction(async (tx) => {
    const snap = await tx.get(codeRef);
    if (!usable(snap)) throw new HttpsError('not-found', INVALID_CODE);
    const hid = snap.get('hid') as string;
    const kidId = snap.get('kidId') as string;
    const encryptionKey = keyClaim && verifiedEncryptionKey(keyClaim, hid, key.deviceId, key.key);
    const household = db.doc(`households/${hid}`);
    const deviceRef = household.collection('devices').doc(key.deviceId);
    const userRef = db.doc(`userHouseholds/${caller.uid}`);
    const [householdSnap, deviceSnap, userSnap] = await Promise.all([
      tx.get(household),
      tx.get(deviceRef),
      tx.get(userRef),
    ]);
    if (!householdSnap.exists) throw new HttpsError('not-found', INVALID_CODE);
    if (userSnap.exists) throw new HttpsError('failed-precondition', 'This device is already linked');
    if (deviceSnap.exists) throw new HttpsError('already-exists', 'Device already registered');
    tx.create(userRef, { hid });
    tx.update(codeRef, { used: true, usedAt: FieldValue.serverTimestamp(), usedBy: caller.uid });
    tx.create(household.collection('members').doc(caller.uid), {
      role: 'KID',
      kidId,
      addedAt: FieldValue.serverTimestamp(),
    });
    tx.create(deviceRef, deviceDocument(caller.uid, 'KID', key, attestation, 'ACTIVE', kidId, encryptionKey));
    return { hid, kidId, withKey: encryptionKey !== undefined };
  });
  await auth.setCustomUserClaims(caller.uid, { hid, role: 'KID', kidId });
  log.info('linkCode.redeemed', { hid, kidId, deviceId: key.deviceId, attestation, encryptionKey: withKey });
  return { hid, kidId, deviceId: key.deviceId };
}
