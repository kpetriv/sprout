import type { DocumentReference, Query, Transaction } from 'firebase-admin/firestore';
import { HttpsError } from './https.js';
import { CHAT_COLLECTIONS, DOCUMENT_COLLECTIONS } from './config.js';
import { log } from './log.js';

/**
 * A stored lamport as a JavaScript number. Lamports are non-negative 64-bit integers on the devices; the Admin SDK
 * reads integers as numbers, which are exact only up to 2^53 - 1, so anything that is not a safe non-negative
 * integer (or a bigint in that range) is refused with `internal` instead of being rounded.
 */
export function lamportOf(value: unknown): number {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value;
  if (typeof value === 'bigint' && value >= 0n && value <= BigInt(Number.MAX_SAFE_INTEGER)) return Number(value);
  log.error('lamport.outOfRange', { kind: typeof value });
  throw new HttpsError('internal', 'A stored lamport is out of range');
}

/** [max] + 1, refused with `internal` when that is no longer a safe integer. */
export function nextLamport(max: number): number {
  const next = max + 1;
  if (!Number.isSafeInteger(next)) {
    log.error('lamport.outOfRange', { kind: 'next' });
    throw new HttpsError('internal', 'A stored lamport is out of range');
  }
  return next;
}

/** The event collection, every document collection and the chat collections (messages, blobs) of [household]. */
function lamportCollections(household: DocumentReference) {
  return ['events', ...Object.keys(DOCUMENT_COLLECTIONS), ...CHAT_COLLECTIONS].map((name) => household.collection(name));
}

/**
 * The highest lamport over the household's events, documents, messages and blob chunks, optionally only those
 * written by [deviceId]
 * (`authorDeviceId`), read inside [tx] with one `orderBy lamport desc limit 1` query per collection; 0 when there are
 * none. The per-device queries need the (authorDeviceId, lamport desc) composite indexes in firestore.indexes.json.
 */
export async function maxLamport(tx: Transaction, household: DocumentReference, deviceId?: string): Promise<number> {
  const queries: Query[] = lamportCollections(household).map((c) =>
    (deviceId === undefined ? c : c.where('authorDeviceId', '==', deviceId)).orderBy('lamport', 'desc').limit(1),
  );
  const snaps = await Promise.all(queries.map((q) => tx.get(q)));
  let max = 0;
  for (const snap of snaps) {
    if (snap.empty) continue;
    max = Math.max(max, lamportOf(snap.docs[0].get('lamport')));
  }
  return max;
}
