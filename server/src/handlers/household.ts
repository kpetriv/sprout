import { DocumentSnapshot, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import { DOCUMENT_COLLECTIONS, MAX_EXPORT_BYTES, RECENT_SIGN_IN_MS } from '../config.js';
import { parseDeviceKey, sha256Hex } from '../crypto.js';
import { requireDeviceProof } from '../deviceProof.js';
import { auth, db } from '../firebase.js';
import { dataOf, parent, signedIn } from '../guards.js';
import { log } from '../log.js';
import { attestDevice } from '../registration.js';
import { deviceDocument } from './registry.js';

const CURRENCY = /^[A-Z]{3}$/;

/**
 * createHousehold({ fiatCurrency, devicePublicKey, attestationChain? }) → { hid, deviceId }. The caller becomes the
 * household's first parent and the calling device its first ACTIVE device. The batch also creates
 * `userHouseholds/{uid}`, so two concurrent calls from one uid cannot both create a household (D-M3-20).
 */
export async function createHousehold(request: CallableRequest): Promise<{ hid: string; deviceId: string }> {
  const caller = await signedIn(request, 'createHousehold');
  if (caller.anonymous) throw new HttpsError('permission-denied', 'A parent account is required');
  if (caller.hid !== undefined) throw new HttpsError('already-exists', 'Already a member of a household');
  const user = await auth.getUser(caller.uid);
  if (user.customClaims?.hid !== undefined) throw new HttpsError('already-exists', 'Already a member of a household');

  const data = dataOf(request);
  const fiatCurrency = data.fiatCurrency;
  if (typeof fiatCurrency !== 'string' || !CURRENCY.test(fiatCurrency)) {
    throw new HttpsError('invalid-argument', 'fiatCurrency must be an ISO 4217 code');
  }
  const key = parseDeviceKey(data.devicePublicKey);
  const attestation = await attestDevice(caller.uid, data.attestationChain, key);

  const household = db.collection('households').doc();
  const hid = household.id;
  const batch = db.batch();
  batch.create(household, { createdBy: caller.uid, createdAt: FieldValue.serverTimestamp(), fiatCurrency });
  batch.create(db.doc(`userHouseholds/${caller.uid}`), { hid });
  batch.create(household.collection('members').doc(caller.uid), {
    role: 'PARENT',
    addedAt: FieldValue.serverTimestamp(),
  });
  batch.create(
    household.collection('devices').doc(key.deviceId),
    deviceDocument(caller.uid, 'PARENT', key, attestation, 'ACTIVE'),
  );
  try {
    await batch.commit();
  } catch (e) {
    // gRPC ALREADY_EXISTS: userHouseholds/{uid} was created by a concurrent call (or the uid is linked elsewhere).
    if ((e as { code?: unknown }).code === 6) throw new HttpsError('already-exists', 'Already a member of a household');
    throw e;
  }
  await auth.setCustomUserClaims(caller.uid, { hid, role: 'PARENT' });
  log.info('household.created', { hid, deviceId: key.deviceId, attestation });
  return { hid, deviceId: key.deviceId };
}

/** Firestore values as JSON: timestamps become ISO-8601 strings; everything else is already plain. */
function plain(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toDate().toISOString();
  if (Array.isArray(value)) return value.map(plain);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)]));
  }
  return value;
}

function row(doc: DocumentSnapshot): Record<string, unknown> {
  return { id: doc.id, ...(plain(doc.data() ?? {}) as Record<string, unknown>) };
}

export interface ExportBundle {
  exportedAt: string;
  household: Record<string, unknown>;
  members: Record<string, unknown>[];
  devices: Record<string, unknown>[];
  parentKeys: Record<string, unknown>[];
  collections: Record<string, Record<string, unknown>[]>;
  events: Record<string, unknown>[];
}

/**
 * exportHousehold({ callerDeviceId, signature }) → the household's data as one JSON bundle (right of access and
 * portability, PRIVACY.md; D-M3-7). Signed by an ACTIVE parent device over
 * `"sprout/export-household/v1" || 0x00 || hid || 0x00 || {}`. Refused with `resource-exhausted` above 4 MB
 * (config.ts `MAX_EXPORT_BYTES`), below the host's 4.5 MB response limit.
 */
