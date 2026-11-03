import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, type Firestore } from 'firebase/firestore';
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
      await assertSucceeds(put(parent, coll, document(coll, { docId: 'p-1', lamport: 1 })));
      await assertSucceeds(
        put(parent, coll, document(coll, { docId: 'p-1', lamport: 2, serverReceivedAt: serverTimestamp() })),
      );
      await assertSucceeds(put(parent, coll, document(coll, { docId: 'p-1', lamport: 3, deleted: true })));
      await assertSucceeds(getDoc(doc(parent, `households/${HID}/${coll}/p-1`)));
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
    'savingsGoals', 'interestPolicy']) {
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
