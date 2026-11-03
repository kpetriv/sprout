import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  setDoc,
  updateDoc,
  where,
  type Firestore,
} from 'firebase/firestore';
import {
  asKid,
  asKid2,
  asOtherParent,
  asParent,
  asParentSignedInAt,
  blobChunk,
  bytesOf,
  clean,
  document,
  HID,
  KID2_DEVICE,
  KID2_ID,
  KID2_UID,
  KID_DEVICE,
  KID_ID,
  KID_UID,
  kidMessage,
  message,
  PARENT_DEVICE,
  PARENT_UID,
  payloadFor,
  PENDING_DEVICE,
  REVOKED_DEVICE,
  seed,
  setupEnv,
  WRAPPED,
} from './helpers.js';

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

const putMessage = (db: Firestore, data: Record<string, unknown>, id = data.messageId as string) =>
  setDoc(doc(db, `households/${HID}/messages/${id}`), clean(data));

const putBlob = (db: Firestore, data: Record<string, unknown>, id = `${data.blobId as string}.${data.chunk as number}`) =>
  setDoc(doc(db, `households/${HID}/blobs/${id}`), clean(data));

const putThreadKey = (db: Firestore, data: Record<string, unknown>) =>
  setDoc(doc(db, `households/${HID}/threadKeys/${data.docId as string}`), clean(data));

/** Seeds a message per thread and a blob per kid, bypassing the rules. */
async function seedChat() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore() as unknown as Firestore;
    await setDoc(doc(db, `households/${HID}/messages/m-a`), message({ messageId: 'm-a', threadId: KID_ID }));
    await setDoc(doc(db, `households/${HID}/messages/m-b`), message({ messageId: 'm-b', threadId: KID2_ID }));
    await setDoc(doc(db, `households/${HID}/blobs/b-a.0`), blobChunk({ blobId: 'b-a' }));
    await setDoc(
      doc(db, `households/${HID}/blobs/b-b.0`),
      blobChunk({ blobId: 'b-b', threadId: KID2_ID, kidId: KID2_ID }),
    );
  });
}

