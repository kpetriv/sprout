import { FieldValue, Timestamp, type DocumentReference, type DocumentSnapshot, type Query } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from '../https.js';
import {
  CHAT_RETENTION_DAYS,
  MAX_PRUNE_CHUNK_PAGES,
  MAX_PRUNE_DOCUMENTS,
  PRUNE_BATCH_SIZE,
  PRUNE_BLOB_GRACE_MS,
  PRUNE_CLOCK_SKEW_MS,
} from '../config.js';
import { requireDeviceProof } from '../deviceProof.js';
import { db } from '../firebase.js';
import { dataOf, parent } from '../guards.js';
import { log } from '../log.js';

const DAY_MS = 24 * 60 * 60 * 1000;

/** Firestore's bound on the values of one `in` filter. */
const IN_QUERY_LIMIT = 30;

export interface PruneResult {
  /** Messages deleted. */
  messages: number;
  /** Chat blob chunks deleted. */
  blobChunks: number;
  /**
   * True when the call stopped at [MAX_PRUNE_DOCUMENTS] deletions or [MAX_PRUNE_CHUNK_PAGES] chunk pages: call again
   * to delete the rest.
   */
  more: boolean;
  /** The cut applied (epoch ms): the requested `before`, at most the server's now. */
  before: number;
}

