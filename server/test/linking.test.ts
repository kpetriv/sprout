import { createHmac, hkdfSync } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { linkCodeKey } from '../src/config.js';
import { rateLimitBucket } from '../src/rateLimit.js';
import { LINK_CODE_ALPHABET, linkCodeId as hmacId, sha256Hex } from '../src/crypto.js';
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
  signed,
  withProof,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});

const linkCodeId = (code: string) => hmacId(code, linkCodeKey());

/** The rate-limit id digest, computed independently of src/rateLimit.ts. */
const rateLimitHmac = (secret: string, message: string) =>
  createHmac('sha256', Buffer.from(hkdfSync('sha256', secret, Buffer.alloc(0), 'sprout/rate-limit/v1', 32)))
    .update(message)
    .digest('hex')
    .slice(0, 32);

const CODE = new RegExp(`^[${LINK_CODE_ALPHABET}]{8}$`);

async function kidCaller(ip?: string) {
  return { uid: await newUser(), anonymous: true, ip };
}

describe('createLinkCode', () => {
  it('returns an 8-character code stored only as its hash, valid for 10 minutes', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code, expiresAt } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
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
    await expectCode(call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId: 'nobody' }))), 'not-found');
    await expectCode(call(fns.createLinkCode, req(h.parent, { kidId: '../x' })), 'invalid-argument');
    const uid = await newUser();
    await expectCode(call(fns.createLinkCode, req({ uid }, { kidId: 'kid-a' })), 'permission-denied');
  });

  it('allows at most 5 live codes per household', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    for (let i = 0; i < 5; i++) await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    await expectCode(call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId }))), 'resource-exhausted');
    // Expired codes do not count.
    const live = await db.collection('linkCodes').get();
    await live.docs[0].ref.update({ expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
  });
});

describe('redeemLinkCode', () => {
  it('links an anonymous kid device: membership, ACTIVE device and KID claims', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
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
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    await call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey }));
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey })),
      'not-found',
    );
  });

  it('an expired code is refused', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    await db.doc(`linkCodes/${linkCodeId(code)}`).update({ expiresAt: Timestamp.fromMillis(Date.now() - 1) });
    await expectCode(
      call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey })),
      'not-found',
    );
  });

  it('a wrong or malformed code is refused', async () => {
    const h = await newHousehold();
    await addKid(h.hid);
    await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId: 'kid-a' })));
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
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
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

describe('link code ids are HMACs under LINK_CODE_HMAC_KEY', () => {
  beforeEach(() => {
    delete process.env.LINK_CODE_HMAC_KEY;
  });
  afterEach(() => {
    process.env.SPROUT_EMULATOR = 'true';
    delete process.env.LINK_CODE_HMAC_KEY;
  });

  it('stores HMAC-SHA256(key, code), not a plain hash; the emulator uses the fixed dev key', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    const ids = (await db.collection('linkCodes').get()).docs.map((d) => d.id);
    expect(ids).toEqual([hmacId(code, 'sprout-emulator-only-link-code-key-not-a-secret')]);
    expect(ids).not.toContain(sha256Hex(code));
    // The per-code rate-limit bucket keys on the HMAC as well, and its id is itself keyed (audit L4).
    await call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey }));
    const bucket = `redeemLinkCode_code_${rateLimitHmac('sprout-emulator-only-link-code-key-not-a-secret', `redeemLinkCode|code|${ids[0]}`)}`;
    expect(rateLimitBucket('redeemLinkCode', 'code', ids[0])).toBe(bucket);
    expect((await db.doc(`rateLimits/${bucket}`).get()).exists).toBe(true);
    const unkeyed = `redeemLinkCode_code_${sha256Hex(`redeemLinkCode|code|${ids[0]}`).slice(0, 32)}`;
    expect((await db.doc(`rateLimits/${unkeyed}`).get()).exists).toBe(false);
  });

  it('keys rate-limit ids with HKDF(LINK_CODE_HMAC_KEY, "sprout/rate-limit/v1"), so an IP cannot be enumerated back', () => {
    const ip = '203.0.113.7';
    expect(rateLimitBucket('createHousehold', 'ip', ip)).toBe(
      `createHousehold_ip_${rateLimitHmac('sprout-emulator-only-link-code-key-not-a-secret', `createHousehold|ip|${ip}`)}`,
    );
    process.env.LINK_CODE_HMAC_KEY = 'r'.repeat(44);
    expect(rateLimitBucket('createHousehold', 'ip', ip)).toBe(
      `createHousehold_ip_${rateLimitHmac('r'.repeat(44), `createHousehold|ip|${ip}`)}`,
    );
    expect(rateLimitBucket('createHousehold', 'ip', ip)).not.toContain(sha256Hex(`createHousehold|ip|${ip}`).slice(0, 32));
  });

  it('outside the emulator uses the secret, and refuses to run without one', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    delete process.env.SPROUT_EMULATOR;
    await expectCode(call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId }))), 'internal');
    process.env.LINK_CODE_HMAC_KEY = 'too-short';
    await expectCode(call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId }))), 'internal');
    const secret = 'k'.repeat(44);
    process.env.LINK_CODE_HMAC_KEY = secret;
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    expect((await db.doc(`linkCodes/${hmacId(code, secret)}`).get()).exists).toBe(true);
    const result = await call(
      fns.redeemLinkCode,
      req(await kidCaller(), { code, devicePublicKey: newDevice().publicKey }),
    ).catch((e: unknown) => e);
    // Production refuses the unattested kid key, which shows the code itself was found under the secret's id.
    expect(result).toMatchObject({ code: 'failed-precondition' });
  });
});

