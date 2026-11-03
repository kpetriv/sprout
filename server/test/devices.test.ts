import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { idTokenHash } from '../src/crypto.js';
import {
  addKid,
  approvalSignature,
  auth,
  call,
  db,
  expectCode,
  fns,
  idTokenFor,
  linkKid,
  newDevice,
  newHousehold,
  nowSeconds,
  parentKeySignature,
  post,
  req,
  resetEmulators,
  signed,
  statusSignature,
  TEST_APP_CHECK_TOKEN,
  withProof,
  type Household,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});

async function secondDevice(h: Household) {
  const device = newDevice();
  const result = await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: device.publicKey }));
  return { device, result };
}

describe('registerParentDevice', () => {
  it('registers a PENDING parent device and returns its display fingerprint', async () => {
    const h = await newHousehold();
    const { device, result } = await secondDevice(h);
    expect(result.deviceId).toBe(device.deviceId);
    expect(result.fingerprint).toMatch(/^[0-9a-f]{4} [0-9a-f]{4} [0-9a-f]{4} [0-9a-f]{4}$/);
    expect(result.fingerprint.replace(/ /g, '')).toBe(device.deviceId.slice(0, 16));
    const stored = (await db.doc(`households/${h.hid}/devices/${device.deviceId}`).get()).data();
    expect(stored).toMatchObject({ uid: h.parent.uid, role: 'PARENT', status: 'PENDING', attestation: 'NONE' });
    // Idempotent while pending.
    await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: device.publicKey }));
    // An already-active key cannot be registered again.
    await expectCode(
      call(fns.registerParentDevice, req(h.parent, { devicePublicKey: h.device.publicKey })),
      'already-exists',
    );
  });

  it('is for parents only', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await expectCode(
      call(fns.registerParentDevice, req(linked.kid, { devicePublicKey: newDevice().publicKey })),
      'permission-denied',
    );
  });
});

describe('approveDevice', () => {
  it('an ACTIVE parent device approves a PENDING one with a valid signature', async () => {
    const h = await newHousehold();
    const { device } = await secondDevice(h);
    const result = await call(
      fns.approveDevice,
      req(h.parent, {
        deviceId: device.deviceId,
        approverDeviceId: h.device.deviceId,
        signature: approvalSignature(h.device, h.hid, device.deviceId),
      }),
    );
    expect(result).toEqual({ deviceId: device.deviceId, status: 'ACTIVE' });
    const stored = (await db.doc(`households/${h.hid}/devices/${device.deviceId}`).get()).data();
    expect(stored).toMatchObject({ status: 'ACTIVE', approvedBy: h.device.deviceId });
  });

  it('rejects a bad signature, a signature for another device and a non-active approver', async () => {
    const h = await newHousehold();
    const { device } = await secondDevice(h);
    const third = await secondDevice(h);
    const base = { deviceId: device.deviceId, approverDeviceId: h.device.deviceId };
    // Signed by a key that is not the approver's.
    await expectCode(
      call(fns.approveDevice, req(h.parent, { ...base, signature: approvalSignature(newDevice(), h.hid, device.deviceId) })),
      'permission-denied',
    );
    // The approver's signature over a different device id.
    await expectCode(
      call(fns.approveDevice, req(h.parent, { ...base, signature: approvalSignature(h.device, h.hid, third.device.deviceId) })),
      'permission-denied',
    );
    // A pending device cannot approve another one, even with its own valid signature.
    await expectCode(
      call(
        fns.approveDevice,
        req(h.parent, {
          deviceId: device.deviceId,
          approverDeviceId: third.device.deviceId,
          signature: approvalSignature(third.device, h.hid, device.deviceId),
        }),
      ),
      'permission-denied',
    );
    await expectCode(call(fns.approveDevice, req(h.parent, { ...base, signature: '%%%' })), 'invalid-argument');
    expect((await db.doc(`households/${h.hid}/devices/${device.deviceId}`).get()).get('status')).toBe('PENDING');
  });

  it('refuses a target that is not pending', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await expectCode(
      call(
        fns.approveDevice,
        req(h.parent, {
          deviceId: linked.device.deviceId,
          approverDeviceId: h.device.deviceId,
          signature: approvalSignature(h.device, h.hid, linked.device.deviceId),
        }),
      ),
      'not-found',
    );
  });
});