/** What one call deleted so far; read by the audit record even when a delete throws midway. */
interface PruneCounts {
  messages: number;
  blobChunks: number;
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

/** Deletes what [query] matches in batches, at most [budget] documents; [onDeleted] hears each committed batch. */
async function deleteMatching(query: Query, budget: number, onDeleted: (count: number) => void): Promise<number> {
  let deleted = 0;
  while (deleted < budget) {
    const limit = Math.min(PRUNE_BATCH_SIZE, budget - deleted);
    const page = await query.limit(limit).get();
    if (page.empty) break;
    const batch = db.batch();
    page.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    deleted += page.size;
    onDeleted(page.size);
    if (page.size < limit) break;
  }
  return deleted;
}

/** The ids among [blobIds] that a remaining message names, read with `in` queries of at most [IN_QUERY_LIMIT]. */
async function namedBlobIds(household: DocumentReference, blobIds: string[]): Promise<Set<string>> {
  const named = new Set<string>();
  for (let i = 0; i < blobIds.length; i += IN_QUERY_LIMIT) {
    const slice = blobIds.slice(i, i + IN_QUERY_LIMIT);
    const found = await household.collection('messages').where('blobId', 'in', slice).select('blobId').get();
    found.docs.forEach((doc) => {
      const blobId: unknown = doc.get('blobId');
      if (typeof blobId === 'string') named.add(blobId);
    });
  }
  return named;
}

/**
 * Deletes the chat blob chunks (`threadId` set; chore photos are never touched) stored before [cut] that no remaining
 * message names, at most [budget], reading at most [MAX_PRUNE_CHUNK_PAGES] pages ordered by the server stamp; returns
 * whether another call would delete more (`more`): the budget ran out, or the page limit was reached after deleting
 * something. A chunk a newer message still names is kept (its message is not due yet), and so
 * is a chunk stored less than [PRUNE_BLOB_GRACE_MS] before the cut (its message may still be on its way, D-M7-55).
 * Whether a blob is named is read for a whole page at once (`in` queries).
 */
async function deleteChatChunks(
  household: DocumentReference,
  cut: number,
  budget: number,
  counts: PruneCounts,
): Promise<boolean> {
  const stamp = Timestamp.fromMillis(cut - PRUNE_BLOB_GRACE_MS);
  let deleted = 0;
  let last: DocumentSnapshot | undefined;
  const named = new Map<string, boolean>();
  for (let pages = 0; ; pages++) {
    // Stopping at the page limit asks for another call only when this one made progress: a call that deleted nothing
    // in its pages (chore photos and still-named chunks only) would read the same pages again.
    if (deleted >= budget) return true;
    if (pages >= MAX_PRUNE_CHUNK_PAGES) return deleted > 0;
    let query = household
      .collection('blobs')
      .where('serverReceivedAt', '<', stamp)
      .orderBy('serverReceivedAt')
      .select('threadId', 'blobId', 'serverReceivedAt')
      .limit(PRUNE_BATCH_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    if (page.empty) return false;
    last = page.docs[page.docs.length - 1];
    const chat = page.docs.filter(
      (chunk) => typeof chunk.get('threadId') === 'string' && typeof chunk.get('blobId') === 'string',
    );
    const unknown = [...new Set(chat.map((chunk) => chunk.get('blobId') as string))].filter((id) => !named.has(id));
    const found = await namedBlobIds(household, unknown);
    unknown.forEach((id) => named.set(id, found.has(id)));
    const doomed = chat.filter((chunk) => named.get(chunk.get('blobId') as string) === false);
    const take = doomed.slice(0, budget - deleted);
    if (take.length > 0) {
      const batch = db.batch();
      take.forEach((chunk) => batch.delete(chunk.ref));
      await batch.commit();
      deleted += take.length;
      counts.blobChunks += take.length;
    }
    if (take.length < doomed.length) return true;
    if (page.size < PRUNE_BATCH_SIZE) return false;
  }
}

/**
 * Publishes the prune watermark on the household root document (members read it, only the server writes it):
 * `chatPrune.prunedBefore` is the latest cut any prune applied (never moved back), `chatPrune.prunedAt` when the last
 * prune ran and `chatPrune.purgedAt` when the last purge ran. A device whose pull is behind a prune sees a `seq` gap
 * for the messages it never got; with the watermark it skips that gap at once instead of after a day (D-M7-53).
 */
async function publishWatermark(household: DocumentReference, cut: number, purge: boolean): Promise<void> {
  await db.runTransaction(async (tx) => {
    const root = await tx.get(household);
    const previous: unknown = root.get('chatPrune.prunedBefore');
    const latest = previous instanceof Timestamp && previous.toMillis() > cut ? previous : Timestamp.fromMillis(cut);
    const watermark: Record<string, unknown> = {
      'chatPrune.prunedBefore': latest,
      'chatPrune.prunedAt': FieldValue.serverTimestamp(),
    };
    if (purge) watermark['chatPrune.purgedAt'] = FieldValue.serverTimestamp();
    tx.update(household, watermark);
  });
}

/**
 * pruneMessages({ before, purge, callerDeviceId, signature }) → { messages, blobChunks, more, before } (M7 §2,
 * D-M7-8, ADR-0011). Message and blob documents are create-only for clients, so the chat retention deletes the
 * server's copy here, with the Admin SDK. Signed by an ACTIVE parent device over `"sprout/prune-messages/v2" || 0x00 ||
 * hid || 0x00 || sha256(raw ID token) || 0x00 || {"before":<ms>,"purge":<bool>}` (crypto.ts `callablePayload`).
 *
 * `before` (epoch ms, an integer) may be at most [PRUNE_CLOCK_SKEW_MS] ahead of the server clock and is clamped to
 * now. Without `purge` the household must have a retention (`failed-precondition` otherwise) and `before` must be no
 * later than `now − chatRetentionDays` plus [PRUNE_CLOCK_SKEW_MS] (D-M7-51): a later cut deletes more, so a retention
 * prune never deletes more than the policy says, while any earlier cut (a device clock behind, a retried call) is
 * accepted. `purge: true` ("Delete chat history now") accepts any `before` up to now. Deletes every message the server
 * stored (`serverReceivedAt`) before the cut, never by the author's `createdAt` (D-M7-52), then the chat blob chunks
 * stored before the cut that no remaining message names. At most [MAX_PRUNE_DOCUMENTS] documents and
 * [MAX_PRUNE_CHUNK_PAGES] chunk pages per call (`more: true` asks for another).
 *
 * Each call is recorded as `households/{hid}/prunes/{autoId}` (`at`, `before`, `purge`, `deviceId`, `status`, counts;
 * no content), written before anything is deleted (`status: 'started'`) and finalised with the counts (`'done'`, or
 * `'failed'` with what was deleted before the failure, D-M7-55); clients cannot read or write it. Then the household
 * root's `chatPrune` watermark is raised ([publishWatermark]). The export is unchanged: it carries whatever is left.
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
    if (before > now - days * DAY_MS + PRUNE_CLOCK_SKEW_MS) {
      throw new HttpsError('invalid-argument', 'before is later than the chat retention allows');
    }
  }
  const cut = Math.min(before, now);
  const stamp = Timestamp.fromMillis(cut);
  const counts: PruneCounts = { messages: 0, blobChunks: 0 };
  const record = await household.collection('prunes').add({
    at: FieldValue.serverTimestamp(),
    before: stamp,
    purge,
    deviceId,
    status: 'started',
    counts,
  });
  let more: boolean;
  let status = 'failed';
  try {
    await deleteMatching(
      household.collection('messages').where('serverReceivedAt', '<', stamp),
      MAX_PRUNE_DOCUMENTS,
      (n) => {
        counts.messages += n;
      },
    );
    const chunksStopped = await deleteChatChunks(household, cut, MAX_PRUNE_DOCUMENTS - counts.messages, counts);
    more = counts.messages >= MAX_PRUNE_DOCUMENTS || chunksStopped;
    await publishWatermark(household, cut, purge);
    status = 'done';
  } finally {
    try {
      await record.update({ status, counts: { ...counts } });
    } catch {
      // The deletions stand; the record keeps `started` (with zero counts). Never hides the first failure.
      log.warn('chat.prune-record-failed', { hid: caller.hid, deviceId, purge, status });
    }
  }
  log.info('chat.pruned', { hid: caller.hid, deviceId, purge, ...counts, more });
  return { messages: counts.messages, blobChunks: counts.blobChunks, more, before: cut };
}
