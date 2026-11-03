import { beforeEach, describe, expect, it } from 'vitest';
import { DOCUMENT_COLLECTIONS } from '../src/config.js';
import { sha256Hex } from '../src/crypto.js';
import {
  addKid,
  auth,
  call,
  db,
  expectCode,
  fns,
  linkKid,
  newDevice,
  newHousehold,
  newUser,
  req,
  resetEmulators,
  signed,
  withProof,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});

describe('createHousehold', () => {
  it('creates the household, the parent membership and an ACTIVE device, and sets PARENT claims', async () => {
    const uid = await newUser();
    const device = newDevice();
    const result = await call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: device.publicKey }));
    expect(result.deviceId).toBe(device.deviceId);
    expect(result.deviceId).toMatch(/^[0-9a-f]{64}$/);

    const household = await db.doc(`households/${result.hid}`).get();
    expect(household.get('createdBy')).toBe(uid);
    expect(household.get('fiatCurrency')).toBe('EUR');
    expect((await db.doc(`households/${result.hid}/members/${uid}`).get()).get('role')).toBe('PARENT');
    const dev = (await db.doc(`households/${result.hid}/devices/${device.deviceId}`).get()).data();
    expect(dev).toMatchObject({
      uid,
      role: 'PARENT',
      publicKey: device.publicKey,
      status: 'ACTIVE',
      attestation: 'NONE',
      lamport: 0,
    });
    expect((await auth.getUser(uid)).customClaims).toEqual({ hid: result.hid, role: 'PARENT' });
  });

  it('refuses anonymous users, users already in a household and bad input', async () => {
    const device = newDevice();
    const anon = await newUser();
    await expectCode(
      call(fns.createHousehold, req({ uid: anon, anonymous: true }, { fiatCurrency: 'EUR', devicePublicKey: device.publicKey })),
      'permission-denied',
    );
    const h = await newHousehold();
    await expectCode(
      call(fns.createHousehold, req(h.parent, { fiatCurrency: 'EUR', devicePublicKey: device.publicKey })),
      'already-exists',
    );
    // A stale token without the claims is caught by the live claims check.
    await expectCode(
      call(fns.createHousehold, req({ uid: h.parent.uid }, { fiatCurrency: 'EUR', devicePublicKey: device.publicKey })),
      'already-exists',
    );
    const uid = await newUser();
    await expectCode(call(fns.createHousehold, req({ uid }, { fiatCurrency: 'euro', devicePublicKey: device.publicKey })), 'invalid-argument');
    await expectCode(call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: 'not base64!' })), 'invalid-argument');
    const { generateKeyPairSync } = await import('node:crypto');
    const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).publicKey.export({ format: 'der', type: 'spki' });
    await expectCode(
      call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: rsa.toString('base64') })),
      'invalid-argument',
    );
    await expectCode(call(fns.createHousehold, req(null, { fiatCurrency: 'EUR' })), 'unauthenticated');
  });

  it('is rate limited per uid', async () => {
    const uid = await newUser();
    for (let i = 0; i < 5; i++) {
      await expectCode(call(fns.createHousehold, req({ uid }, { fiatCurrency: 'bad' })), 'invalid-argument');
    }
    await expectCode(call(fns.createHousehold, req({ uid }, { fiatCurrency: 'bad' })), 'resource-exhausted');
    const buckets = await db.collection('rateLimits').get();
    for (const b of buckets.docs) {
      expect(b.id).not.toContain(uid);
      expect(JSON.stringify(b.data())).not.toContain(uid);
    }
  });
});

