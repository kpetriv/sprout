import { DocumentSnapshot, FieldPath, FieldValue, Timestamp, type DocumentReference } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import {
  DOCUMENT_COLLECTIONS,
  EXPORT_PAGE_SIZE,
  MAX_EXPORT_BYTES,
  MAX_EXPORT_MESSAGES,
  RECENT_SIGN_IN_MS,
} from '../config.js';
import { parseDeviceKey, sha256Hex } from '../crypto.js';
import { requireDeviceProof } from '../deviceProof.js';
import { auth, db } from '../firebase.js';
import { dataOf, parent, signedIn } from '../guards.js';
import { verifiedEncryptionKey, encryptionKeyClaimOf } from '../encryptionKeys.js';
import { log } from '../log.js';
import { attestDevice } from '../registration.js';
import { deviceDocument } from './registry.js';

const CURRENCY = /^[A-Z]{3}$/;

/** The stored fields of a blob chunk except `data` (core/domain-ledger BlobChunkHeader plus the server stamp). */
const BLOB_HEADER_FIELDS = [
  'schemaVersion',
  'householdId',
  'blobId',
  'threadId',
  'kidId',
  'submissionId',
  'authorUid',
  'authorDeviceId',
  'lamport',
  'chunk',
  'chunks',
  'sha256',
  'epoch',
  'signature',
  'serverReceivedAt',
];

/**
 * createHousehold({ fiatCurrency, devicePublicKey, attestationChain?, encryptionKey?, encryptionKeySignature? }) →
 * { hid, deviceId }. The caller becomes the household's first parent and the calling device its first ACTIVE device.
 * The batch also creates `userHouseholds/{uid}`, so two concurrent calls from one uid cannot both create a household
 * (D-M3-20).
 *
 * The optional encryption key (M4 §1.1) is stored on the device row only if its binding verifies against the
 * submitted device key for the new household id; otherwise `permission-denied` and nothing is created. The id is
 * assigned here, so a client normally registers its key afterwards (registerEncryptionKey).
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
  const keyClaim = encryptionKeyClaimOf(data);
  const household = db.collection('households').doc();
  const hid = household.id;
  const encryptionKey = keyClaim && verifiedEncryptionKey(keyClaim, hid, key.deviceId, key.key);
  const attestation = await attestDevice(caller.uid, data.attestationChain, key);

  const batch = db.batch();
  batch.create(household, { createdBy: caller.uid, createdAt: FieldValue.serverTimestamp(), fiatCurrency });
  batch.create(db.doc(`userHouseholds/${caller.uid}`), { hid });
  batch.create(household.collection('members').doc(caller.uid), {
    role: 'PARENT',
    addedAt: FieldValue.serverTimestamp(),
  });
  batch.create(
    household.collection('devices').doc(key.deviceId),
    deviceDocument(caller.uid, 'PARENT', key, attestation, 'ACTIVE', undefined, encryptionKey),
  );
  try {
    await batch.commit();
  } catch (e) {
    // gRPC ALREADY_EXISTS: userHouseholds/{uid} was created by a concurrent call (or the uid is linked elsewhere).
    if ((e as { code?: unknown }).code === 6) throw new HttpsError('already-exists', 'Already a member of a household');
    throw e;
  }
  await auth.setCustomUserClaims(caller.uid, { hid, role: 'PARENT' });
  log.info('household.created', { hid, deviceId: key.deviceId, attestation, encryptionKey: encryptionKey !== undefined });
  return { hid, deviceId: key.deviceId };
}

/**
 * Firestore values as JSON: timestamps become ISO-8601 strings, bytes (message ciphertext) standard base64; everything
 * else is already plain.
 */
function plain(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toDate().toISOString();
  if (value instanceof Uint8Array) return Buffer.from(value).toString('base64');
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
  /** Chat messages as stored: envelopes with their ciphertext (base64), never a plaintext (D-M4-6). */
  messages: Record<string, unknown>[];
  /** Blob chunk metadata (headers) without `data`, the encrypted bytes (D-M4-6). */
  blobs: Record<string, unknown>[];
}

/**
 * exportHousehold({ callerDeviceId, signature }) → the household's data as one JSON bundle (right of access and
 * portability, PRIVACY.md; D-M3-7). Chat is exported as stored, end-to-end encrypted: message envelopes with their
 * ciphertext and blob chunk headers without their bytes (D-M4-6); a plaintext export is a device's job (M7). Signed by an ACTIVE parent device over
 * `"sprout/export-household/v2" || 0x00 || hid || 0x00 || sha256(raw ID token) || 0x00 || {}` (crypto.ts
 * `callablePayload`). Refused with `resource-exhausted` above 4 MB (config.ts `MAX_EXPORT_BYTES`), below the host's
 * 4.5 MB response limit, or above `MAX_EXPORT_MESSAGES` messages (read in pages, D-M4-17).
 */
