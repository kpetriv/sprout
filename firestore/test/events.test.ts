import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, setDoc, updateDoc, serverTimestamp, type Firestore } from 'firebase/firestore';
import {
  asKid,
  asOtherParent,
  asParent,
  asParentSignedInAt,
  asStranger,
  clean,
  document,
  event,
  gamePlayed,
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

  it('a kid creates a game_played event for their own kid with the existing key set (M5)', async () => {
    await assertSucceeds(put(asKid(env), gamePlayed()));
    // The same keys a kid already writes: the ledger, not the rules, judges the credit (ADR-0009).
    await assertFails(put(asKid(env), gamePlayed({ eventId: 'evt-game-2', kidId: KID2_ID })));
  });

  it('a parent stores a conflicting copy under eventId__hash; a server-stamped event is accepted', async () => {
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
    await assertFails(put(parent, event({ reason: 'x'.repeat(501) })));
    await assertFails(put(parent, event({ note: 'x'.repeat(501) })));
    await assertFails(put(parent, event({ type: 'overdraft_compensated', causedBy: Array(65).fill('e') })));
    await assertSucceeds(put(parent, event({ type: 'overdraft_compensated', causedBy: ['e1'], note: 'ok' })));
  });

  it('bounds causedBy to 16 ids of at most 128 characters', async () => {
    const parent = asParent(env);
    const comp = (eventId: string, causedBy: unknown) =>
      put(parent, event({ eventId, type: 'overdraft_compensated', reason: undefined, causedBy }));
    await assertSucceeds(comp('c-16', Array.from({ length: 16 }, (_, i) => `e${i}`.padEnd(128, 'x'))));
    await assertFails(comp('c-17', Array.from({ length: 17 }, (_, i) => `e${i}`)));
    await assertFails(comp('c-long', ['e1', 'x'.repeat(129)]));
    await assertFails(comp('c-last-long', [...Array.from({ length: 15 }, (_, i) => `e${i}`), 'x'.repeat(129)]));
    await assertFails(comp('c-empty', ['e1', '']));
    await assertFails(comp('c-int', ['e1', 7]));
  });

  it('stays within the rules evaluation budget with every optional field set', async () => {
    const everything = {
      parentSignature: 'MEUCIQ-parent', currency: 'EUR', amount: 1, choreId: 'c'.repeat(128), dueOn: '2026-11-03',
      scheduleId: 's'.repeat(128), periodKey: '2026-W45', gameId: 'g'.repeat(128), score: 9, playedOn: '2026-11-03',
      rewardId: 'r'.repeat(128), minutesGranted: 30, reason: 'x'.repeat(500), toAccountId: 'a'.repeat(256),
      principal: 100, annualRateBasisPoints: 200, days: 30, sessionId: 's'.repeat(128), minutesUsed: 5, minutes: 5,
      grantedBy: 'g'.repeat(128), note: 'n'.repeat(500),
      causedBy: Array.from({ length: 16 }, (_, i) => `${i}`.padEnd(128, '-')),
    };
    await assertSucceeds(put(asKid(env), kidEvent({ ...everything, eventId: 'evt-kid-max' })));
    await assertSucceeds(put(asParent(env), event({ ...everything, eventId: 'evt-max' }), `evt-max__${HASH}`));
  });

  it('requires serverReceivedAt stamped with the server time', async () => {
    await assertFails(put(asParent(env), event({ serverReceivedAt: undefined })));
    await assertFails(put(asParent(env), event({ serverReceivedAt: new Date(0) })));
  });

  it('a kid cannot store a conflicting copy under eventId__hash', async () => {
    await assertFails(put(asKid(env), kidEvent(), `evt-kid-1__${HASH}`));
    await assertSucceeds(put(asKid(env), kidEvent()));
  });
});

describe('device revocation freshness (tokensValidAfter)', () => {
  const REVOKED_AT = 1_800_000_000;

  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore() as unknown as Firestore, `households/${HID}/members/${PARENT_UID}`), {
        tokensValidAfter: REVOKED_AT,
      });
    });
  });

  it('a session signed in before the revocation can no longer write events or documents', async () => {
    const stale = asParentSignedInAt(env, REVOKED_AT - 1);
    await assertFails(put(stale, event()));
    await assertFails(setDoc(doc(stale, `households/${HID}/rewards/doc-1`), clean(document('rewards'))));
    // Reads are membership-based and unaffected.
    await assertSucceeds(getDoc(doc(stale, `households/${HID}/kids/kid-a`)));
  });

  it('a session signed in at or after the revocation writes as before', async () => {
    await assertSucceeds(put(asParentSignedInAt(env, REVOKED_AT), event()));
    await assertSucceeds(
      setDoc(doc(asParentSignedInAt(env, REVOKED_AT + 60), `households/${HID}/rewards/doc-1`), clean(document('rewards'))),
    );
  });

  it('members without tokensValidAfter are unaffected', async () => {
    await assertSucceeds(put(asKid(env), kidEvent()));
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