describe('exportHousehold', () => {
  it('returns the whole household as one bundle', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await db.doc(`households/${h.hid}/events/e1`).set({ eventId: 'e1', authorDeviceId: linked.device.deviceId, lamport: 2 });
    await db.doc(`households/${h.hid}/events/e0`).set({ eventId: 'e0', authorDeviceId: h.device.deviceId, lamport: 1 });
    await db.doc(`households/${h.hid}/rewards/r1`).set({ docId: 'r1', docType: 'reward', lamport: 1, payload: { title: 'Ice cream' } });
    await db.doc(`households/${h.hid}/parentKeys/${'7'.repeat(64)}`).set({ deviceId: h.device.deviceId, uid: h.parent.uid });

    const bundle = await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold')));
    expect(Object.keys(bundle).sort()).toEqual(
      ['collections', 'devices', 'events', 'exportedAt', 'household', 'members', 'parentKeys'].sort(),
    );
    expect(Object.keys(bundle.collections).sort()).toEqual(Object.keys(DOCUMENT_COLLECTIONS).sort());
    expect(bundle.household).toMatchObject({ id: h.hid, fiatCurrency: 'EUR', createdBy: h.parent.uid });
    expect(typeof bundle.household.createdAt).toBe('string');
    expect(bundle.members.map((m) => m.role).sort()).toEqual(['KID', 'PARENT']);
    expect(bundle.devices).toHaveLength(2);
    expect(bundle.parentKeys).toHaveLength(1);
    expect(bundle.events.map((e) => e.id)).toEqual(['e0', 'e1']);
    expect(bundle.collections.rewards).toEqual([
      { id: 'r1', docId: 'r1', docType: 'reward', lamport: 1, payload: { title: 'Ice cream' } },
    ]);
    expect(bundle.collections.kids).toHaveLength(1);
    expect(() => JSON.stringify(bundle)).not.toThrow();
  });

  it('is for parents only', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await expectCode(call(fns.exportHousehold, req(linked.kid)), 'permission-denied');
    const stranger = await newUser();
    await expectCode(call(fns.exportHousehold, req({ uid: stranger, claims: { hid: h.hid, role: 'PARENT' } })), 'permission-denied');
  });

  it('refuses a household above the export limit (4 MB)', async () => {
    const h = await newHousehold();
    const blob = 'x'.repeat(900_000);
    const writer = db.bulkWriter();
    for (let i = 0; i < 11; i++) void writer.set(db.doc(`households/${h.hid}/chores/c${i}`), { payload: { blob } });
    await writer.close();
    await expectCode(call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold'))), 'resource-exhausted');
  });
});

describe('deleteHousehold', () => {
  it('deletes the household and every member user, leaving only a deletion record', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await addKid(h.hid, 'kid-b', 'Otto');
    await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId: 'kid-b' })));
    await call(fns.beginDeviceRegistration, req(h.parent));
    await db.doc(`households/${h.hid}/events/e1`).set({ eventId: 'e1', lamport: 1 });
    await db.doc(`households/${h.hid}/chores/c1`).set({ docId: 'c1' });

    const other = await newHousehold('198.51.100.1');

    await expectCode(call(fns.deleteHousehold, req(h.parent, { confirmation: 'nope' })), 'invalid-argument');
    await expectCode(call(fns.deleteHousehold, req(linked.kid, { confirmation: h.hid })), 'permission-denied');
    expect(
      await call(fns.deleteHousehold, req(h.parent, withProof(h, 'deleteHousehold', { confirmation: h.hid }))),
    ).toEqual({ deleted: true });

    const remaining = await db.doc(`households/${h.hid}`).listCollections();
    expect(remaining).toHaveLength(0);
    expect((await db.doc(`households/${h.hid}`).get()).exists).toBe(false);
    expect((await db.collection('linkCodes').where('hid', '==', h.hid).get()).size).toBe(0);
    expect((await db.doc(`challenges/${h.parent.uid}`).get()).exists).toBe(false);
    await expect(auth.getUser(h.parent.uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    await expect(auth.getUser(linked.kid.uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });

    const records = await db.collection('deletionRecords').get();
    expect(records.size).toBe(1);
    const record = records.docs[0].data();
    expect(Object.keys(record).sort()).toEqual(['at', 'counts', 'hidHash']);
    expect(record.hidHash).toBe(sha256Hex(h.hid));
    expect(record.counts).toEqual({ members: 2, devices: 2, events: 1, documents: 3 });
    expect(JSON.stringify(record)).not.toContain(h.hid);

    // Apart from the deletion record (and hashed rate-limit rows) nothing of the household remains; the other
    // household is untouched.
    const topLevel = (await db.listCollections()).map((c) => c.id).sort();
    expect(topLevel).toEqual(['deletionRecords', 'households', 'rateLimits', 'userHouseholds']);
    expect((await db.collection('userHouseholds').get()).docs.map((d) => d.id)).toEqual([other.parent.uid]);
    expect((await db.collection('households').get()).docs.map((d) => d.id)).toEqual([other.hid]);
    expect((await auth.getUser(other.parent.uid)).uid).toBe(other.parent.uid);
  });
});

