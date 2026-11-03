import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc, type Firestore } from 'firebase/firestore';
import {
  asAnonymous,
  asKid,
  asOtherParent,
  asParent,
  asStranger,
  HID,
  KID_DEVICE,
  KID_UID,
  OTHER_HID,
  PARENT_DEVICE,
  PARENT_UID,
  PENDING_DEVICE,
  REVOKED_DEVICE,
  seed,
  serverTimestamp,
  setupEnv,
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

const FINGERPRINT = '7'.repeat(64);

function parentKey(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    publicKey: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE',
    uid: PARENT_UID,
    deviceId: PARENT_DEVICE,
    validFromLamport: 10,
    registeredAt: serverTimestamp(),
    ...overrides,
  };
}

const putKey = (db: Firestore, data: Record<string, unknown>, fp = FINGERPRINT) =>
  setDoc(doc(db, `households/${HID}/parentKeys/${fp}`), data);

describe('parentKeys', () => {
  it('a parent registers a key for its ACTIVE device', async () => {
    await assertSucceeds(putKey(asParent(env), parentKey()));
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/parentKeys/${FINGERPRINT}`)));
  });

  it('a kid cannot create a parent key', async () => {
    await assertFails(putKey(asKid(env), parentKey({ uid: KID_UID, deviceId: KID_DEVICE })));
  });

  it('a parent cannot register a key for a PENDING, REVOKED or foreign device, or another uid', async () => {
    const parent = asParent(env);
    await assertFails(putKey(parent, parentKey({ deviceId: PENDING_DEVICE })));
    await assertFails(putKey(parent, parentKey({ deviceId: REVOKED_DEVICE })));
    await assertFails(putKey(parent, parentKey({ deviceId: KID_DEVICE })));
    await assertFails(putKey(parent, parentKey({ uid: KID_UID })));
  });

  it('rejects revokedAtLamport from a client, a malformed fingerprint and a client-chosen registeredAt', async () => {
    const parent = asParent(env);
    await assertFails(putKey(parent, parentKey({ revokedAtLamport: 1 })));
    await assertFails(putKey(parent, parentKey(), 'not-a-fingerprint'));
    await assertFails(putKey(parent, parentKey({ registeredAt: new Date(0) })));
  });

  it('a parent cannot update or delete a key', async () => {
    await assertSucceeds(putKey(asParent(env), parentKey()));
    const ref = doc(asParent(env), `households/${HID}/parentKeys/${FINGERPRINT}`);
    await assertFails(updateDoc(ref, { revokedAtLamport: 99 }));
    await assertFails(updateDoc(ref, { validFromLamport: 0 }));
    await assertFails(deleteDoc(ref));
  });
});

describe('devices and members', () => {
  it('members read devices; nobody writes them', async () => {
    await assertSucceeds(getDocs(collection(asKid(env), `households/${HID}/devices`)));
    await assertFails(
      setDoc(doc(asParent(env), `households/${HID}/devices/${'8'.repeat(64)}`), {
        uid: PARENT_UID,
        status: 'ACTIVE',
      }),
    );
    await assertFails(updateDoc(doc(asParent(env), `households/${HID}/devices/${PENDING_DEVICE}`), { status: 'ACTIVE' }));
    await assertFails(getDocs(collection(asOtherParent(env), `households/${HID}/devices`)));
  });

  it('a member reads only their own member row; nobody writes members', async () => {
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}/members/${KID_UID}`)));
    await assertFails(getDoc(doc(asKid(env), `households/${HID}/members/${PARENT_UID}`)));
    await assertFails(getDocs(collection(asParent(env), `households/${HID}/members`)));
    await assertFails(updateDoc(doc(asKid(env), `households/${HID}/members/${KID_UID}`), { role: 'PARENT' }));
    await assertFails(setDoc(doc(asStranger(env), `households/${HID}/members/stranger-uid`), { role: 'PARENT' }));
  });

  it('the household doc is readable by members only and never writable', async () => {
    await assertSucceeds(getDoc(doc(asKid(env), `households/${HID}`)));
    await assertFails(getDoc(doc(asOtherParent(env), `households/${HID}`)));
    await assertFails(getDoc(doc(asParent(env), `households/${OTHER_HID}`)));
    await assertFails(updateDoc(doc(asParent(env), `households/${HID}`), { fiatCurrency: 'USD' }));
    await assertFails(setDoc(doc(asStranger(env), 'households/new-household'), { createdBy: 'stranger-uid' }));
  });
});

describe('top-level collections are closed', () => {
  for (const path of ['linkCodes/abc', `challenges/${PARENT_UID}`, 'deletionRecords/r1', 'rateLimits/b1']) {
    it(`${path} is unreadable and unwritable, even by members`, async () => {
      await assertFails(getDoc(doc(asParent(env), path)));
      await assertFails(getDoc(doc(asKid(env), path)));
      await assertFails(getDoc(doc(asAnonymous(env), path)));
      await assertFails(getDocs(collection(asParent(env), path.split('/')[0])));
      await assertFails(setDoc(doc(asParent(env), path), { x: 1 }));
    });
  }
});