describe('messages: writes', () => {
  it('a parent writes in every thread, a kid only in its own', async () => {
    await assertSucceeds(putMessage(asParent(env), message()));
    await assertSucceeds(putMessage(asParent(env), message({ messageId: 'msg-2', threadId: KID2_ID })));
    await assertSucceeds(putMessage(asKid(env), kidMessage()));
    await assertFails(putMessage(asKid(env), kidMessage({ messageId: 'msg-kid-2', threadId: KID2_ID })));
  });

  it('a kid cannot write to another thread with the other kid as author', async () => {
    await assertFails(
      putMessage(asKid(env), kidMessage({ threadId: KID2_ID, authorUid: KID2_UID, authorDeviceId: KID2_DEVICE })),
    );
  });

  it('the author must be an ACTIVE device of the caller in the caller role', async () => {
    await assertFails(putMessage(asParent(env), message({ authorDeviceId: PENDING_DEVICE })));
    await assertFails(putMessage(asParent(env), message({ authorDeviceId: REVOKED_DEVICE })));
    await assertFails(putMessage(asParent(env), message({ authorDeviceId: KID_DEVICE })));
    await assertFails(putMessage(asKid(env), kidMessage({ authorRole: 'PARENT' })));
    await assertFails(putMessage(asKid(env), message()));
    await assertFails(putMessage(asOtherParent(env), message()));
  });

  it('a session older than tokensValidAfter cannot write', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore() as unknown as Firestore;
      await updateDoc(doc(db, `households/${HID}/members/${PARENT_UID}`), { tokensValidAfter: 2_000 });
    });
    await assertFails(putMessage(asParentSignedInAt(env, 1_999), message()));
    await assertSucceeds(putMessage(asParentSignedInAt(env, 2_000), message()));
  });

  it('kid mode on a parent phone writes KID-role messages and blobs in any thread', async () => {
    await assertSucceeds(putMessage(asParent(env), message({ messageId: 'km-1', authorRole: 'KID' })));
    await assertSucceeds(putMessage(asParent(env), message({ messageId: 'km-2', authorRole: 'KID', threadId: KID2_ID })));
    await assertSucceeds(putBlob(asParent(env), blobChunk({ blobId: 'kb-1', threadId: KID2_ID, kidId: KID2_ID })));
    // A kid device never authors as a parent, nor as a kid in another thread.
    await assertFails(putMessage(asKid(env), kidMessage({ messageId: 'km-3', authorRole: 'PARENT' })));
    await assertFails(putMessage(asKid(env), kidMessage({ messageId: 'km-4', threadId: KID2_ID })));
  });

  it('the ciphertext is bytes of 1 to 8192', async () => {
    await assertSucceeds(putMessage(asParent(env), message({ messageId: 'max', ciphertext: bytesOf(8192) })));
    await assertFails(putMessage(asParent(env), message({ messageId: 'over', ciphertext: bytesOf(8193) })));
    await assertFails(putMessage(asParent(env), message({ messageId: 'empty', ciphertext: bytesOf(0) })));
    await assertFails(putMessage(asParent(env), message({ messageId: 'text', ciphertext: 'AAAA' })));
  });

  it('the key set is closed and every field is checked', async () => {
    const parent = asParent(env);
    const refused: Record<string, unknown>[] = [
      { extra: 1 },
      { seq: undefined },
      { signature: undefined },
      { serverReceivedAt: undefined },
      { schemaVersion: 2 },
      { householdId: 'household-2' },
      { messageId: 'other' },
      { authorUid: KID_UID },
      { seq: 0 },
      { seq: '1' },
      { lamport: -1 },
      { epoch: -1 },
      { createdAt: '2026' },
      { kind: 'VIDEO' },
      { kind: 'PHOTO' },
      { kind: 'TEXT', blobId: 'blob-1' },
      { signature: 's'.repeat(201) },
      { threadId: 't'.repeat(129) },
      { serverReceivedAt: 5 },
    ];
    for (const [i, overrides] of refused.entries()) {
      await assertFails(putMessage(parent, message(overrides), `msg-1`).catch((e: unknown) => {
        throw new Error(`case ${i}: ${String(e)}`);
      }));
    }
    await assertSucceeds(putMessage(parent, message({ messageId: 'voice', kind: 'VOICE', blobId: 'blob-1' })));
  });

  it('messages are create-only', async () => {
    await assertSucceeds(putMessage(asParent(env), message()));
    await assertFails(putMessage(asParent(env), message({ seq: 2 })));
    await assertFails(updateDoc(doc(asParent(env), `households/${HID}/messages/msg-1`), { seq: 3 }));
    await assertFails(deleteDoc(doc(asParent(env), `households/${HID}/messages/msg-1`)));
  });

  it('stays within the rules evaluation budget for the largest message', async () => {
    await assertSucceeds(
      putMessage(asKid(env), kidMessage({
        messageId: 'm'.repeat(128),
        kind: 'PHOTO',
        blobId: 'b'.repeat(128),
        ciphertext: bytesOf(8192),
        signature: 's'.repeat(200),
      })),
    );
  });
});

