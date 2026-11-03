import { beforeEach, describe, expect, it } from 'vitest';
import {
  approvalSignature,
  auth,
  call,
  db,
  expectCode,
  fns,
  linkKid,
  newDevice,
  newHousehold,
  req,
  resetEmulators,
  signed,
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
        signature: approvalSignature(h.device, device.deviceId),
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
      call(fns.approveDevice, req(h.parent, { ...base, signature: approvalSignature(newDevice(), device.deviceId) })),
      'permission-denied',
    );
    // The approver's signature over a different device id.
    await expectCode(
      call(fns.approveDevice, req(h.parent, { ...base, signature: approvalSignature(h.device, third.device.deviceId) })),
      'permission-denied',
    );
    // A pending device cannot approve another one, even with its own valid signature.
    await expectCode(
      call(
        fns.approveDevice,
        req(h.parent, {
          deviceId: device.deviceId,
          approverDeviceId: third.device.deviceId,
          signature: approvalSignature(third.device, device.deviceId),
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
          signature: approvalSignature(h.device, linked.device.deviceId),
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