describe('revokeDevice', () => {
  it('revokes a kid device at its latest event lamport + 1, disables the kid user and removes the membership', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const events = db.collection(`households/${h.hid}/events`);
    await events.doc('k1').set({ authorDeviceId: linked.device.deviceId, lamport: 7 });
    await events.doc('k2').set({ authorDeviceId: linked.device.deviceId, lamport: 12 });
    await events.doc('p1').set({ authorDeviceId: h.device.deviceId, lamport: 50 });

    const result = await call(
      fns.revokeDevice,
      req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })),
    );
    expect(result).toEqual({ deviceId: linked.device.deviceId, revokedAtLamport: 13 });
    const stored = (await db.doc(`households/${h.hid}/devices/${linked.device.deviceId}`).get()).data();
    expect(stored).toMatchObject({ status: 'REVOKED', revokedAtLamport: 13 });
    expect(stored?.revokedAt).toBeDefined();
    expect((await auth.getUser(linked.kid.uid)).disabled).toBe(true);
    expect((await db.doc(`households/${h.hid}/members/${linked.kid.uid}`).get()).exists).toBe(false);

    // Idempotent.
    const again = await call(
      fns.revokeDevice,
      req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })),
    );
    expect(again.revokedAtLamport).toBe(13);
  });

  it('revokes a parent device at 1 when it has no events and stamps its parent keys', async () => {
    const h = await newHousehold();
    const { device } = await secondDevice(h);
    const keys = db.collection(`households/${h.hid}/parentKeys`);
    await keys.doc('1'.repeat(64)).set({ deviceId: device.deviceId, uid: h.parent.uid, validFromLamport: 0 });
    await keys.doc('2'.repeat(64)).set({ deviceId: h.device.deviceId, uid: h.parent.uid, validFromLamport: 0 });

    const result = await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: device.deviceId })));
    expect(result.revokedAtLamport).toBe(1);
    expect((await keys.doc('1'.repeat(64)).get()).get('revokedAtLamport')).toBe(1);
    expect((await keys.doc('2'.repeat(64)).get()).get('revokedAtLamport')).toBeUndefined();
    // The parent's own account stays enabled and a member.
    expect((await auth.getUser(h.parent.uid)).disabled).toBe(false);
    expect((await db.doc(`households/${h.hid}/members/${h.parent.uid}`).get()).exists).toBe(true);
  });

  it('refuses revoking the calling device, an unknown device, or with a foreign callerDeviceId', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await expectCode(
      call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: h.device.deviceId }))),
      'failed-precondition',
    );
    await expectCode(
      call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: 'a'.repeat(64) }))),
      'not-found',
    );
    await expectCode(
      call(
        fns.revokeDevice,
        req(h.parent, { deviceId: h.device.deviceId, ...signed(linked.device, 'revokeDevice', h.hid, { deviceId: h.device.deviceId }) }),
      ),
      'permission-denied',
    );
    await expectCode(
      call(
        fns.revokeDevice,
        req(linked.kid, { deviceId: h.device.deviceId, ...signed(linked.device, 'revokeDevice', h.hid, { deviceId: h.device.deviceId }) }),
      ),
      'permission-denied',
    );
  });
});