describe('messages: reads', () => {
  beforeEach(seedChat);

  it('a parent reads every thread', async () => {
    await assertSucceeds(getDocs(collection(asParent(env), `households/${HID}/messages`)));
    await assertSucceeds(getDoc(doc(asParent(env), `households/${HID}/messages/m-b`)));
  });

  it("a kid reads its own thread and cannot read another kid's", async () => {
    const kid = asKid(env);
    const messages = collection(kid, `households/${HID}/messages`);
    await assertSucceeds(getDocs(query(messages, where('threadId', '==', KID_ID))));
    await assertSucceeds(getDoc(doc(kid, `households/${HID}/messages/m-a`)));
    await assertFails(getDocs(messages));
    await assertFails(getDocs(query(messages, where('threadId', '==', KID2_ID))));
    await assertFails(getDoc(doc(kid, `households/${HID}/messages/m-b`)));
    await assertFails(getDoc(doc(asKid2(env), `households/${HID}/messages/m-a`)));
  });

  it('a missing message can be read back by its id (a refused write is read back)', async () => {
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/messages/nothing`)));
  });

  it('another household reads nothing', async () => {
    await assertFails(getDoc(doc(asOtherParent(env), `households/${HID}/messages/m-a`)));
    await assertFails(getDocs(collection(asOtherParent(env), `households/${HID}/messages`)));
  });
});

describe('blobs', () => {
  it('a parent and the thread kid write chunks; another kid cannot', async () => {
    await assertSucceeds(putBlob(asParent(env), blobChunk()));
    await assertSucceeds(
      putBlob(asKid(env), blobChunk({ blobId: 'kid-blob', authorUid: 'kid-uid', authorDeviceId: KID_DEVICE })),
    );
    await assertFails(
      putBlob(asKid(env), blobChunk({
        blobId: 'kid-blob-2', threadId: KID2_ID, kidId: KID2_ID, authorUid: 'kid-uid', authorDeviceId: KID_DEVICE,
      })),
    );
  });

  it('a chore photo carries a submission instead of a thread', async () => {
    await assertSucceeds(putBlob(asKid(env), blobChunk({
      blobId: 'photo-1', threadId: null, submissionId: 'sub-1', authorUid: 'kid-uid', authorDeviceId: KID_DEVICE,
    })));
    await assertFails(putBlob(asParent(env), blobChunk({ blobId: 'both', submissionId: 'sub-1' })));
    await assertFails(putBlob(asParent(env), blobChunk({ blobId: 'neither', threadId: null })));
    await assertFails(putBlob(asParent(env), blobChunk({ blobId: 'mismatch', threadId: KID2_ID })));
  });

  it('chunks are 1 to 716800 bytes, at most two', async () => {
    const parent = asParent(env);
    await assertSucceeds(putBlob(parent, blobChunk({ blobId: 'big', chunks: 2, data: bytesOf(716_800) })));
    await assertSucceeds(putBlob(parent, blobChunk({ blobId: 'big', chunk: 1, chunks: 2, data: bytesOf(1) })));
    await assertFails(putBlob(parent, blobChunk({ blobId: 'over', data: bytesOf(716_801) })));
    await assertFails(putBlob(parent, blobChunk({ blobId: 'empty', data: bytesOf(0) })));
    await assertFails(putBlob(parent, blobChunk({ blobId: 'text', data: 'AAAA' })));
    await assertFails(putBlob(parent, blobChunk({ blobId: 'c2', chunk: 2, chunks: 3 })));
    await assertFails(putBlob(parent, blobChunk({ blobId: 'c3', chunk: 1, chunks: 1 })));
    await assertFails(putBlob(parent, blobChunk({ blobId: 'c4', chunks: 3 })));
  });

  it('the document id is blobId.chunk and the key set is closed', async () => {
    const parent = asParent(env);
    await assertFails(putBlob(parent, blobChunk(), 'blob-1.1'));
    await assertFails(putBlob(parent, blobChunk(), 'blob-1'));
    await assertFails(putBlob(parent, blobChunk({ extra: 1 })));
    await assertFails(putBlob(parent, blobChunk({ sha256: 'xyz' })));
    await assertFails(putBlob(parent, blobChunk({ authorDeviceId: PENDING_DEVICE })));
    await assertFails(putBlob(parent, blobChunk({ serverReceivedAt: undefined })));
  });

  it('blobs are create-only', async () => {
    await assertSucceeds(putBlob(asParent(env), blobChunk()));
    await assertFails(putBlob(asParent(env), blobChunk({ lamport: 9 })));
    await assertFails(deleteDoc(doc(asParent(env), `households/${HID}/blobs/blob-1.0`)));
  });

  it("a kid fetches its own blobs by id but never another kid's, and nobody lists them", async () => {
    await seedChat();
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/blobs/b-a.0`)));
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/blobs/b-a.1`)));
    await assertFails(getDoc(doc(asKid(env), `households/${HID}/blobs/b-b.0`)));
    await assertSucceeds(getDoc(doc(asParent(env), `households/${HID}/blobs/b-b.0`)));
    await assertFails(getDocs(collection(asParent(env), `households/${HID}/blobs`)));
    await assertFails(getDocs(collection(asKid(env), `households/${HID}/blobs`)));
    await assertFails(getDoc(doc(asOtherParent(env), `households/${HID}/blobs/b-a.0`)));
  });

  it('stays within the rules evaluation budget for the largest chunk', async () => {
    await assertSucceeds(putBlob(asKid(env), blobChunk({
      blobId: 'b'.repeat(126),
      chunk: 1,
      chunks: 2,
      threadId: null,
      submissionId: 's'.repeat(128),
      authorUid: 'kid-uid',
      authorDeviceId: KID_DEVICE,
      signature: 's'.repeat(200),
      data: bytesOf(716_800),
    })));
  });
});

describe('threadKeys', () => {
  const threadKey = (payload: Record<string, unknown>, overrides: Record<string, unknown> = {}) =>
    document('threadKeys', { docId: `${KID_ID}.0`, payload: { ...payloadFor('threadKeys', 'x'), ...payload }, ...overrides });

  it('only a parent writes thread keys', async () => {
    await assertSucceeds(putThreadKey(asParent(env), threadKey({})));
    await assertFails(
      putThreadKey(asKid(env), threadKey({}, { docId: `${KID_ID}.1`, authorUid: 'kid-uid', authorDeviceId: KID_DEVICE })),
    );
  });

  it('the payload is closed and bounded', async () => {
    const parent = asParent(env);
    const refused: Record<string, unknown>[] = [
      { extra: 1 },
      { epoch: -1 },
      { epoch: '0' },
      { threadId: undefined },
      { createdByDeviceId: 'not-a-device' },
      { wraps: {} },
      { wraps: { [PARENT_DEVICE]: 'flat-string' } },
      { wraps: { [PARENT_DEVICE]: { wrapped: 'w'.repeat(129) } } },
      { wraps: { [PARENT_DEVICE]: { wrapped: WRAPPED, extra: 1 } } },
      { wraps: { [PARENT_DEVICE]: { wrapped: '' } } },
      { wraps: { 'not-a-device': { wrapped: WRAPPED } } },
      { wraps: Object.fromEntries(Array.from({ length: 17 }, (_, i) => [i.toString(16).padStart(64, '0'), { wrapped: WRAPPED }])) },
    ];
    for (const [i, payload] of refused.entries()) {
      const data = threadKey({}, { lamport: 10 + i });
      const merged = { ...(data.payload as Record<string, unknown>), ...payload };
      if (payload.threadId === undefined && 'threadId' in payload) delete merged.threadId;
      await assertFails(putThreadKey(parent, { ...data, payload: merged })).catch((e: unknown) => {
        throw new Error(`case ${i}: ${String(e)}`);
      });
    }
  });

  it('a bad wrap at the last index is still caught', async () => {
    const wraps = Object.fromEntries(
      Array.from({ length: 16 }, (_, i) => [i.toString(16).padStart(64, '0'), { wrapped: i === 15 ? 7 : WRAPPED }]),
    );
    await assertFails(putThreadKey(asParent(env), threadKey({ wraps })));
  });

  it('stays within the rules evaluation budget for the largest thread key (16 wraps) and its re-wrap', async () => {
    const wraps = Object.fromEntries(
      Array.from({ length: 16 }, (_, i) => [i.toString(16).padStart(64, '0'), { wrapped: 'w'.repeat(128) }]),
    );
    const largest = { threadId: 't'.repeat(128), epoch: 9_007_199_254_740_991, createdByDeviceId: PARENT_DEVICE, wraps };
    await assertSucceeds(putThreadKey(asParent(env), threadKey(largest, { lamport: 1, signature: 's'.repeat(200) })));
    await assertSucceeds(putThreadKey(asParent(env), threadKey(largest, { lamport: 2, signature: 's'.repeat(200) })));
  });

  it('kids read thread keys (wraps are useless without the device key)', async () => {
    await assertSucceeds(putThreadKey(asParent(env), threadKey({})));
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/threadKeys/${KID_ID}.0`)));
  });
});

describe('device encryption keys', () => {
  it('no member writes a device row, so no client registers an encryption key', async () => {
    const own = doc(asKid(env), `households/${HID}/devices/${KID_DEVICE}`);
    await assertFails(updateDoc(own, { encryptionKey: 'AAAA', encryptionKeySignature: 'BBBB' }));
    await assertFails(
      updateDoc(doc(asParent(env), `households/${HID}/devices/${PARENT_DEVICE}`), { encryptionKey: 'AAAA' }),
    );
  });
});
