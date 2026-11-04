import { FieldValue, Timestamp, type DocumentReference, type DocumentSnapshot, type Query } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import {
  CHAT_RETENTION_DAYS,
  MAX_PRUNE_DOCUMENTS,
  PRUNE_BATCH_SIZE,
  PRUNE_CLOCK_SKEW_MS,
} from '../config.js';
import { requireDeviceProof } from '../deviceProof.js';
import { db } from '../firebase.js';
import { dataOf, parent } from '../guards.js';
import { log } from '../log.js';

const DAY_MS = 24 * 60 * 60 * 1000;

export interface PruneResult {
  /** Messages deleted. */
  messages: number;
  /** Chat blob chunks deleted. */
  blobChunks: number;
  /** True when the call stopped at [MAX_PRUNE_DOCUMENTS]: call again to delete the rest. */
  more: boolean;
  /** The cut applied (epoch ms): the requested `before`, at most the server's now. */
  before: number;
}

/**
 * The household's synced chat retention in days (`householdPolicy/{hid}.payload.chatRetentionDays`), or null when
 * the household keeps messages (no policy, a tombstone, no value, or a value outside [CHAT_RETENTION_DAYS], which the
 * rules never let a client write).
 */
async function retentionDays(household: DocumentReference): Promise<number | null> {
  const policy = await household.collection('householdPolicy').doc(household.id).get();
  if (!policy.exists || policy.get('deleted') === true) return null;
  const days: unknown = policy.get('payload.chatRetentionDays');
  return typeof days === 'number' && Number.isInteger(days) && days >= CHAT_RETENTION_DAYS.min &&
    days <= CHAT_RETENTION_DAYS.max
    ? days
    : null;
}

/** Deletes what [query] matches in batches, at most [budget] documents; returns how many went. */
async function deleteMatching(query: Query, budget: number): Promise<number> {
  let deleted = 0;
  while (deleted < budget) {
    const limit = Math.min(PRUNE_BATCH_SIZE, budget - deleted);
    const page = await query.limit(limit).get();
    if (page.empty) break;
    const batch = db.batch();
    page.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    deleted += page.size;
    if (page.size < limit) break;
  }
  return deleted;
}

/**
 * Deletes the chat blob chunks (`threadId` set; chore photos are never touched) stored before [cut] that no remaining
 * message names, at most [budget]. Read in pages ordered by the server stamp; a chunk a newer message still names is
 * kept (its message is not due yet).
 */
async function deleteChatChunks(household: DocumentReference, cut: Timestamp, budget: number): Promise<number> {
  let deleted = 0;
  let last: DocumentSnapshot | undefined;
  const named = new Map<string, boolean>();
  const isNamed = async (blobId: string): Promise<boolean> => {
    const known = named.get(blobId);
    if (known !== undefined) return known;
    const found = !(await household.collection('messages').where('blobId', '==', blobId).limit(1).get()).empty;
    named.set(blobId, found);
    return found;
  };
  while (deleted < budget) {
    let query = household
      .collection('blobs')
      .where('serverReceivedAt', '<', cut)
      .orderBy('serverReceivedAt')
      .select('threadId', 'blobId', 'serverReceivedAt')
      .limit(PRUNE_BATCH_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    if (page.empty) break;
    last = page.docs[page.docs.length - 1];
    const doomed: DocumentSnapshot[] = [];
    for (const chunk of page.docs) {
      const threadId: unknown = chunk.get('threadId');
      const blobId: unknown = chunk.get('blobId');
      if (typeof threadId !== 'string' || typeof blobId !== 'string') continue;
      if (!(await isNamed(blobId))) doomed.push(chunk);
    }
    const take = doomed.slice(0, budget - deleted);
    if (take.length > 0) {
      const batch = db.batch();
      take.forEach((chunk) => batch.delete(chunk.ref));
      await batch.commit();
      deleted += take.length;
    }
    if (page.size < PRUNE_BATCH_SIZE) break;
  }
  return deleted;
}

/**
 * pruneMessages({ before, purge, callerDeviceId, signature }) → { messages, blobChunks, more, before } (M7 §2,
 * D-M7-8, ADR-0011). Message and blob documents are create-only for clients, so the chat retention deletes the
 * server's copy here, with the Admin SDK. Signed by an ACTIVE parent device over `"sprout/prune-messages/v2" || 0x00 ||
 * hid || 0x00 || sha256(raw ID token) || 0x00 || {"before":<ms>,"purge":<bool>}` (crypto.ts `callablePayload`).
 *
 * `before` (epoch ms, an integer) may be at most [PRUNE_CLOCK_SKEW_MS] ahead of the server clock and is clamped to
 * now. Without `purge` the household must have a retention (`failed-precondition` otherwise) and `before` must be no
 * earlier than `now − chatRetentionDays` (less the skew), so a retention prune never deletes more than the policy
 * says; `purge: true` ("Delete chat history now") accepts any `before` up to now. Deletes every message whose
 * `createdAt` (the author's clock) or `serverReceivedAt` is before the cut, a superset of what the devices prune by
 * `createdAt`, then the chat blob chunks stored before the cut that no remaining message names. At most
 * [MAX_PRUNE_DOCUMENTS] documents per call (`more: true` asks for another). Each call is recorded as
 * `households/{hid}/prunes/{autoId}` (`at`, `before`, `purge`, `deviceId`, counts; no content), which clients cannot
 * read or write. The export is unchanged: it carries whatever is left.
 */
export async function pruneMessages(request: CallableRequest): Promise<PruneResult> {
  const caller = await parent(request, 'pruneMessages');
  const data = dataOf(request);
  const { before, purge } = data;
  if (typeof before !== 'number' || !Number.isSafeInteger(before) || before < 0) {
    throw new HttpsError('invalid-argument', 'before must be a time in epoch milliseconds');
  }
  if (typeof purge !== 'boolean') throw new HttpsError('invalid-argument', 'purge must be a boolean');
  const deviceId = await requireDeviceProof(caller, data, 'pruneMessages', { before, purge });
  const now = Date.now();
  if (before > now + PRUNE_CLOCK_SKEW_MS) throw new HttpsError('invalid-argument', 'before is in the future');
  const household = db.doc(`households/${caller.hid}`);
  if (!purge) {
    const days = await retentionDays(household);
    if (days === null) throw new HttpsError('failed-precondition', 'The household keeps chat messages');
    if (before < now - days * DAY_MS - PRUNE_CLOCK_SKEW_MS) {
      throw new HttpsError('invalid-argument', 'before is earlier than the chat retention allows');
    }
  }
  const cut = Math.min(before, now);
  const stamp = Timestamp.fromMillis(cut);
  const messagesRef = household.collection('messages');
  let messages = await deleteMatching(messagesRef.where('createdAt', '<', cut), MAX_PRUNE_DOCUMENTS);
  messages += await deleteMatching(messagesRef.where('serverReceivedAt', '<', stamp), MAX_PRUNE_DOCUMENTS - messages);
  const blobChunks = await deleteChatChunks(household, stamp, MAX_PRUNE_DOCUMENTS - messages);
  const more = messages + blobChunks >= MAX_PRUNE_DOCUMENTS;
  await household.collection('prunes').add({
    at: FieldValue.serverTimestamp(),
    before: stamp,
    purge,
    deviceId,
    counts: { messages, blobChunks },
  });
  log.info('chat.pruned', { hid: caller.hid, deviceId, purge, messages, blobChunks, more });
  return { messages, blobChunks, more, before: cut };
}
