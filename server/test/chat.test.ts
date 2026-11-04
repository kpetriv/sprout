import { Timestamp } from 'firebase-admin/firestore';
import { beforeEach, describe, expect, it } from 'vitest';
import { MAX_PRUNE_DOCUMENTS, PRUNE_BATCH_SIZE, PRUNE_CLOCK_SKEW_MS } from '../src/config.js';
import {
  call,
  db,
  expectCode,
  fns,
  linkKid,
  newHousehold,
  req,
  resetEmulators,
  signed,
  withProof,
  type Household,
} from './helpers.js';

const DAY_MS = 24 * 60 * 60 * 1000;
const KID = 'kid-a';

beforeEach(async () => {
  await resetEmulators();
});

/** The synced household policy document with [days] of chat retention (null: keep messages). */
async function setRetention(h: Household, days: number | null, deleted = false): Promise<void> {
  const payload: Record<string, unknown> = { fiatCurrency: 'EUR', requireParentSignature: false };
  if (days !== null) payload.chatRetentionDays = days;
  await db.doc(`households/${h.hid}/householdPolicy/${h.hid}`).set({
    schemaVersion: 1,
    householdId: h.hid,
    docType: 'household_policy',
    docId: h.hid,
    lamport: 1,
    deleted,
    payload,
    signature: 'sig',
  });
}

/** A stored message created at [createdAt] and stamped by the server at [receivedAt] (default: the same). */
async function message(h: Household, id: string, createdAt: number, blobId: string | null = null, receivedAt = createdAt) {
  await db.doc(`households/${h.hid}/messages/${id}`).set({
    messageId: id,
    threadId: KID,
    kind: blobId === null ? 'TEXT' : 'PHOTO',
    createdAt,
    blobId,
    ciphertext: Buffer.from('ciphertext'),
    serverReceivedAt: Timestamp.fromMillis(receivedAt),
  });
}

/** A stored blob chunk: a chat blob of [KID]'s thread, or a chore photo of submission `sub-1` when [chore]. */
async function chunk(h: Household, blobId: string, receivedAt: number, chore = false) {
  await db.doc(`households/${h.hid}/blobs/${blobId}.0`).set({
    blobId,
    kidId: KID,
    ...(chore ? { submissionId: 'sub-1' } : { threadId: KID }),
    chunk: 0,
    chunks: 1,
    data: Buffer.from('bytes'),
    serverReceivedAt: Timestamp.fromMillis(receivedAt),
  });
}

const ids = async (h: Household, collection: string) =>
  (await db.collection(`households/${h.hid}/${collection}`).get()).docs.map((d) => d.id).sort();

const prune = (h: Household, before: number, purge = false) =>
  call(fns.pruneMessages, req(h.parent, withProof(h, 'pruneMessages', { before, purge })));