export async function exportHousehold(request: CallableRequest): Promise<ExportBundle> {
  const caller = await parent(request, 'exportHousehold');
  await requireDeviceProof(caller, dataOf(request), 'exportHousehold', {});
  const ref = db.doc(`households/${caller.hid}`);
  const names = Object.keys(DOCUMENT_COLLECTIONS);
  const [household, members, devices, parentKeys, events, ...documents] = await Promise.all([
    ref.get(),
    ref.collection('members').get(),
    ref.collection('devices').get(),
    ref.collection('parentKeys').get(),
    ref.collection('events').orderBy('lamport').get(),
    ...names.map((name) => ref.collection(name).get()),
  ]);
  const collections: Record<string, Record<string, unknown>[]> = {};
  names.forEach((name, i) => {
    collections[name] = documents[i].docs.map(row);
  });
  const bundle: ExportBundle = {
    exportedAt: new Date().toISOString(),
    household: row(household),
    members: members.docs.map(row),
    devices: devices.docs.map(row),
    parentKeys: parentKeys.docs.map(row),
    collections,
    events: events.docs.map(row),
  };
  const bytes = Buffer.byteLength(JSON.stringify(bundle), 'utf8');
  const documentCount = documents.reduce((n, snap) => n + snap.size, 0);
  if (bytes > MAX_EXPORT_BYTES) {
    log.warn('household.export.tooLarge', { hid: caller.hid, bytes });
    throw new HttpsError('resource-exhausted', 'The household is too large to export in one response');
  }
  log.info('household.exported', { hid: caller.hid, events: events.size, documents: documentCount, bytes });
  return bundle;
}

/**
 * deleteHousehold({ confirmation: hid, callerDeviceId, signature }) → { deleted: true }. Signed by an ACTIVE parent
 * device over `"sprout/delete-household/v1" || 0x00 || hid || 0x00 || {"confirmation":"<hid>"}`, from a session signed
 * in within the last 5 minutes (ID token `auth_time`; otherwise `failed-precondition`, sign in again).
 *
 * Deletes the `members` rows first (the rules key every client permission off them, so clients lose access before
 * anything else goes), then `households/{hid}` recursively, the household's outstanding link codes, and for every uid
 * that was a member or owns a registered device (a revoked kid's member row is already gone): its registration
 * challenge, its `userHouseholds` row and its Auth user (kids and parents, the caller included). Finally records the
 * deletion as `deletionRecords/{autoId}` with a hash of the id and counts only (right to erasure, PRIVACY.md).
 */
export async function deleteHousehold(request: CallableRequest): Promise<{ deleted: true }> {
  const caller = await parent(request, 'deleteHousehold');
  const data = dataOf(request);
  if (data.confirmation !== caller.hid) {
    throw new HttpsError('invalid-argument', 'Confirmation does not match the household');
  }
  await requireDeviceProof(caller, data, 'deleteHousehold', { confirmation: caller.hid });
  if (Date.now() - caller.authTime * 1000 > RECENT_SIGN_IN_MS) {
    throw new HttpsError('failed-precondition', 'Sign in again to delete the household');
  }
  const hid = caller.hid;
  const ref = db.doc(`households/${hid}`);
  const [members, deviceRows] = await Promise.all([ref.collection('members').get(), ref.collection('devices').get()]);
  const count = async (name: string) => (await ref.collection(name).count().get()).data().count;
  const [events, parentKeys, ...documents] = await Promise.all([
    count('events'),
    count('parentKeys'),
    ...Object.keys(DOCUMENT_COLLECTIONS).map(count),
  ]);
  const counts = {
    members: members.size,
    devices: deviceRows.size,
    events,
    documents: parentKeys + documents.reduce((a, b) => a + b, 0),
  };
  const uids = [
    ...new Set([
      ...members.docs.map((m) => m.id),
      ...deviceRows.docs.map((d) => d.get('uid') as unknown).filter((u): u is string => typeof u === 'string'),
    ]),
  ];

  const memberWriter = db.bulkWriter();
  members.docs.forEach((m) => void memberWriter.delete(m.ref));
  await memberWriter.close();

  await db.recursiveDelete(ref);

  const writer = db.bulkWriter();
  const codes = await db.collection('linkCodes').where('hid', '==', hid).get();
  codes.docs.forEach((d) => void writer.delete(d.ref));
  uids.forEach((uid) => {
    void writer.delete(db.doc(`challenges/${uid}`));
    void writer.delete(db.doc(`userHouseholds/${uid}`));
  });
  await writer.close();

  const result = uids.length > 0 ? await auth.deleteUsers(uids) : { successCount: 0, failureCount: 0 };

  const hidHash = sha256Hex(hid);
  await db.collection('deletionRecords').add({ hidHash, at: FieldValue.serverTimestamp(), counts });
  log.info('household.deleted', {
    hidHash,
    ...counts,
    usersDeleted: result.successCount,
    userDeleteFailures: result.failureCount,
  });
  return { deleted: true };
}
