import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import {
  asKid,
  asKid2,
  asOtherParent,
  asParent,
  clean,
  COLLECTIONS,
  document,
  HID,
  KID2_DEVICE,
  KID2_ID,
  KID2_UID,
  KID_DEVICE,
  KID_ID,
  OTHER_HID,
  PARENT_DEVICE,
  payloadFor,
  PENDING_DEVICE,
  REVOKED_DEVICE,
  seed,
  serverTimestamp,
  setupEnv,
  submission,
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

const put = (db: Firestore, coll: string, data: Record<string, unknown>, hid = HID) =>
  setDoc(doc(db, `households/${hid}/${coll}/${data.docId as string}`), clean(data));

describe('documents: parent happy path', () => {
  for (const coll of Object.keys(COLLECTIONS)) {
    it(`a parent creates, updates, tombstones and reads ${coll}`, async () => {
      const parent = asParent(env);
      // A thread key's id names its thread, epoch and creator; it is never tombstoned (audit M2).
      const threadKey = coll === 'threadKeys';
      const docId = threadKey ? `${KID_ID}.0.${PARENT_DEVICE}` : 'p-1';
      await assertSucceeds(put(parent, coll, document(coll, { docId, lamport: 1 })));
      await assertSucceeds(
        put(parent, coll, document(coll, { docId, lamport: 2, serverReceivedAt: serverTimestamp() })),
      );
      const tombstone = put(parent, coll, document(coll, { docId, lamport: 3, deleted: true }));
      await (threadKey ? assertFails(tombstone) : assertSucceeds(tombstone));
      await assertSucceeds(getDoc(doc(parent, `households/${HID}/${coll}/${docId}`)));
      await assertSucceeds(getDocs(collection(parent, `households/${HID}/${coll}`)));
    });
  }
});

describe('documents: envelope checks', () => {
  it('docType must match the collection', async () => {
    await assertFails(put(asParent(env), 'rewards', document('rewards', { docType: 'chore' })));
  });

  it('docId must match the document id and householdId the household', async () => {
    const parent = asParent(env);
    await assertFails(setDoc(doc(parent, `households/${HID}/rewards/other`), document('rewards')));
    await assertFails(put(parent, 'rewards', document('rewards', { householdId: OTHER_HID })));
  });

  it('authorDeviceId must be an ACTIVE device of the caller', async () => {
    const parent = asParent(env);
    await assertFails(put(parent, 'rewards', document('rewards', { authorDeviceId: PENDING_DEVICE })));
    await assertFails(put(parent, 'rewards', document('rewards', { authorDeviceId: REVOKED_DEVICE })));
    await assertFails(put(parent, 'rewards', document('rewards', { authorDeviceId: KID_DEVICE })));
  });

  it('rejects unknown top-level fields, unknown collections and Firestore deletes', async () => {
    const parent = asParent(env);
    await assertFails(put(parent, 'rewards', document('rewards', { extra: true })));
    await assertFails(setDoc(doc(parent, `households/${HID}/secrets/doc-1`), document('rewards')));
    await assertSucceeds(put(parent, 'rewards', document('rewards')));
    await assertFails(deleteDoc(doc(parent, `households/${HID}/rewards/doc-1`)));
  });

  it('bounds the payload key count', async () => {
    const payload = Object.fromEntries(Array.from({ length: 65 }, (_, i) => [`k${i}`, i]));
    await assertFails(put(asParent(env), 'chores', document('chores', { payload })));
  });

  it('requires serverReceivedAt, stamped with the server time', async () => {
    const parent = asParent(env);
    await assertFails(put(parent, 'rewards', document('rewards', { serverReceivedAt: undefined })));
    await assertFails(put(parent, 'rewards', document('rewards', { serverReceivedAt: new Date(0) })));
    await assertSucceeds(put(parent, 'rewards', document('rewards')));
  });
});

describe('documents: payload shape and size (T18)', () => {
  it('refuses an unknown payload key in a chore submission', async () => {
    await assertFails(put(asKid(env), 'choreSubmissions', submission({}, { extra: 'x' })));
    await assertFails(put(asParent(env), 'choreSubmissions', submission({ authorUid: 'parent-uid',
      authorDeviceId: PARENT_DEVICE }, { reward: 100 })));
  });

  it('refuses an oversized note or rejection reason and accepts 500 characters', async () => {
    await assertFails(put(asKid(env), 'choreSubmissions', submission({}, { note: 'x'.repeat(501) })));
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({}, { note: 'x'.repeat(500) })));
    await assertFails(
      put(asParent(env), 'choreSubmissions', document('choreSubmissions', {
        docId: 'sub-r', lamport: 9,
        payload: { ...payloadFor('choreSubmissions', 'sub-r'), status: 'REJECTED', rejectionReason: 'x'.repeat(501) },
      })),
    );
  });

  it('bounds titles to 80 characters, descriptions to 500 and a first name to 40', async () => {
    const parent = asParent(env);
    const over = (coll: string, field: string, n: number) =>
      put(parent, coll, document(coll, { payload: { ...payloadFor(coll, 'doc-1'), [field]: 'x'.repeat(n) } }));
    await assertFails(over('rewards', 'title', 81));
    await assertFails(over('chores', 'title', 81));
    await assertFails(over('savingsGoals', 'title', 81));
    await assertFails(over('chores', 'description', 501));
    await assertFails(over('kids', 'firstName', 41));
    await assertSucceeds(over('rewards', 'title', 80));
  });

  it('refuses unknown keys and wrong kinds in every docType, and an unknown submission status', async () => {
    const parent = asParent(env);
    for (const coll of Object.keys(COLLECTIONS)) {
      await assertFails(put(parent, coll, document(coll, { payload: { ...payloadFor(coll, 'doc-1'), bogus: 1 } })));
    }
    await assertFails(put(parent, 'rewards', document('rewards', { payload: { ...payloadFor('rewards', 'doc-1'), cost: '250' } })));
    await assertFails(put(parent, 'rewards', document('rewards', { payload: { ...payloadFor('rewards', 'doc-1'), forKids: Array(65).fill('k') } })));
    await assertFails(
      put(parent, 'choreSubmissions', document('choreSubmissions', {
        payload: { ...payloadFor('choreSubmissions', 'doc-1'), status: 'PAID' },
      })),
    );
  });
});