describe('createHousehold: one household per uid', () => {
  it('records userHouseholds/{uid} and refuses a uid that already has a row', async () => {
    const h = await newHousehold();
    expect((await db.doc(`userHouseholds/${h.parent.uid}`).get()).data()).toEqual({ hid: h.hid });
    const uid = await newUser();
    await db.doc(`userHouseholds/${uid}`).set({ hid: 'elsewhere' });
    await expectCode(
      call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: newDevice().publicKey })),
      'already-exists',
    );
    expect((await auth.getUser(uid)).customClaims ?? {}).toEqual({});
  });
});

describe('exportHousehold device proof', () => {
  it('requires the calling device\'s signature', async () => {
    const h = await newHousehold();
    await expectCode(call(fns.exportHousehold, req(h.parent)), 'invalid-argument');
    await expectCode(call(fns.exportHousehold, req(h.parent, signed(newDevice(), 'exportHousehold', h.hid))), 'permission-denied');
    await expectCode(
      call(fns.exportHousehold, req(h.parent, { ...signed(newDevice(), 'exportHousehold', h.hid), callerDeviceId: h.device.deviceId })),
      'permission-denied',
    );
    await expectCode(
      call(fns.exportHousehold, req(h.parent, signed(h.device, 'exportHousehold', 'another-household'))),
      'permission-denied',
    );
  });
});

describe('deleteHousehold: proof, recent sign-in and complete erasure', () => {
  it('requires the calling device\'s signature and a sign-in within 5 minutes', async () => {
    const h = await newHousehold();
    const args = { confirmation: h.hid };
    await expectCode(call(fns.deleteHousehold, req(h.parent, args)), 'invalid-argument');
    await expectCode(
      call(fns.deleteHousehold, req(h.parent, { ...args, ...signed(h.device, 'deleteHousehold', h.hid, { confirmation: 'x' }) })),
      'permission-denied',
    );
    const stale = Math.floor(Date.now() / 1000) - 6 * 60;
    await expectCode(
      call(fns.deleteHousehold, req({ ...h.parent, authTime: stale }, withProof(h, 'deleteHousehold', args))),
      'failed-precondition',
    );
    expect((await db.doc(`households/${h.hid}`).get()).exists).toBe(true);
  });

  it('erases revoked kids too: their user, challenge and userHouseholds row, found through the device registry', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })));
    expect((await db.doc(`households/${h.hid}/members/${linked.kid.uid}`).get()).exists).toBe(false);
    await db.doc(`challenges/${linked.kid.uid}`).set({ hash: 'x' });

    await call(fns.deleteHousehold, req(h.parent, withProof(h, 'deleteHousehold', { confirmation: h.hid })));
    await expect(auth.getUser(linked.kid.uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    await expect(auth.getUser(h.parent.uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    expect((await db.doc(`challenges/${linked.kid.uid}`).get()).exists).toBe(false);
    expect((await db.collection('userHouseholds').get()).size).toBe(0);
    expect((await db.doc(`households/${h.hid}`).listCollections())).toHaveLength(0);
  });
});
