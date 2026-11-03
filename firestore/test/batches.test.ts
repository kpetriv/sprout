import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, writeBatch, type Firestore } from 'firebase/firestore';
import { asParent, clean, document, event, HID, PARENT_DEVICE, payloadFor, seed, setupEnv } from './helpers.js';

// Batched writes and the rules evaluation budget (review M1). Firebase documents a budget of 1,000 expressions per
// request; a batch is one request. These tests commit the heaviest items together and record what the emulator does:
// it evaluates every written document against its own budget, so the batches below all succeed (and so does a batch of
// 500 maximal events, checked while investigating). Production may account differently, which cannot be tested
// locally, so core/sync-firebase FirebaseSyncTransport still sizes batches by a per-item cost estimate (BATCH_BUDGET)
// and commits thread keys alone; a refused batch is retried item by item either way.

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await setupEnv();
});
afterAll(async () => {
  await env.cleanup();
});
beforeEach(async () => {
  await seed(env);
});

/** An event with every optional field at its bound (the heaviest event the rules evaluate). */
function maximalEvent(eventId: string): Record<string, unknown> {
  return event({
    eventId,
    signature: 's'.repeat(200),
    parentSignature: 'p'.repeat(200),
    currency: 'EUR', amount: 1, choreId: 'c'.repeat(128), dueOn: '2026-11-03', scheduleId: 's'.repeat(128),
    periodKey: '2026-W45', gameId: 'g'.repeat(128), score: 9, playedOn: '2026-11-03', rewardId: 'r'.repeat(128),
    minutesGranted: 30, reason: 'x'.repeat(500), toAccountId: 'a'.repeat(256), principal: 100,
    annualRateBasisPoints: 200, days: 30, sessionId: 's'.repeat(128), minutesUsed: 5, minutes: 5,
    grantedBy: 'g'.repeat(128), note: 'n'.repeat(500),
    causedBy: Array.from({ length: 16 }, (_, i) => `${i}`.padEnd(128, '-')),
  });
}

/** A decided chore submission with every payload field at its bound (the heaviest live document). */
function maximalDocument(docId: string): Record<string, unknown> {
  return document('choreSubmissions', {
    docId,
    lamport: 1,
    signature: 's'.repeat(200),
    payload: {
      ...payloadFor('choreSubmissions', docId), choreId: 'c'.repeat(128), note: 'n'.repeat(500),
      photoRef: { fileName: 'f'.repeat(128), sha256: 'a'.repeat(64), blobId: 'b'.repeat(64) },
      status: 'REJECTED', decidedAt: '2026-11-03T19:00:00Z', decidedBy: 'p'.repeat(128), rejectionReason: 'r'.repeat(500),
    },
  });
}

/** A thread key wrapped for 16 devices (the heaviest document the rules evaluate). */
function largestThreadKey(): [string, Record<string, unknown>] {
  const wraps = Object.fromEntries(
    Array.from({ length: 16 }, (_, i) => [i.toString(16).padStart(64, '0'), { wrapped: 'w'.repeat(128) }]),
  );
  const docId = `${'t'.repeat(128)}.0.${PARENT_DEVICE}`;
  return [
    `households/${HID}/threadKeys/${docId}`,
    document('threadKeys', {
      docId, lamport: 1, signature: 's'.repeat(200),
      payload: { threadId: 't'.repeat(128), epoch: 0, createdByDeviceId: PARENT_DEVICE, wraps },
    }),
  ];
}

function commit(db: Firestore, writes: Array<[string, Record<string, unknown>]>): Promise<void> {
  const batch = writeBatch(db);
  for (const [path, data] of writes) batch.set(doc(db, path), clean(data));
  return batch.commit();
}

describe('batched writes and the evaluation budget', () => {
  it('commits 3 maximal events and 2 maximal documents in one batch', async () => {
    const writes: Array<[string, Record<string, unknown>]> = [
      ...[1, 2, 3].map((i): [string, Record<string, unknown>] => [`households/${HID}/events/evt-max-${i}`, maximalEvent(`evt-max-${i}`)]),
      ...[1, 2].map((i): [string, Record<string, unknown>] => [`households/${HID}/choreSubmissions/sub-${i}`, maximalDocument(`sub-${i}`)]),
    ];
    await assertSucceeds(commit(asParent(env), writes));
  });

  it('commits a 16-wrap thread key and a maximal event in one batch', async () => {
    await assertSucceeds(commit(asParent(env), [largestThreadKey(), [`households/${HID}/events/evt-max`, maximalEvent('evt-max')]]));
  });

  it('commits a batch as large as the transport ever sends (10 maximal events)', async () => {
    const writes = Array.from({ length: 10 }, (_, i): [string, Record<string, unknown>] =>
      [`households/${HID}/events/evt-max-${i}`, maximalEvent(`evt-max-${i}`)]);
    await assertSucceeds(commit(asParent(env), writes));
  });

  it('refuses the whole batch when one item is refused (atomic)', async () => {
    const db = asParent(env);
    await assertFails(commit(db, [
      [`households/${HID}/events/evt-ok`, maximalEvent('evt-ok')],
      [`households/${HID}/events/evt-bad`, maximalEvent('evt-bad-other-id')],
    ]));
    await assertSucceeds(commit(db, [[`households/${HID}/events/evt-ok`, maximalEvent('evt-ok')]]));
  });
});