describe('documents: conflict semantics match core/sync ConflictResolver', () => {
  it('a live copy never overwrites a tombstone, whatever its lamport', async () => {
    const parent = asParent(env);
    await assertSucceeds(put(parent, 'rewards', document('rewards', { lamport: 5, deleted: true })));
    await assertFails(put(parent, 'rewards', document('rewards', { lamport: 6 })));
    await assertFails(put(parent, 'rewards', document('rewards', { lamport: 600 })));
    // Tombstone over tombstone is last-writer-wins.
    await assertFails(put(parent, 'rewards', document('rewards', { lamport: 4, deleted: true })));
    await assertSucceeds(put(parent, 'rewards', document('rewards', { lamport: 7, deleted: true })));
  });

  it('a parent tombstones a live document with a lower lamport', async () => {
    const parent = asParent(env);
    await assertSucceeds(put(parent, 'chores', document('chores', { lamport: 40 })));
    await assertSucceeds(put(parent, 'chores', document('chores', { lamport: 3, deleted: true })));
  });

  it('a kid cannot tombstone their own submission, even with a higher lamport', async () => {
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 1 })));
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ lamport: 2, deleted: true })));
  });

  it('a parent approves a SUBMITTED submission with a lower lamport', async () => {
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 10 })));
    await assertSucceeds(
      put(asParent(env), 'choreSubmissions', document('choreSubmissions', {
        docId: 'sub-1', lamport: 2,
        payload: { ...payloadFor('choreSubmissions', 'sub-1'), status: 'APPROVED', decidedAt: '2026-11-03T19:00:00Z',
          decidedBy: 'parent-uid' },
      })),
    );
  });

  it('SUBMITTED never replaces APPROVED, even with a higher lamport; equal ranks fall back to LWW', async () => {
    const decided = (status: string, lamport: number) =>
      document('choreSubmissions', {
        docId: 'sub-1', lamport,
        payload: { ...payloadFor('choreSubmissions', 'sub-1'), status, decidedAt: status === 'SUBMITTED' ? null : '2026-11-03T19:00:00Z' },
      });
    const parent = asParent(env);
    await assertSucceeds(put(parent, 'choreSubmissions', decided('APPROVED', 5)));
    await assertFails(put(parent, 'choreSubmissions', decided('SUBMITTED', 50)));
    await assertFails(put(parent, 'choreSubmissions', decided('REJECTED', 50)));
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ lamport: 60 })));
    await assertFails(put(parent, 'choreSubmissions', decided('APPROVED', 4)));
    await assertSucceeds(put(parent, 'choreSubmissions', decided('APPROVED', 6)));
  });

  it('stays within the rules evaluation budget for the largest kid update', async () => {
    const full = {
      note: 'n'.repeat(500),
      photoRef: { fileName: 'f'.repeat(128), sha256: 'a'.repeat(64), blobId: 'b'.repeat(64) },
      choreId: 'c'.repeat(128),
      dueOn: '2026-11-03',
    };
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 1 }, full)));
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 2 }, full)));
    await assertSucceeds(
      put(asParent(env), 'choreSubmissions', document('choreSubmissions', {
        docId: 'sub-1', lamport: 1,
        payload: { ...payloadFor('choreSubmissions', 'sub-1'), ...full, status: 'REJECTED', decidedAt: '2026-11-03T19:00:00Z',
          decidedBy: 'p'.repeat(128), rejectionReason: 'r'.repeat(500) },
      })),
    );
  });

  it('a submission photoRef may name its uploaded blob (blobId, at most 64 characters) and nothing else', async () => {
    const ref = (photoRef: Record<string, unknown>) => submission({ lamport: 1 }, { photoRef });
    await assertSucceeds(put(asKid(env), 'choreSubmissions', ref({ fileName: 'p.jpg', sha256: 'a'.repeat(64), blobId: 'b'.repeat(64) })));
    await assertFails(put(asKid(env), 'choreSubmissions', ref({ fileName: 'p.jpg', blobId: 'b'.repeat(65) })));
    await assertFails(put(asKid(env), 'choreSubmissions', ref({ fileName: 'p.jpg', blobId: 7 })));
    await assertFails(put(asKid(env), 'choreSubmissions', ref({ fileName: 'p.jpg', url: 'https://example.test' })));
  });

  it('a kid cannot change the identity (choreId, dueOn) of their stored submission', async () => {
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 1 })));
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ lamport: 2 }, { choreId: 'chore-2' })));
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ lamport: 2 }, { dueOn: '2026-11-04' })));
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 2 }, { note: 'again' })));
  });

  it('a kid cannot write over a tombstoned submission', async () => {
    await assertSucceeds(
      put(asParent(env), 'choreSubmissions', document('choreSubmissions', {
        docId: 'sub-1', lamport: 1, deleted: true, payload: payloadFor('choreSubmissions', 'sub-1'),
      })),
    );
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ lamport: 50 })));
  });
});