describe('createLinkCode device proof', () => {
  it('requires the calling device\'s signature over hid and kidId', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    await addKid(h.hid, 'kid-b', 'Otto');
    await expectCode(call(fns.createLinkCode, req(h.parent, { kidId, callerDeviceId: h.device.deviceId })), 'invalid-argument');
    await expectCode(
      call(fns.createLinkCode, req(h.parent, { kidId, ...signed(h.device, 'createLinkCode', h.hid, { kidId: 'kid-b' }) })),
      'permission-denied',
    );
    await expectCode(
      call(fns.createLinkCode, req(h.parent, { kidId, ...signed(newDevice(), 'createLinkCode', h.hid, { kidId }) })),
      'permission-denied',
    );
    // A kid device of the household is not a parent device.
    const kidDevice = newDevice();
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    await call(fns.redeemLinkCode, req(await kidCaller(), { code, devicePublicKey: kidDevice.publicKey }));
    await expectCode(
      call(fns.createLinkCode, req(h.parent, { kidId, ...signed(kidDevice, 'createLinkCode', h.hid, { kidId }) })),
      'permission-denied',
    );
    expect((await db.collection('linkCodes').get()).size).toBe(1);
  });
});

describe('redeemLinkCode: one household per uid', () => {
  it('records userHouseholds/{uid} and refuses a uid that already has a household', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const kid = await kidCaller();
    const first = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    await call(fns.redeemLinkCode, req(kid, { code: first.code, devicePublicKey: newDevice().publicKey }));
    expect((await db.doc(`userHouseholds/${kid.uid}`).get()).data()).toEqual({ hid: h.hid });

    // Even with its claims cleared (a stale token, a failed claims write), the uid cannot join again.
    await auth.setCustomUserClaims(kid.uid, null);
    const second = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    await expectCode(
      call(fns.redeemLinkCode, req(kid, { code: second.code, devicePublicKey: newDevice().publicKey })),
      'failed-precondition',
    );
    expect((await db.doc(`linkCodes/${linkCodeId(second.code)}`).get()).get('used')).toBe(false);
  });

  it('two concurrent redemptions by one uid link it once', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    await addKid(h.hid, 'kid-b', 'Otto');
    const a = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
    const b = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId: 'kid-b' })));
    const kid = await kidCaller();
    const results = await Promise.allSettled([
      call(fns.redeemLinkCode, req(kid, { code: a.code, devicePublicKey: newDevice().publicKey })),
      call(fns.redeemLinkCode, req(kid, { code: b.code, devicePublicKey: newDevice().publicKey })),
    ]);
    expect(results.filter((r) => r.status === 'fulfilled')).toHaveLength(1);
    const members = await db.collection(`households/${h.hid}/members`).where('role', '==', 'KID').get();
    expect(members.size).toBe(1);
  });
});