/**
 * The household's messages ordered by (lamport, id), read [EXPORT_PAGE_SIZE] at a time (audit L5). Stops reading and
 * answers `resource-exhausted` as soon as more than [MAX_EXPORT_MESSAGES] exist, so a long history is never loaded
 * whole into one instance.
 */
async function exportedMessages(hid: string, ref: DocumentReference): Promise<DocumentSnapshot[]> {
  const rows: DocumentSnapshot[] = [];
  let last: DocumentSnapshot | undefined;
  for (;;) {
    let query = ref.collection('messages').orderBy('lamport').orderBy(FieldPath.documentId()).limit(EXPORT_PAGE_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    rows.push(...page.docs);
    if (rows.length > MAX_EXPORT_MESSAGES) {
      log.warn('household.export.tooManyMessages', { hid, limit: MAX_EXPORT_MESSAGES });
      throw new HttpsError(
        'resource-exhausted',
        `The chat history has more than ${MAX_EXPORT_MESSAGES} messages and cannot be exported in one response`,
      );
    }
    if (page.size < EXPORT_PAGE_SIZE) return rows;
    last = page.docs[page.docs.length - 1];
  }
}

export async function exportHousehold(request: CallableRequest): Promise<ExportBundle> {
  const caller = await parent(request, 'exportHousehold');
  await requireDeviceProof(caller, dataOf(request), 'exportHousehold', {});
  const ref = db.doc(`households/${caller.hid}`);
  const names = Object.keys(DOCUMENT_COLLECTIONS);
  const messages = await exportedMessages(caller.hid, ref);
  const [household, members, devices, parentKeys, events, blobs, ...documents] = await Promise.all([
    ref.get(),
    ref.collection('members').get(),
    ref.collection('devices').get(),
    ref.collection('parentKeys').get(),
    ref.collection('events').orderBy('lamport').get(),
    // Only the header fields: the encrypted bytes (`data`, up to 700 KB each) never leave the database.
    ref.collection('blobs').select(...BLOB_HEADER_FIELDS).get(),
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
    messages: messages.map(row),
    blobs: blobs.docs.map(row),
  };
  const bytes = Buffer.byteLength(JSON.stringify(bundle), 'utf8');
  const documentCount = documents.reduce((n, snap) => n + snap.size, 0);
  if (bytes > MAX_EXPORT_BYTES) {
    log.warn('household.export.tooLarge', { hid: caller.hid, bytes });
    throw new HttpsError('resource-exhausted', 'The household is too large to export in one response');
  }
  log.info('household.exported', {
    hid: caller.hid,
    events: events.size,
    documents: documentCount,
    messages: messages.length,
    blobs: blobs.size,
    bytes,
  });
  return bundle;
}

/**
 * deleteHousehold({ confirmation: hid, callerDeviceId, signature }) → { deleted: true }. Signed by an ACTIVE parent
 * device over `"sprout/delete-household/v2" || 0x00 || hid || 0x00 || sha256(raw ID token) || 0x00 ||
 * {"confirmation":"<hid>"}` (crypto.ts `callablePayload`), from a session signed in within the last 5 minutes (ID
 * token `auth_time`; otherwise `failed-precondition`, sign in again).
 *
 * Records the deletion first, as `deletionRecords/{autoId}` with a hash of the id and counts only (right to erasure,
 * PRIVACY.md), so a deletion that fails half-way still answers `householdGone` to `deviceStatus` (which reports it only
 * when the household or the device is missing). Then deletes the `members` rows (the rules key every client
 * permission off them, so clients lose access before anything else goes), `households/{hid}` recursively, the
 * household's outstanding link codes, and for every uid that was a member or owns a registered device (a revoked
 * kid's member row is already gone): its registration challenge, its `userHouseholds` row and its Auth user (kids and
 * parents, the caller included). A retry after a partial failure records the deletion again (counts as then left).
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

  const hidHash = sha256Hex(hid);
  await db.collection('deletionRecords').add({ hidHash, at: FieldValue.serverTimestamp(), counts });

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

  log.info('household.deleted', {
    hidHash,
    ...counts,
    usersDeleted: result.successCount,
    userDeleteFailures: result.failureCount,
  });
  return { deleted: true };
}
