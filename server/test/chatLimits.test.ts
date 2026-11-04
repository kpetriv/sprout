import { Timestamp } from 'firebase-admin/firestore';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { call, db, fns, newHousehold, req, resetEmulators, withProof, type Household } from './helpers.js';

// Small limits, so the per-call bounds of pruneMessages show with a handful of documents (D-M7-55).
vi.mock('../src/config.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/config.js')>()),
  PRUNE_BATCH_SIZE: 3,
  MAX_PRUNE_DOCUMENTS: 5,
  MAX_PRUNE_CHUNK_PAGES: 2,
}));

const DAY_MS = 24 * 60 * 60 * 1000;
const KID = 'kid-a';

beforeEach(async () => {
  await resetEmulators();
});

async function message(h: Household, id: string, at: number) {
  await db.doc(`households/${h.hid}/messages/${id}`).set({
    messageId: id,
    threadId: KID,
    kind: 'TEXT',
    createdAt: at,
    ciphertext: Buffer.from('ciphertext'),
    serverReceivedAt: Timestamp.fromMillis(at),
  });
}

async function chunk(h: Household, blobId: string, at: number, chore = false) {
  await db.doc(`households/${h.hid}/blobs/${blobId}.0`).set({
    blobId,
    kidId: KID,
    ...(chore ? { submissionId: 'sub-1' } : { threadId: KID }),
    chunk: 0,
    chunks: 1,
    data: Buffer.from('bytes'),
    serverReceivedAt: Timestamp.fromMillis(at),
  });
}

const purge = (h: Household, before: number) =>
  call(fns.pruneMessages, req(h.parent, withProof(h, 'pruneMessages', { before, purge: true })));

const count = async (h: Household, collection: string) =>
  (await db.collection(`households/${h.hid}/${collection}`).get()).size;

describe('pruneMessages limits', () => {
  it('stops at the document budget and asks for another call', async () => {
    const h = await newHousehold();
    const old = Date.now() - 2 * DAY_MS;
    for (let i = 0; i < 7; i++) await message(h, `m-${i}`, old + i);
    expect(await purge(h, Date.now())).toMatchObject({ messages: 5, blobChunks: 0, more: true });
    expect(await purge(h, Date.now())).toMatchObject({ messages: 2, blobChunks: 0, more: false });
    expect(await count(h, 'messages')).toBe(0);
    const records = (await db.collection(`households/${h.hid}/prunes`).get()).docs.map((d) => d.data());
    expect(records.map((r) => r.status as unknown)).toEqual(['done', 'done']);
  });

  it('reads at most the page limit of chunks per call; more only when the call made progress', async () => {
    const h = await newHousehold();
    const old = Date.now() - 2 * DAY_MS;
    // Page 1: three orphan chat chunks; page 2: three chore photos; page 3: two orphan chat chunks.
    for (let i = 0; i < 3; i++) await chunk(h, `b-a${i}`, old + i);
    for (let i = 0; i < 3; i++) await chunk(h, `p-${i}`, old + 10 + i, true);
    for (let i = 0; i < 2; i++) await chunk(h, `b-b${i}`, old + 20 + i);
    expect(await purge(h, Date.now())).toMatchObject({ blobChunks: 3, more: true });
    expect(await purge(h, Date.now())).toMatchObject({ blobChunks: 2, more: false });
    expect(await count(h, 'blobs')).toBe(3);
    // Only chore photos are left: a full scan that deletes nothing does not ask again.
    for (let i = 3; i < 9; i++) await chunk(h, `p-${i}`, old + 30 + i, true);
    expect(await purge(h, Date.now())).toMatchObject({ blobChunks: 0, more: false });
  });
});