describe('revokeDevice: lamport, device proof and parent sessions', () => {
  it('revokedAtLamport covers the device\'s documents in every collection, not only its events', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const base = `households/${h.hid}`;
    await db.doc(`${base}/events/k1`).set({ authorDeviceId: linked.device.deviceId, lamport: 12 });
    await db.doc(`${base}/choreSubmissions/s1`).set({ authorDeviceId: linked.device.deviceId, lamport: 20 });
    await db.doc(`${base}/chores/c1`).set({ authorDeviceId: h.device.deviceId, lamport: 99 });
    const result = await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })));
    expect(result.revokedAtLamport).toBe(21);

    const { device } = await secondDevice(h);
    await db.doc(`${base}/savingsGoals/g1`).set({ authorDeviceId: device.deviceId, lamport: 33 });
    await db.doc(`${base}/interestPolicy/i1`).set({ authorDeviceId: device.deviceId, lamport: 34 });
    const parentResult = await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: device.deviceId })));
    expect(parentResult.revokedAtLamport).toBe(35);
  });

  it('refuses a stored lamport that is not a safe integer with internal', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await db.doc(`households/${h.hid}/events/k1`).set({ authorDeviceId: linked.device.deviceId, lamport: 2 ** 53 });
    await expectCode(
      call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId }))),
      'internal',
    );
    expect((await db.doc(`households/${h.hid}/devices/${linked.device.deviceId}`).get()).get('status')).toBe('ACTIVE');
  });

  it('requires the calling device\'s signature over hid and the target', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const target = { deviceId: linked.device.deviceId };
    // No signature, a signature by another key, and a signature over another target.
    await expectCode(
      call(fns.revokeDevice, req(h.parent, { ...target, callerDeviceId: h.device.deviceId })),
      'invalid-argument',
    );
    await expectCode(
      call(fns.revokeDevice, req(h.parent, { ...target, ...signed(newDevice(), 'revokeDevice', h.hid, target), callerDeviceId: h.device.deviceId })),
      'permission-denied',
    );
    await expectCode(
      call(fns.revokeDevice, req(h.parent, { ...target, signature: signed(h.device, 'revokeDevice', h.hid, { deviceId: 'b'.repeat(64) }).signature, callerDeviceId: h.device.deviceId })),
      'permission-denied',
    );
    // A signature made for another callable with the same arguments.
    await expectCode(
      call(fns.revokeDevice, req(h.parent, { ...target, ...signed(h.device, 'createLinkCode', h.hid, target) })),
      'permission-denied',
    );
    expect((await db.doc(`households/${h.hid}/devices/${linked.device.deviceId}`).get()).get('status')).toBe('ACTIVE');
  });

  it('revoking a PARENT device revokes the owner\'s sessions and refuses tokens signed in before it', async () => {
    const h = await newHousehold();
    const { device } = await secondDevice(h);
    const before = Math.floor(Date.now() / 1000) - 60;
    await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: device.deviceId })));

    const member = await db.doc(`households/${h.hid}/members/${h.parent.uid}`).get();
    const validAfter = member.get('tokensValidAfter') as number;
    expect(Number.isSafeInteger(validAfter)).toBe(true);
    expect(validAfter).toBeGreaterThan(before);
    const user = await auth.getUser(h.parent.uid);
    expect(Math.floor(Date.parse(user.tokensValidAfterTime ?? '') / 1000)).toBe(validAfter);
    expect((await db.doc(`households/${h.hid}/devices/${device.deviceId}`).get()).get('sessionsRevokedAt')).toBeDefined();

    // A session signed in before the revocation is refused; a fresh sign-in works.
    await expectCode(
      call(fns.exportHousehold, req({ ...h.parent, authTime: validAfter - 1 }, withProof(h, 'exportHousehold'))),
      'unauthenticated',
    );
    await call(fns.exportHousehold, req({ ...h.parent, authTime: validAfter }, withProof(h, 'exportHousehold')));

    // Revoking a kid device leaves the parent's sessions alone.
    await db.doc(`households/${h.hid}/members/${h.parent.uid}`).update({ tokensValidAfter: 1 });
    const linked = await linkKid(h);
    await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })));
    expect((await db.doc(`households/${h.hid}/members/${h.parent.uid}`).get()).get('tokensValidAfter')).toBe(1);
  });
});

