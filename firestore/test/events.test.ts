import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, setDoc, updateDoc, serverTimestamp, type Firestore } from 'firebase/firestore';
import {
  asKid,
  asOtherParent,
  asParent,
  asStranger,
  clean,
  event,
  HASH,
  HID,
  KID2_ID,
  KID_DEVICE,
  KID_UID,
  kidEvent,
  OTHER_HID,
  PARENT_DEVICE,
  PARENT_UID,
  PENDING_DEVICE,
  REVOKED_DEVICE,
  seed,
  setupEnv,
  UNREGISTERED_DEVICE,
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

const put = (db: Firestore, data: Record<string, unknown>, id = data.eventId as string, hid = HID) =>
  setDoc(doc(db, `households/${hid}/events/${id}`), clean(data));

describe('events: create', () => {
  it('a parent creates a PARENT-role event from its ACTIVE device', async () => {
    await assertSucceeds(put(asParent(env), event()));
  });

  it('a parent creates a KID-role event (kid mode on a parent device)', async () => {
    await assertSucceeds(put(asParent(env), event({ authorRole: 'KID' })));
  });

  it('a kid creates a KID-role event for their own kid from their device', async () => {
    await assertSucceeds(put(asKid(env), kidEvent()));
  });

  it('accepts a conflicting copy stored under eventId__hash and a server-stamped event', async () => {
    await assertSucceeds(put(asParent(env), event(), `evt-1__${HASH}`));
    await assertSucceeds(put(asParent(env), event({ eventId: 'evt-2', serverReceivedAt: serverTimestamp() })));
  });

  it('accepts schema version 1 without parentSignature', async () => {
    await assertSucceeds(put(asParent(env), event({ schemaVersion: 1, parentSignature: undefined })));
  });

  it('is denied from an unregistered device', async () => {
    await assertFails(put(asParent(env), event({ authorDeviceId: UNREGISTERED_DEVICE })));
  });

  it('is denied from a PENDING device', async () => {
    await assertFails(put(asParent(env), event({ authorDeviceId: PENDING_DEVICE })));
  });

  it('is denied from a REVOKED device', async () => {
    await assertFails(put(asParent(env), event({ authorDeviceId: REVOKED_DEVICE })));
  });

  it('is denied from another uid\'s ACTIVE device', async () => {
    await assertFails(put(asParent(env), event({ authorDeviceId: KID_DEVICE })));
  });

  it('is denied when authorUid is not the caller', async () => {
    await assertFails(put(asParent(env), event({ authorUid: KID_UID })));
    await assertFails(put(asKid(env), kidEvent({ authorUid: PARENT_UID })));
  });

  it('a PARENT-role event from a KID member is denied', async () => {
    await assertFails(put(asKid(env), kidEvent({ authorRole: 'PARENT' })));
  });

  it('a kid cannot write an event for another kid', async () => {
    await assertFails(put(asKid(env), kidEvent({ kidId: KID2_ID })));
  });

  it('is denied for another household, by a non-member and for a mismatched householdId', async () => {
    await assertFails(put(asOtherParent(env), event({ authorDeviceId: PARENT_DEVICE })));
    await assertFails(put(asStranger(env), event()));
    await assertFails(put(asParent(env), event({ householdId: OTHER_HID })));
    await assertFails(put(asParent(env), event({ householdId: OTHER_HID }), 'evt-1', OTHER_HID));
  });

  it('enforces the schema', async () => {
    const parent = asParent(env);
    await assertFails(put(parent, event({ schemaVersion: 3 })));
    await assertFails(put(parent, event({ lamport: -1 })));
    await assertFails(put(parent, event({ lamport: 1.5 })));
    await assertFails(put(parent, event({ hash: 'not-hex' })));
    await assertFails(put(parent, event({ signature: undefined })));
    await assertFails(put(parent, event({ prevHash: undefined })));
    await assertFails(put(parent, event({ authorRole: 'ADMIN' })));
    await assertFails(put(parent, event({ unexpected: 'field' })));
    await assertFails(put(parent, event(), 'some-other-id'));
    await assertFails(put(parent, event({ serverReceivedAt: 12 })));
  });

  it('enforces size bounds on free text and lists', async () => {
    const parent = asParent(env);
    await assertFails(put(parent, event({ reason: 'x'.repeat(401) })));
    await assertFails(put(parent, event({ type: 'overdraft_compensated', causedBy: Array(65).fill('e') })));
    await assertSucceeds(put(parent, event({ type: 'overdraft_compensated', causedBy: ['e1'], note: 'ok' })));
  });
});

describe('events: immutable', () => {
  beforeEach(async () => {
    await assertSucceeds(put(asParent(env), event()));
  });

  it('cannot be updated, even by its author', async () => {
    await assertFails(updateDoc(doc(asParent(env), `households/${HID}/events/evt-1`), { amount: 999_999 }));
  });

  it('cannot be overwritten with a set', async () => {
    await assertFails(put(asParent(env), event({ amount: 999_999, lamport: 2 })));
  });

  it('cannot be deleted', async () => {
    await assertFails(deleteDoc(doc(asParent(env), `households/${HID}/events/evt-1`)));
  });

  it('is readable by members only', async () => {
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/events/evt-1`)));
    await assertFails(getDoc(doc(asOtherParent(env), `households/${HID}/events/evt-1`)));
  });
});