describe('documents: last writer wins', () => {
  beforeEach(async () => {
    await assertSucceeds(put(asParent(env), 'chores', document('chores', { lamport: 5 })));
  });

  it('rejects a lower lamport', async () => {
    await assertFails(put(asParent(env), 'chores', document('chores', { lamport: 4 })));
  });

  it('rejects an equal lamport from the same device', async () => {
    await assertFails(put(asParent(env), 'chores', document('chores', { lamport: 5 })));
  });

  it('breaks an equal-lamport tie on the greater device id', async () => {
    // Seed a copy authored by the kid device ('b'*64) at lamport 7, then the parent device ('a'*64) at 7 loses and
    // at 8 wins.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(
        doc(ctx.firestore(), `households/${HID}/chores/doc-1`),
        document('chores', { lamport: 7, authorDeviceId: KID_DEVICE }),
      );
    });
    await assertFails(put(asParent(env), 'chores', document('chores', { lamport: 7, authorDeviceId: PARENT_DEVICE })));
    await assertSucceeds(
      put(asParent(env), 'chores', document('chores', { lamport: 8, authorDeviceId: PARENT_DEVICE })),
    );
  });

  it('a greater device id wins an equal lamport', async () => {
    // A stored copy from the parent device ('a'*64) at lamport 9 loses to the kid device ('b'*64) at 9.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(
        doc(ctx.firestore(), `households/${HID}/choreSubmissions/sub-1`),
        submission({ lamport: 9, authorUid: 'parent-uid', authorDeviceId: PARENT_DEVICE }),
      );
    });
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 9 })));
  });
});