describe('device proofs are bound to the ID token of the request', () => {
  /** Another ID token of the same parent session (a different auth_time makes different token bytes). */
  const otherToken = (h: Household) => idTokenFor({ ...h.parent, authTime: nowSeconds() - 30 });

  it('a proof signed over another token is refused with permission-denied, for every signed callable', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const { device: pending } = await secondDevice(h);
    const other = idTokenHash(await otherToken(h));
    const over = (callable: Parameters<typeof signed>[1], args: Record<string, unknown> = {}) => ({
      ...args,
      ...signed(h.device, callable, h.hid, args, other),
    });
    await expectCode(call(fns.createLinkCode, req(h.parent, over('createLinkCode', { kidId }))), 'permission-denied');
    await expectCode(call(fns.exportHousehold, req(h.parent, over('exportHousehold'))), 'permission-denied');
    await expectCode(
      call(fns.revokeDevice, req(h.parent, over('revokeDevice', { deviceId: pending.deviceId }))),
      'permission-denied',
    );
    await expectCode(
      call(fns.deleteHousehold, req(h.parent, over('deleteHousehold', { confirmation: h.hid }))),
      'permission-denied',
    );
    const approval = signed(h.device, 'approveDevice', h.hid, { deviceId: pending.deviceId }, other);
    await expectCode(
      call(
        fns.approveDevice,
        req(h.parent, { deviceId: pending.deviceId, approverDeviceId: h.device.deviceId, signature: approval.signature }),
      ),
      'permission-denied',
    );
    const key = newDevice();
    const keySignature = parentKeySignature(h.device, h.hid, key.spki).resolve(other);
    await expectCode(
      call(
        fns.registerParentKey,
        req(h.parent, { publicKey: key.publicKey, deviceId: h.device.deviceId, signature: keySignature }),
      ),
      'permission-denied',
    );
    expect((await db.doc(`households/${h.hid}/devices/${pending.deviceId}`).get()).get('status')).toBe('PENDING');
    expect((await db.doc(`households/${h.hid}`).get()).exists).toBe(true);
  });

  it('a captured request works with its own token and fails when replayed with a different one', async () => {
    const h = await newHousehold();
    const kidId = await addKid(h.hid);
    const tokenA = await idTokenFor(h.parent);
    const captured = { kidId, ...signed(h.device, 'createLinkCode', h.hid, { kidId }, idTokenHash(tokenA)) };
    await call(fns.createLinkCode, req({ ...h.parent, idToken: tokenA }, captured));

    const tokenB = await otherToken(h);
    expect(tokenB).not.toBe(tokenA);
    await expectCode(call(fns.createLinkCode, req({ ...h.parent, idToken: tokenB }, captured)), 'permission-denied');
    // The same holds for the approval of a pending device.
    const { device: pending } = await secondDevice(h);
    const approval = {
      deviceId: pending.deviceId,
      ...signed(h.device, 'approveDevice', h.hid, { deviceId: pending.deviceId }, idTokenHash(tokenA)),
    };
    const approve = { deviceId: approval.deviceId, approverDeviceId: approval.callerDeviceId, signature: approval.signature };
    await expectCode(call(fns.approveDevice, req({ ...h.parent, idToken: tokenB }, approve)), 'permission-denied');
    await call(fns.approveDevice, req({ ...h.parent, idToken: tokenA }, approve));
    expect((await db.doc(`households/${h.hid}/devices/${pending.deviceId}`).get()).get('status')).toBe('ACTIVE');
  });

  it('an approval signed for another household is refused', async () => {
    const h = await newHousehold();
    const { device } = await secondDevice(h);
    await expectCode(
      call(
        fns.approveDevice,
        req(h.parent, {
          deviceId: device.deviceId,
          approverDeviceId: h.device.deviceId,
          signature: approvalSignature(h.device, 'another-household', device.deviceId),
        }),
      ),
      'permission-denied',
    );
  });
});

