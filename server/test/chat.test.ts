import { Timestamp } from 'firebase-admin/firestore';
import { beforeEach, describe, expect, it } from 'vitest';
import { MAX_PRUNE_DOCUMENTS, PRUNE_BATCH_SIZE, PRUNE_BLOB_GRACE_MS, PRUNE_CLOCK_SKEW_MS } from '../src/config.js';
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
  it('deletes messages and chat blob chunks the server stored before the retention cut and records the prune', async () => {
    const h = await newHousehold();
    await setRetention(h, 30);
    const now = Date.now();
    const old = now - 40 * DAY_MS;
    const recent = now - DAY_MS;
    await message(h, 'm-old', old, 'b-old');
    await message(h, 'm-old-text', old);
    // Only the server's stamp counts (D-M7-52): a message its author dated in the future but stored long ago goes, a
    // backdated one stored recently stays.
    await message(h, 'm-forged', now + 365 * DAY_MS, null, old);
    await message(h, 'm-backdated', 0, null, recent);
    await message(h, 'm-new', recent, 'b-kept');
    await chunk(h, 'b-old', old);
    await chunk(h, 'b-kept', old); // named by m-new, which stays
    await chunk(h, 'b-orphan', old); // its message never came
    await chunk(h, 'b-new', recent);
    await chunk(h, 'p-photo', old, true); // a chore photo: never a chat prune's

    const before = now - 30 * DAY_MS;
    const result = await prune(h, before);

    expect(result).toEqual({ messages: 3, blobChunks: 2, more: false, before });
    expect(await ids(h, 'messages')).toEqual(['m-backdated', 'm-new']);
    expect(await ids(h, 'blobs')).toEqual(['b-kept.0', 'b-new.0', 'p-photo.0']);
    const records = await db.collection(`households/${h.hid}/prunes`).get();
    expect(records.size).toBe(1);
    const record = records.docs[0].data();
    expect(Object.keys(record).sort()).toEqual(['at', 'before', 'counts', 'deviceId', 'purge', 'status']);
    expect(record).toMatchObject({
      purge: false,
      status: 'done',
      deviceId: h.device.deviceId,
      counts: { messages: 3, blobChunks: 2 },
    });
    expect((record.before as Timestamp).toMillis()).toBe(before);
    // The watermark members read (D-M7-53): the cut, when it ran, no purge yet.
    const watermark = (await db.doc(`households/${h.hid}`).get()).get('chatPrune') as Record<string, unknown>;
    expect((watermark.prunedBefore as Timestamp).toMillis()).toBe(before);
    expect(watermark.prunedAt).toBeInstanceOf(Timestamp);
    expect(watermark.purgedAt).toBeUndefined();

    // Idempotent: nothing older is left.
    expect(await prune(h, before)).toEqual({ messages: 0, blobChunks: 0, more: false, before });
    // An earlier cut never moves the watermark back.
    await prune(h, before - DAY_MS);
    const again = (await db.doc(`households/${h.hid}`).get()).get('chatPrune.prunedBefore') as Timestamp;
    expect(again.toMillis()).toBe(before);
  });

  it('refuses a retention cut later than the policy allows, a cut in the future and a household that keeps messages', async () => {
    const h = await newHousehold();
    const now = Date.now();
    await expectCode(prune(h, now - 31 * DAY_MS), 'failed-precondition');
    await setRetention(h, 30, true);
    await expectCode(prune(h, now - 31 * DAY_MS), 'failed-precondition'); // a tombstoned policy keeps messages
    await setRetention(h, 30);
    await message(h, 'm-recent', now - DAY_MS);
    // A later cut deletes more than the policy allows (D-M7-51): refused without purge.
    await expectCode(prune(h, now), 'invalid-argument');
    await expectCode(prune(h, now - 30 * DAY_MS + PRUNE_CLOCK_SKEW_MS + 60_000), 'invalid-argument');
    await expectCode(prune(h, now - DAY_MS), 'invalid-argument');
    expect(await ids(h, 'messages')).toEqual(['m-recent']);
    // Any earlier cut is fine: a slow device clock, a retry computed a while ago, a longer retention elsewhere.
    expect(await prune(h, now - 31 * DAY_MS)).toMatchObject({ messages: 0, more: false });
    expect(await prune(h, now - 400 * DAY_MS)).toMatchObject({ messages: 0, more: false });
    // A device clock a little ahead of the server is fine too.
    expect(await prune(h, now - 30 * DAY_MS + 60_000)).toMatchObject({ messages: 0 });
    await expectCode(prune(h, now + PRUNE_CLOCK_SKEW_MS + 60_000, true), 'invalid-argument');
    // A purge's cut is clamped to the server's now.
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
    const watermark = (await db.doc(`households/${h.hid}`).get()).get('chatPrune') as Record<string, unknown>;
    expect((watermark.prunedBefore as Timestamp).toMillis()).toBe(result.before);
    expect(watermark.purgedAt).toBeInstanceOf(Timestamp);
  });

  it('keeps blob chunks stored just before a purge: their message may still be on its way', async () => {
    const h = await newHousehold();
    const now = Date.now();
    await chunk(h, 'b-uploading', now - PRUNE_BLOB_GRACE_MS / 2);
    await chunk(h, 'b-stale', now - PRUNE_BLOB_GRACE_MS - 60_000);
    const result = await prune(h, now, true);
    expect(result).toMatchObject({ blobChunks: 1, more: false });
    expect(await ids(h, 'blobs')).toEqual(['b-uploading.0']);
  });

  it('reads whether a whole page of chunks is still named at once and keeps the named ones', async () => {
    const h = await newHousehold();
    const now = Date.now();
    const old = now - 2 * DAY_MS;
    // More distinct blobs than one `in` query takes, half of them named by a message stored after the cut.
    for (let i = 0; i < 70; i++) {
      await chunk(h, `b-${i}`, old);
      if (i % 2 === 0) await message(h, `m-${i}`, now, `b-${i}`);
    }
    const result = await prune(h, now - DAY_MS, true);
    expect(result).toMatchObject({ messages: 0, blobChunks: 35, more: false });
    expect((await ids(h, 'blobs')).length).toBe(35);
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
    expect((await db.doc(`households/${h.hid}`).get()).get('chatPrune')).toBeUndefined();
  });

  it('is rate limited per uid', async () => {
    const h = await newHousehold();
    for (let i = 0; i < 10; i++) {
      await expectCode(call(fns.pruneMessages, req(h.parent, { before: 'bad' })), 'invalid-argument');
    }
    await expectCode(call(fns.pruneMessages, req(h.parent, { before: 'bad' })), 'resource-exhausted');
  });
});