describe('pruneMessages', () => {
  it('deletes messages and chat blob chunks older than the retention cut and records the prune', async () => {
    const h = await newHousehold();
    await setRetention(h, 30);
    const now = Date.now();
    const old = now - 40 * DAY_MS;
    const recent = now - DAY_MS;
    await message(h, 'm-old', old, 'b-old');
    await message(h, 'm-old-text', old);
    // Dated recently by its author but stored by the server long ago, and the other way round: both go.
    await message(h, 'm-forged', now + 365 * DAY_MS, null, old);
    await message(h, 'm-late', old, null, recent);
    await message(h, 'm-new', recent, 'b-kept');
    await chunk(h, 'b-old', old);
    await chunk(h, 'b-kept', old); // named by m-new, which stays
    await chunk(h, 'b-orphan', old); // its message never came
    await chunk(h, 'b-new', recent);
    await chunk(h, 'p-photo', old, true); // a chore photo: never a chat prune's

    const before = now - 30 * DAY_MS;
    const result = await prune(h, before);

    expect(result).toEqual({ messages: 4, blobChunks: 2, more: false, before });
    expect(await ids(h, 'messages')).toEqual(['m-new']);
    expect(await ids(h, 'blobs')).toEqual(['b-kept.0', 'b-new.0', 'p-photo.0']);
    const records = await db.collection(`households/${h.hid}/prunes`).get();
    expect(records.size).toBe(1);
    const record = records.docs[0].data();
    expect(Object.keys(record).sort()).toEqual(['at', 'before', 'counts', 'deviceId', 'purge']);
    expect(record).toMatchObject({ purge: false, deviceId: h.device.deviceId, counts: { messages: 4, blobChunks: 2 } });
    expect((record.before as Timestamp).toMillis()).toBe(before);

    // Idempotent: nothing older is left.
    expect(await prune(h, before)).toEqual({ messages: 0, blobChunks: 0, more: false, before });
  });

  it('refuses a retention cut earlier than the policy allows, a cut in the future and a household that keeps messages', async () => {
    const h = await newHousehold();
    const now = Date.now();
    await expectCode(prune(h, now - DAY_MS), 'failed-precondition');
    await setRetention(h, 30, true);
    await expectCode(prune(h, now - DAY_MS), 'failed-precondition'); // a tombstoned policy keeps messages
    await setRetention(h, 30);
    await expectCode(prune(h, now - 31 * DAY_MS), 'invalid-argument');
    await expectCode(prune(h, now + PRUNE_CLOCK_SKEW_MS + 60_000), 'invalid-argument');
    // A device clock a little ahead is fine: the cut is clamped to the server's now.
    await message(h, 'm-new', now - 1000);
    const result = await prune(h, now + 60_000, true);
    expect(result.before).toBeLessThanOrEqual(Date.now());
    expect(result.messages).toBe(1);
  });

  it('purges everything before now whatever the policy says', async () => {
    const h = await newHousehold();
    const now = Date.now();
    await message(h, 'm-1', now - 400 * DAY_MS, 'b-1');
    await message(h, 'm-2', now - 1000);
    await chunk(h, 'b-1', now - 400 * DAY_MS);
    const result = await prune(h, now, true);
    expect(result).toMatchObject({ messages: 2, blobChunks: 1, more: false });
    expect(await ids(h, 'messages')).toEqual([]);
    expect(await ids(h, 'blobs')).toEqual([]);
    expect((await db.collection(`households/${h.hid}/prunes`).get()).docs[0].get('purge')).toBe(true);
  });

  it('deletes in batches', async () => {
    const h = await newHousehold();
    const old = Date.now() - 2 * DAY_MS;
    const count = 2 * PRUNE_BATCH_SIZE + 17;
    expect(count).toBeLessThan(MAX_PRUNE_DOCUMENTS);
    const writer = db.bulkWriter();
    for (let i = 0; i < count; i++) {
      void writer.set(db.doc(`households/${h.hid}/messages/m-${i}`), {
        messageId: `m-${i}`,
        threadId: KID,
        createdAt: old,
        serverReceivedAt: Timestamp.fromMillis(old),
      });
    }
    await writer.close();
    const result = await prune(h, Date.now(), true);
    expect(result).toMatchObject({ messages: count, blobChunks: 0, more: false });
    expect(await ids(h, 'messages')).toEqual([]);
  });

  it('is for an active parent device with a valid proof only', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const now = Date.now();
    await message(h, 'm-1', now - 1000);
    await expectCode(
      call(fns.pruneMessages, req(linked.kid, { before: now, purge: true, ...signed(linked.device, 'pruneMessages', h.hid, { before: now, purge: true }) })),
      'permission-denied',
    );
    // Without a proof, with a proof over other arguments, or from a device that is not the caller's.
    await expectCode(call(fns.pruneMessages, req(h.parent, { before: now, purge: true })), 'invalid-argument');
    await expectCode(
      call(fns.pruneMessages, req(h.parent, { before: now, purge: true, ...signed(h.device, 'pruneMessages', h.hid, { before: now - 1, purge: true }) })),
      'permission-denied',
    );
    await expectCode(
      call(fns.pruneMessages, req(h.parent, { before: now, purge: true, ...signed(linked.device, 'pruneMessages', h.hid, { before: now, purge: true }) })),
      'permission-denied',
    );
    // Malformed arguments.
    await expectCode(call(fns.pruneMessages, req(h.parent, withProof(h, 'pruneMessages', { before: 'now', purge: true }))), 'invalid-argument');
    await expectCode(call(fns.pruneMessages, req(h.parent, withProof(h, 'pruneMessages', { before: 1.5, purge: true }))), 'invalid-argument');
    await expectCode(call(fns.pruneMessages, req(h.parent, withProof(h, 'pruneMessages', { before: now }))), 'invalid-argument');
    expect(await ids(h, 'messages')).toEqual(['m-1']);
    expect((await db.collection(`households/${h.hid}/prunes`).get()).size).toBe(0);
  });

  it('is rate limited per uid', async () => {
    const h = await newHousehold();
    for (let i = 0; i < 10; i++) {
      await expectCode(call(fns.pruneMessages, req(h.parent, { before: 'bad' })), 'invalid-argument');
    }
    await expectCode(call(fns.pruneMessages, req(h.parent, { before: 'bad' })), 'resource-exhausted');
  });
});