describe('deviceStatus (no ID token)', () => {
  const project = process.env.FIREBASE_PROJECT_ID;
  afterEach(() => {
    process.env.FIREBASE_PROJECT_ID = project;
  });

  const ask = (hid: string, deviceId: string, signature: string, ip?: string) =>
    call(fns.deviceStatus, req(ip === undefined ? null : { uid: 'unused', ip }, { hid, deviceId, signature }));

  it('answers ACTIVE and PENDING for a device signing its own request, without a signed-in user', async () => {
    const h = await newHousehold();
    expect(await ask(h.hid, h.device.deviceId, statusSignature(h.device, h.hid))).toEqual({
      status: 'ACTIVE',
      householdGone: false,
    });
    const { device } = await secondDevice(h);
    expect(await ask(h.hid, device.deviceId, statusSignature(device, h.hid))).toEqual({
      status: 'PENDING',
      householdGone: false,
    });
  });

  it('a revoked kid device, whose user is disabled, learns it is REVOKED', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await call(fns.revokeDevice, req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })));
    expect((await auth.getUser(linked.kid.uid)).disabled).toBe(true);
    expect(await ask(h.hid, linked.device.deviceId, statusSignature(linked.device, h.hid))).toEqual({
      status: 'REVOKED',
      householdGone: false,
    });
  });

  it('a device of a deleted household gets UNKNOWN with householdGone', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await call(fns.deleteHousehold, req(h.parent, withProof(h, 'deleteHousehold', { confirmation: h.hid })));
    expect(await ask(h.hid, linked.device.deviceId, statusSignature(linked.device, h.hid))).toEqual({
      status: 'UNKNOWN',
      householdGone: true,
    });
  });

  it('an unknown device or household gets UNKNOWN without householdGone', async () => {
    const h = await newHousehold();
    const stranger = newDevice();
    expect(await ask(h.hid, stranger.deviceId, statusSignature(stranger, h.hid))).toEqual({
      status: 'UNKNOWN',
      householdGone: false,
    });
    expect(await ask('no-such-household', stranger.deviceId, statusSignature(stranger, 'no-such-household'))).toEqual({
      status: 'UNKNOWN',
      householdGone: false,
    });
  });

  it('refuses a bad signature with permission-denied and malformed arguments with invalid-argument', async () => {
    const h = await newHousehold();
    const id = h.device.deviceId;
    // Another key, another household in the payload, another device id in the payload.
    await expectCode(ask(h.hid, id, statusSignature(newDevice(), h.hid, id)), 'permission-denied');
    await expectCode(ask(h.hid, id, statusSignature(h.device, 'another-household')), 'permission-denied');
    await expectCode(ask(h.hid, id, statusSignature(h.device, h.hid, 'b'.repeat(64))), 'permission-denied');
    await expectCode(ask(h.hid, id, '%%%'), 'invalid-argument');
    await expectCode(ask('bad/hid', id, statusSignature(h.device, h.hid)), 'invalid-argument');
    await expectCode(ask(h.hid, 'not-a-device', statusSignature(h.device, h.hid)), 'invalid-argument');
  });

  it('ignores any Authorization header, even an invalid one, and returns nothing but status and householdGone', async () => {
    const h = await newHousehold();
    const response = await post(
      '/deviceStatus',
      JSON.stringify({ data: { hid: h.hid, deviceId: h.device.deviceId, signature: statusSignature(h.device, h.hid) } }),
      { 'content-type': 'application/json', authorization: 'Bearer not-a-token', 'x-firebase-appcheck': TEST_APP_CHECK_TOKEN },
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ result: { status: 'ACTIVE', householdGone: false } });
  });

  it('still requires App Check outside the emulator', async () => {
    const h = await newHousehold();
    process.env.FIREBASE_PROJECT_ID = 'sprout-prod';
    const data = JSON.stringify({ data: { hid: h.hid, deviceId: h.device.deviceId, signature: statusSignature(h.device, h.hid) } });
    const response = await post('/deviceStatus', data, { 'content-type': 'application/json' });
    expect(response.status).toBe(401);
  });

  it('is limited per device id and per IP', async () => {
    const h = await newHousehold();
    const signature = statusSignature(h.device, h.hid);
    for (let i = 0; i < 30; i++) await ask(h.hid, h.device.deviceId, signature, `198.51.100.${i}`);
    await expectCode(ask(h.hid, h.device.deviceId, signature, '198.51.100.200'), 'resource-exhausted');
    // Another device from the same address still gets through, until the address's own limit.
    const other = newDevice();
    expect((await ask(h.hid, other.deviceId, statusSignature(other, h.hid), '198.51.100.1')).status).toBe('UNKNOWN');
  });

  it('counts the per-device bucket only for requests whose signature verified', async () => {
    const h = await newHousehold();
    const forged = statusSignature(newDevice(), h.hid, h.device.deviceId);
    // An unsigned flood from many addresses is refused without using up the device's own allowance.
    for (let i = 0; i < 40; i++) {
      await expectCode(ask(h.hid, h.device.deviceId, forged, `203.0.113.${i}`), 'permission-denied');
    }
    expect(await ask(h.hid, h.device.deviceId, statusSignature(h.device, h.hid), '203.0.113.250')).toEqual({
      status: 'ACTIVE',
      householdGone: false,
    });
  });

  it('still counts every request against its address first', async () => {
    const h = await newHousehold();
    const forged = statusSignature(newDevice(), h.hid, h.device.deviceId);
    for (let i = 0; i < 120; i++) await expectCode(ask(h.hid, h.device.deviceId, forged, '203.0.113.7'), 'permission-denied');
    await expectCode(ask(h.hid, h.device.deviceId, statusSignature(h.device, h.hid), '203.0.113.7'), 'resource-exhausted');
  });
});