describe('documents: kid role gates', () => {
  it('a kid creates and updates their own SUBMITTED submission', async () => {
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 1 })));
    await assertSucceeds(put(asKid(env), 'choreSubmissions', submission({ lamport: 2 }, { note: 'done!' })));
  });

  it('a kid cannot write an APPROVED or REJECTED submission', async () => {
    await assertFails(put(asKid(env), 'choreSubmissions', submission({}, { status: 'APPROVED' })));
    await assertFails(put(asKid(env), 'choreSubmissions', submission({}, { status: 'REJECTED' })));
  });

  it("a kid cannot write another kid's submission", async () => {
    await assertFails(put(asKid(env), 'choreSubmissions', submission({}, { kidId: KID2_ID })));
  });

  it("a kid cannot overwrite another kid's stored submission or a decided one", async () => {
    await assertSucceeds(
      put(
        asKid2(env),
        'choreSubmissions',
        submission({ docId: 'sub-2', authorUid: KID2_UID, authorDeviceId: KID2_DEVICE }, { kidId: KID2_ID }),
      ),
    );
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ docId: 'sub-2', lamport: 50 })));

    await assertSucceeds(
      put(
        asParent(env),
        'choreSubmissions',
        document('choreSubmissions', {
          docId: 'sub-3',
          lamport: 3,
          payload: { id: 'sub-3', kidId: KID_ID, status: 'APPROVED' },
        }),
      ),
    );
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ docId: 'sub-3', lamport: 50 })));
  });

  it('a kid cannot tombstone a submission or store it under another id', async () => {
    await assertFails(put(asKid(env), 'choreSubmissions', submission({ deleted: true })));
    await assertFails(
      put(asKid(env), 'choreSubmissions', submission({ docId: 'sub-9' }, { id: 'sub-1' })),
    );
  });

  for (const coll of ['rewards', 'chores', 'allowanceSchedules', 'kids', 'accounts', 'householdPolicy',
    'savingsGoals', 'interestPolicy', 'threadKeys']) {
    it(`a kid cannot write ${coll}`, async () => {
      await assertFails(
        put(asKid(env), coll, document(coll, { authorUid: 'kid-uid', authorDeviceId: KID_DEVICE, lamport: 99 })),
      );
    });
  }

  it('a kid reads household documents', async () => {
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/kids/${KID_ID}`)));
  });
});

describe('documents: cross household', () => {
  it('a parent of another household can neither read nor write', async () => {
    await assertFails(getDoc(doc(asOtherParent(env), `households/${HID}/kids/${KID_ID}`)));
    await assertFails(getDocs(collection(asOtherParent(env), `households/${HID}/kids`)));
    await assertFails(put(asOtherParent(env), 'rewards', document('rewards')));
  });
});

describe('documents: household policy v2 (M7 §1)', () => {
  const policy = (payload: Record<string, unknown>, lamport = 10) =>
    document('householdPolicy', {
      docId: HID,
      lamport,
      payload: { fiatCurrency: 'EUR', requireParentSignature: true, ...payload },
    });

  it('a parent sets the chat retention and the daily game cap within their bounds', async () => {
    const parent = asParent(env);
    await assertSucceeds(put(parent, 'householdPolicy', policy({ chatRetentionDays: 30, dailyGameCapMinor: 120 })));
    await assertSucceeds(put(parent, 'householdPolicy', policy({ chatRetentionDays: 1, dailyGameCapMinor: 0 }, 11)));
    await assertSucceeds(
      put(parent, 'householdPolicy', policy({ chatRetentionDays: 3650, dailyGameCapMinor: 10000 }, 12)),
    );
    // Null or absent: keep messages, default cap.
    await assertSucceeds(put(parent, 'householdPolicy', policy({ chatRetentionDays: null }, 13)));
    await assertSucceeds(put(parent, 'householdPolicy', policy({}, 14)));
  });

  it('refuses values out of bounds, of the wrong type, and unknown keys', async () => {
    const parent = asParent(env);
    for (const payload of [
      { chatRetentionDays: 0 },
      { chatRetentionDays: 3651 },
      { chatRetentionDays: -30 },
      { chatRetentionDays: 30.5 },
      { chatRetentionDays: '30' },
      { dailyGameCapMinor: -1 },
      { dailyGameCapMinor: 10001 },
      { dailyGameCapMinor: true },
      { purgeChatAt: 5 },
    ]) {
      await assertFails(put(parent, 'householdPolicy', policy(payload)));
    }
  });

  // D-REL-23 (audit L2): requireParentSignature is one-way.
  it('requireParentSignature can be turned on but never off again', async () => {
    const parent = asParent(env);
    await assertSucceeds(put(parent, 'householdPolicy', policy({ requireParentSignature: false }, 10)));
    await assertSucceeds(put(parent, 'householdPolicy', policy({ requireParentSignature: false, chatRetentionDays: 30 }, 11)));
    await assertSucceeds(put(parent, 'householdPolicy', policy({ requireParentSignature: true }, 12)));

    await assertFails(put(parent, 'householdPolicy', policy({ requireParentSignature: false }, 13)));
    await assertFails(put(parent, 'householdPolicy', policy({ requireParentSignature: false }, 1_000_000)));
    const absent = policy({}, 14);
    delete (absent.payload as Record<string, unknown>).requireParentSignature;
    await assertFails(put(parent, 'householdPolicy', absent));
    await assertFails(
      put(parent, 'householdPolicy', { ...policy({ requireParentSignature: false }, 15), deleted: true }),
    );

    // Other settings still change with signatures kept on.
    await assertSucceeds(put(parent, 'householdPolicy', policy({ requireParentSignature: true, dailyGameCapMinor: 60 }, 16)));
  });

  it('a kid cannot change either setting', async () => {
    await assertFails(
      put(
        asKid(env),
        'householdPolicy',
        document('householdPolicy', {
          docId: HID,
          authorUid: 'kid-uid',
          authorDeviceId: KID_DEVICE,
          lamport: 99,
          payload: { fiatCurrency: 'EUR', requireParentSignature: false, dailyGameCapMinor: 10000 },
        }),
      ),
    );
  });

  it('prune records are the server\'s only: no client reads or writes them', async () => {
    const parent = asParent(env);
    await assertFails(setDoc(doc(parent, `households/${HID}/prunes/p-1`), { counts: { messages: 0 } }));
    await assertFails(getDoc(doc(parent, `households/${HID}/prunes/p-1`)));
    await assertFails(getDocs(collection(parent, `households/${HID}/prunes`)));
    // Messages and blobs stay create-only for clients: the server prunes them.
    await assertFails(deleteDoc(doc(parent, `households/${HID}/messages/m-1`)));
    await assertFails(deleteDoc(doc(parent, `households/${HID}/blobs/b-1.0`)));
  });

  it('the chat prune watermark on the household root is readable by members and written by the server only', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore() as unknown as Firestore, `households/${HID}`), {
        chatPrune: { prunedBefore: Timestamp.fromMillis(1_000), prunedAt: Timestamp.now() },
      });
    });
    for (const member of [asParent(env), asKid(env)]) {
      await assertSucceeds(getDoc(doc(member, `households/${HID}`)));
      await assertFails(updateDoc(doc(member, `households/${HID}`), { 'chatPrune.prunedBefore': Timestamp.now() }));
    }
    await assertFails(getDoc(doc(asOtherParent(env), `households/${HID}`)));
  });
});
