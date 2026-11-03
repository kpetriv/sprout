import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { LINK_CODE_ALPHABET, linkCodeId } from '../src/crypto.js';
import {
  addKid,
  auth,
  call,
  db,
  expectCode,
  fns,
  newDevice,
  newHousehold,
  newUser,
  req,
  resetEmulators,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});

const CODE = new RegExp(`^[${LINK_CODE_ALPHABET}]{8}$`);

async function kidCaller(ip?: string) {
  return { uid: await newUser(), anonymous: true, ip };
}

describe('createLinkCode', () => {
  it('returns an 8-character code stored only as its hash, valid for 10 minutes', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code, expiresAt } = await call(fns.createLinkCode, req(h.parent, { kidId }));
    expect(code).toMatch(CODE);
    expect(expiresAt - Date.now()).toBeGreaterThan(9 * 60_000);
    expect(expiresAt - Date.now()).toBeLessThanOrEqual(10 * 60_000);
    const stored = await db.doc(`linkCodes/${linkCodeId(code)}`).get();
    expect(stored.data()).toMatchObject({ hid: h.hid, kidId, createdBy: h.parent.uid, used: false });
    const all = await db.collection('linkCodes').get();
    for (const d of all.docs) {
      expect(d.id).not.toContain(code);
      expect(JSON.stringify(d.data())).not.toContain(code);
    }
  });

  it('requires an existing kid and a parent caller', async () => {
    const h = await newHousehold();
    await expectCode(call(fns.createLinkCode, req(h.parent, { kidId: 'nobody' })), 'not-found');
    await expectCode(call(fns.createLinkCode, req(h.parent, { kidId: '../x' })), 'invalid-argument');
    const uid = await newUser();
    await expectCode(call(fns.createLinkCode, req({ uid }, { kidId: 'kid-a' })), 'permission-denied');
  });

  it('allows at most 5 live codes per household', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    for (let i = 0; i < 5; i++) await call(fns.createLinkCode, req(h.parent, { kidId }));
    await expectCode(call(fns.createLinkCode, req(h.parent, { kidId })), 'resource-exhausted');
    // Expired codes do not count.
    const live = await db.collection('linkCodes').get();
    await live.docs[0].ref.update({ expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await call(fns.createLinkCode, req(h.parent, { kidId }));
  });
});

describe('redeemLinkCode', () => {
  it('links an anonymous kid device: membership, ACTIVE device and KID claims', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, { kidId }));
    const kid = await kidCaller();
    const device = newDevice();
    // Lower case with a dash is the same code.
    const typed = `${code.slice(0, 4)}-${code.slice(4)}`.toLowerCase();
    const result = await call(fns.redeemLinkCode, req(kid, { code: typed, devicePublicKey: device.publicKey }));
    expect(result).toEqual({ hid: h.hid, kidId, deviceId: device.deviceId });
    expect((await db.doc(`households/${h.hid}/members/${kid.uid}`).get()).data()).toMatchObject({ role: 'KID', kidId });
    expect((await db.doc(`households/${h.hid}/devices/${device.deviceId}`).get()).data()).toMatchObject({
      uid: kid.uid,
      role: 'KID',
      kidId,
      status: 'ACTIVE',
      attestation: 'NONE',
    });
    expect((await auth.getUser(kid.uid)).customClaims).toEqual({ hid: h.hid, role: 'KID', kidId });
    expect((await db.doc(`linkCodes/${linkCodeId(code)}`).get()).get('used')).toBe(true);
  });

  it('a code works once', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, { kidId }));
    await call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey }));
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey })),
      'not-found',
    );
  });

  it('an expired code is refused', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, { kidId }));
    await db.doc(`linkCodes/${linkCodeId(code)}`).update({ expiresAt: Timestamp.fromMillis(Date.now() - 1) });
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey })),
      'not-found',
    );
  });

  it('a wrong or malformed code is refused', async () => {
    const h = await newHousehold();
    await addKid(h.hid);
    await call(fns.createLinkCode, req(h.parent, { kidId: 'kid-a' }));
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(), { code: 'ABCDEFGH', devicePublicKey: newDevice().publicKey })),
      'not-found',
    );
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(), { code: 'ABC0EFGH', devicePublicKey: newDevice().publicKey })),
      'invalid-argument',
    );
  });

  it('only an anonymous user without a household can redeem', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, { kidId }));
    const device = newDevice();
    await expectCode(
      call(fns.redeemLinkCode, req(h.parent, { code, devicePublicKey: device.publicKey })),
      'permission-denied',
    );
    const linked = await kidCaller();
    await auth.setCustomUserClaims(linked.uid, { hid: 'elsewhere', role: 'KID', kidId: 'k' });
    await expectCode(
      call(fns.redeemLinkCode, req(linked, { code, devicePublicKey: device.publicKey })),
      'failed-precondition',
    );
    expect((await db.doc(`linkCodes/${linkCodeId(code)}`).get()).get('used')).toBe(false);
  });

  it('allows 10 attempts per IP per hour', async () => {
    const ip = '192.0.2.44';
    for (let i = 0; i < 10; i++) {
      await expectCode(
        call(fns.redeemLinkCode, req(await kidCaller(ip), { code: 'ABCDEFGH', devicePublicKey: newDevice().publicKey })),
        'not-found',
      );
    }
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(ip), { code: 'ABCDEFGH', devicePublicKey: newDevice().publicKey })),
      'resource-exhausted',
    );
    // Another IP is not affected.
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller('192.0.2.45'), { code: 'ABCDEFGH', devicePublicKey: newDevice().publicKey })),
      'not-found',
    );
  });

  it('allows 20 attempts per code hash per hour across IPs', async () => {
    for (let i = 0; i < 20; i++) {
      await expectCode(
        call(fns.redeemLinkCode, req(await kidCaller(`192.0.2.${i}`), { code: 'ZZZZZZZZ', devicePublicKey: newDevice().publicKey })),
        'not-found',
      );
    }
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller('192.0.2.100'), { code: 'ZZZZZZZZ', devicePublicKey: newDevice().publicKey })),
      'resource-exhausted',
    );
  });
});
