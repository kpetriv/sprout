import { sign } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { FieldValue } from 'firebase-admin/firestore';
import { DOCUMENT_COLLECTIONS, EXPORT_PAGE_SIZE, MAX_EXPORT_MESSAGES } from '../src/config.js';
import { encryptionKeyBindingPayload, isLowOrderX25519, registerEncryptionKeyPayload } from '../src/crypto.js';
import {
  call,
  db,
  encryptionKeyArgs,
  encryptionKeyBinding,
  encryptionKeyProof,
  expectCode,
  fns,
  linkKid,
  newDevice,
  newEncryptionKey,
  newHousehold,
  newUser,
  nowSeconds,
  parentKeySignature,
  req,
  resetEmulators,
  withProof,
  type Household,
  type TestDevice,
  type Who,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});

/** Clears the rate-limit rows: these tests make more calls per uid than the 5 per hour the callable allows. */
async function clearLimits() {
  const rows = await db.collection('rateLimits').get();
  await Promise.all(rows.docs.map((d) => d.ref.delete()));
}

async function register(who: Who, args: Record<string, unknown>) {
  await clearLimits();
  return call(fns.registerEncryptionKey, req(who, args));
}

async function deviceRow(h: Household, device: TestDevice) {
  return (await db.doc(`households/${h.hid}/devices/${device.deviceId}`).get()).data() ?? {};
}

describe('registerEncryptionKey', () => {
  it('stores a parent device key and its binding, idempotently', async () => {
    const h = await newHousehold();
    const key = newEncryptionKey();
    const args = encryptionKeyArgs(h.device, h.hid, key);

    expect(await register(h.parent, args)).toEqual({ deviceId: h.device.deviceId, created: true });
    const row = await deviceRow(h, h.device);
    expect(row.encryptionKey).toBe(key.toString('base64'));
    expect(row.encryptionKeySignature).toBe(args.bindingSignature);

    // The same key again (a retry with a fresh proof) changes nothing.
    expect(await register(h.parent, encryptionKeyArgs(h.device, h.hid, key))).toEqual({
      deviceId: h.device.deviceId,
      created: false,
    });
    expect((await deviceRow(h, h.device)).encryptionKeySignature).toBe(args.bindingSignature);
  });

  it('refuses a different key once one is registered', async () => {
    const h = await newHousehold();
    await register(h.parent, encryptionKeyArgs(h.device, h.hid, newEncryptionKey()));
    await expectCode(register(h.parent, encryptionKeyArgs(h.device, h.hid, newEncryptionKey())), 'failed-precondition');
  });

  it('a kid device registers its own key', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const key = newEncryptionKey();

    expect(await register(linked.kid, encryptionKeyArgs(linked.device, h.hid, key))).toEqual({
      deviceId: linked.device.deviceId,
      created: true,
    });
    const row = (await db.doc(`households/${h.hid}/devices/${linked.device.deviceId}`).get()).data() ?? {};
    expect(row.encryptionKey).toBe(key.toString('base64'));
  });

  it('refuses a binding that does not verify for this household, device and key', async () => {
    const h = await newHousehold();
    const key = newEncryptionKey();
    const other = newDevice();
    const base = encryptionKeyArgs(h.device, h.hid, key);
    const bad = [
      encryptionKeyBinding(h.device, 'another-household', key),
      encryptionKeyBinding(h.device, h.hid, key, other.deviceId),
      encryptionKeyBinding(h.device, h.hid, newEncryptionKey()),
      encryptionKeyBinding(other, h.hid, key, h.device.deviceId),
    ];
    for (const bindingSignature of bad) {
      await expectCode(
        register(h.parent, { ...base, bindingSignature, signature: encryptionKeyProof(h.device, h.hid, key) }),
        'permission-denied',
      );
    }
    expect((await deviceRow(h, h.device)).encryptionKey).toBeUndefined();
  });

  it('requires a proof by the device itself, bound to this request token', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const key = newEncryptionKey();
    const args = encryptionKeyArgs(h.device, h.hid, key);

    // A proof over another token's hash, or by another device, or for the kid's device from the parent's session.
    const stale = registerEncryptionKeyPayload(h.hid, Buffer.alloc(32), key);
    const staleSignature = sign('sha256', stale, { key: h.device.privateKey, dsaEncoding: 'der' }).toString('base64');
    await expectCode(register(h.parent, { ...args, signature: staleSignature }), 'permission-denied');
    await expectCode(
      register(h.parent, { ...args, signature: encryptionKeyProof(linked.device, h.hid, key) }),
      'permission-denied',
    );
    await expectCode(register(h.parent, encryptionKeyArgs(linked.device, h.hid, key)), 'permission-denied');
    await expectCode(register(linked.kid, args), 'permission-denied');
  });

  it('refuses a revoked device, a non-member and a stale session', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await db.doc(`households/${h.hid}/devices/${linked.device.deviceId}`).update({ status: 'REVOKED' });
    await expectCode(
      register(linked.kid, encryptionKeyArgs(linked.device, h.hid, newEncryptionKey())),
      'permission-denied',
    );

    const stranger = await newUser();
    await expectCode(
      register({ uid: stranger, claims: { hid: h.hid, role: 'PARENT' } }, encryptionKeyArgs(h.device, h.hid, newEncryptionKey())),
      'permission-denied',
    );

    await db.doc(`households/${h.hid}/members/${h.parent.uid}`).update({ tokensValidAfter: nowSeconds() + 60 });
    await expectCode(
      register(h.parent, encryptionKeyArgs(h.device, h.hid, newEncryptionKey())),
      'unauthenticated',
    );
  });

  it('validates its arguments', async () => {
    const h = await newHousehold();
    const key = newEncryptionKey();
    const args = encryptionKeyArgs(h.device, h.hid, key);
    await expectCode(register(h.parent, { ...args, publicKey: Buffer.alloc(31).toString('base64') }), 'invalid-argument');
    await expectCode(register(h.parent, { ...args, publicKey: Buffer.alloc(33).toString('base64') }), 'invalid-argument');
    await expectCode(register(h.parent, { ...args, bindingSignature: undefined }), 'invalid-argument');
    // Low-order points (audit L1): the all-zero key, 1, an order-8 point, p, and p - 1 with the unused top bit set.
    for (const hex of [
      '00'.repeat(32),
      '01' + '00'.repeat(31),
      '5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157',
      'ed' + 'ff'.repeat(30) + '7f',
      'ec' + 'ff'.repeat(31),
    ]) {
      const bad = Buffer.from(hex, 'hex');
      await expectCode(register(h.parent, encryptionKeyArgs(h.device, h.hid, bad)), 'invalid-argument');
    }
    await expectCode(register(h.parent, { ...args, deviceId: 'nope' }), 'invalid-argument');
    await expectCode(call(fns.registerEncryptionKey, req(null, args)), 'unauthenticated');
  });

  it('is rate limited per uid like registerParentKey', async () => {
    const h = await newHousehold();
    await clearLimits();
    const key = newEncryptionKey();
    for (let i = 0; i < 5; i++) {
      await call(fns.registerEncryptionKey, req(h.parent, encryptionKeyArgs(h.device, h.hid, key)));
    }
    await expectCode(
      call(fns.registerEncryptionKey, req(h.parent, encryptionKeyArgs(h.device, h.hid, key))),
      'resource-exhausted',
    );
  });
});

describe('registration callables with an encryption key', () => {
  it('registerParentDevice stores a key bound to the household and the new device', async () => {
    const h = await newHousehold();
    const second = newDevice();
    const key = newEncryptionKey();

    await call(
      fns.registerParentDevice,
      req(h.parent, {
        devicePublicKey: second.publicKey,
        encryptionKey: key.toString('base64'),
        encryptionKeySignature: encryptionKeyBinding(second, h.hid, key),
      }),
    );
    const row = await deviceRow(h, second);
    expect(row).toMatchObject({ status: 'PENDING', encryptionKey: key.toString('base64') });

    const third = newDevice();
    await expectCode(
      call(
        fns.registerParentDevice,
        req(h.parent, {
          devicePublicKey: third.publicKey,
          encryptionKey: key.toString('base64'),
          encryptionKeySignature: encryptionKeyBinding(second, h.hid, key),
        }),
      ),
      'permission-denied',
    );
    expect((await db.doc(`households/${h.hid}/devices/${third.deviceId}`).get()).exists).toBe(false);
    await expectCode(
      call(fns.registerParentDevice, req(h.parent, { devicePublicKey: third.publicKey, encryptionKey: key.toString('base64') })),
      'invalid-argument',
    );
  });

  it('redeemLinkCode stores a key bound to the code household; a bad binding leaves the code unused', async () => {
    const h = await newHousehold();
    await db.doc(`households/${h.hid}/kids/kid-a`).set({ docId: 'kid-a', deleted: false });
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId: 'kid-a' })));
    const uid = await newUser();
    const device = newDevice();
    const key = newEncryptionKey();

    await expectCode(
      call(
        fns.redeemLinkCode,
        req({ uid, anonymous: true }, {
          code,
          devicePublicKey: device.publicKey,
          encryptionKey: key.toString('base64'),
          encryptionKeySignature: encryptionKeyBinding(device, 'another-household', key),
        }),
      ),
      'permission-denied',
    );
    const redeemed = await call(
      fns.redeemLinkCode,
      req({ uid, anonymous: true }, {
        code,
        devicePublicKey: device.publicKey,
        encryptionKey: key.toString('base64'),
        encryptionKeySignature: encryptionKeyBinding(device, h.hid, key),
      }),
    );
    expect(redeemed.hid).toBe(h.hid);
    expect((await deviceRow(h, device)).encryptionKey).toBe(key.toString('base64'));
  });

  it('createHousehold refuses a key whose binding cannot name the new household, and creates nothing', async () => {
    const uid = await newUser();
    const device = newDevice();
    const key = newEncryptionKey();
    await expectCode(
      call(
        fns.createHousehold,
        req({ uid }, {
          fiatCurrency: 'EUR',
          devicePublicKey: device.publicKey,
          encryptionKey: key.toString('base64'),
          encryptionKeySignature: encryptionKeyBinding(device, 'guessed-household', key),
        }),
      ),
      'permission-denied',
    );
    expect((await db.doc(`userHouseholds/${uid}`).get()).exists).toBe(false);
    expect((await db.collection('households').get()).empty).toBe(true);
  });

  it('the registration callables refuse a low-order key too', async () => {
    const h = await newHousehold();
    const zero = Buffer.alloc(32);
    const device = newDevice();
    await expectCode(
      call(fns.registerParentDevice, req(h.parent, {
        devicePublicKey: device.publicKey,
        encryptionKey: zero.toString('base64'),
        encryptionKeySignature: encryptionKeyBinding(device, h.hid, zero),
      })),
      'invalid-argument',
    );
  });

  it('the low-order check ignores the unused top bit and accepts ordinary keys', () => {
    expect(isLowOrderX25519(Buffer.from('e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880', 'hex'))).toBe(true);
    expect(isLowOrderX25519(Buffer.from('ee' + 'ff'.repeat(31), 'hex'))).toBe(true);
    expect(isLowOrderX25519(newEncryptionKey())).toBe(false);
  });

  it('the binding payload matches core/domain-ledger EncryptionKeyBindingPayload', () => {
    // Golden bytes shared with the Kotlin side (domain, hid, device id, raw key, 0x00-separated).
    expect(encryptionKeyBindingPayload('home-1', 'b'.repeat(64), Buffer.alloc(32, 7)).toString('hex')).toBe(
      Buffer.from('sprout/device-encryption-key/v1').toString('hex') + '00' + Buffer.from('home-1').toString('hex') +
        '00' + Buffer.from('b'.repeat(64)).toString('hex') + '00' + '07'.repeat(32),
    );
    expect(registerEncryptionKeyPayload('home-1', Buffer.alloc(32, 0x11), Buffer.alloc(32, 7)).toString('hex')).toBe(
      '7370726f75742f72656769737465722d656e6372797074696f6e2d6b65792f763200686f6d652d3100' + '11'.repeat(32) + '00' +
        '07'.repeat(32),
    );
  });
});

describe('one encryption key per device row in a household (D-REL-32)', () => {
  it('registerEncryptionKey refuses a key another device row holds, and its own row may repeat it', async () => {
    const h = await newHousehold();
    const second = newDevice();
    const key = newEncryptionKey();
    await call(
      fns.registerParentDevice,
      req(h.parent, {
        devicePublicKey: second.publicKey,
        encryptionKey: key.toString('base64'),
        encryptionKeySignature: encryptionKeyBinding(second, h.hid, key),
      }),
    );

    await expectCode(register(h.parent, encryptionKeyArgs(h.device, h.hid, key)), 'failed-precondition');
    expect((await deviceRow(h, h.device)).encryptionKey).toBeUndefined();

    // Another key is fine, and so is repeating it on its own row.
    const own = newEncryptionKey();
    expect(await register(h.parent, encryptionKeyArgs(h.device, h.hid, own))).toMatchObject({ created: true });
    expect(await register(h.parent, encryptionKeyArgs(h.device, h.hid, own))).toMatchObject({ created: false });
  });

  it('a pending registerParentDevice retry adds a free key and refuses one another device row holds', async () => {
    const h = await newHousehold();
    const taken = newEncryptionKey();
    await register(h.parent, encryptionKeyArgs(h.device, h.hid, taken));
    const second = newDevice();
    await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: second.publicKey }));
    expect(await deviceRow(h, second)).toMatchObject({ status: 'PENDING' });
    expect((await deviceRow(h, second)).encryptionKey).toBeUndefined();

    const retry = (key: Buffer) =>
      call(
        fns.registerParentDevice,
        req(h.parent, {
          devicePublicKey: second.publicKey,
          encryptionKey: key.toString('base64'),
          encryptionKeySignature: encryptionKeyBinding(second, h.hid, key),
        }),
      );
    await expectCode(retry(taken), 'failed-precondition');
    expect((await deviceRow(h, second)).encryptionKey).toBeUndefined();

    const own = newEncryptionKey();
    await retry(own);
    expect(await deviceRow(h, second)).toMatchObject({ status: 'PENDING', encryptionKey: own.toString('base64') });
    // Once the row has a key, a retry changes nothing, whatever key it sends.
    await retry(newEncryptionKey());
    expect((await deviceRow(h, second)).encryptionKey).toBe(own.toString('base64'));
  });

  it('registerParentDevice and redeemLinkCode refuse a key a device row already holds and create nothing', async () => {
    const h = await newHousehold();
    const key = newEncryptionKey();
    await register(h.parent, encryptionKeyArgs(h.device, h.hid, key));

    const second = newDevice();
    await expectCode(
      call(
        fns.registerParentDevice,
        req(h.parent, {
          devicePublicKey: second.publicKey,
          encryptionKey: key.toString('base64'),
          encryptionKeySignature: encryptionKeyBinding(second, h.hid, key),
        }),
      ),
      'failed-precondition',
    );
    expect((await db.doc(`households/${h.hid}/devices/${second.deviceId}`).get()).exists).toBe(false);

    await db.doc(`households/${h.hid}/kids/kid-a`).set({ docId: 'kid-a', deleted: false });
    const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId: 'kid-a' })));
    const uid = await newUser();
    const kid = newDevice();
    await expectCode(
      call(
        fns.redeemLinkCode,
        req({ uid, anonymous: true }, {
          code,
          devicePublicKey: kid.publicKey,
          encryptionKey: key.toString('base64'),
          encryptionKeySignature: encryptionKeyBinding(kid, h.hid, key),
        }),
      ),
      'failed-precondition',
    );
    expect((await db.doc(`households/${h.hid}/devices/${kid.deviceId}`).get()).exists).toBe(false);
    expect((await db.doc(`userHouseholds/${uid}`).get()).exists).toBe(false);
  });
});

describe('chat collections on the server', () => {
  it('threadKeys is a document collection', () => {
    expect(DOCUMENT_COLLECTIONS.threadKeys).toBe('thread_key');
  });

  it('maxLamport scans include messages and blob chunks', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const base = `households/${h.hid}`;
    await db.doc(`${base}/events/k1`).set({ authorDeviceId: linked.device.deviceId, lamport: 3 });
    await db.doc(`${base}/messages/m1`).set({ authorDeviceId: linked.device.deviceId, lamport: 17 });
    await db.doc(`${base}/blobs/b1.0`).set({ authorDeviceId: linked.device.deviceId, lamport: 23 });
    await db.doc(`${base}/threadKeys/kid-a.0`).set({ authorDeviceId: h.device.deviceId, lamport: 41 });

    const revoked = await call(
      fns.revokeDevice,
      req(h.parent, withProof(h, 'revokeDevice', { deviceId: linked.device.deviceId })),
    );
    expect(revoked.revokedAtLamport).toBe(24);

    const parentKey = newDevice();
    await clearLimits();
    const registered = await call(
      fns.registerParentKey,
      req(h.parent, {
        publicKey: parentKey.publicKey,
        deviceId: h.device.deviceId,
        signature: parentKeySignature(h.device, h.hid, parentKey.spki),
      }),
    );
    expect(registered.validFromLamport).toBe(42);
  });

  it('exports messages with their ciphertext and blob chunks without their bytes', async () => {
    const h = await newHousehold();
    const base = `households/${h.hid}`;
    const ciphertext = Buffer.from([1, 2, 3, 250]);
    await db.doc(`${base}/messages/m2`).set({ messageId: 'm2', lamport: 9, ciphertext, serverReceivedAt: FieldValue.serverTimestamp() });
    await db.doc(`${base}/messages/m1`).set({ messageId: 'm1', lamport: 4, ciphertext, blobId: null });
    await db.doc(`${base}/blobs/b1.0`).set({
      blobId: 'b1',
      chunk: 0,
      chunks: 1,
      kidId: 'kid-a',
      sha256: 'a'.repeat(64),
      lamport: 5,
      data: Buffer.alloc(1000, 9),
    });

    const bundle = await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold')));

    expect(bundle.messages.map((m) => m.id)).toEqual(['m1', 'm2']);
    expect(bundle.messages[0]).toMatchObject({ messageId: 'm1', ciphertext: ciphertext.toString('base64'), blobId: null });
    expect(typeof bundle.messages[1].serverReceivedAt).toBe('string');
    expect(bundle.blobs).toEqual([
      { id: 'b1.0', blobId: 'b1', chunk: 0, chunks: 1, kidId: 'kid-a', sha256: 'a'.repeat(64), lamport: 5 },
    ]);
    expect(JSON.stringify(bundle)).not.toContain(Buffer.alloc(1000, 9).toString('base64'));
  });

  it('reads messages in pages and refuses a history above the message cap (audit L5)', async () => {
    const h = await newHousehold();
    const base = `households/${h.hid}`;
    async function seedMessages(from: number, to: number) {
      for (let start = from; start < to; start += 500) {
        const batch = db.batch();
        for (let i = start; i < Math.min(start + 500, to); i++) {
          const id = `m${String(i).padStart(5, '0')}`;
          batch.set(db.doc(`${base}/messages/${id}`), { messageId: id, lamport: i % 7, ciphertext: Buffer.from([i % 256]) });
        }
        await batch.commit();
      }
    }
    await seedMessages(0, EXPORT_PAGE_SIZE + 1);

    const bundle = await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold')));

    expect(bundle.messages).toHaveLength(EXPORT_PAGE_SIZE + 1);
    const keys = bundle.messages.map((m) => `${String(m.lamport)}:${String(m.id)}`);
    expect(new Set(keys).size).toBe(keys.length);
    const order = bundle.messages.map((m) => [m.lamport as number, m.id as string] as const);
    const sorted = [...order].sort((a, b) => a[0] - b[0] || (a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0));
    expect(order).toEqual(sorted);

    await seedMessages(EXPORT_PAGE_SIZE + 1, MAX_EXPORT_MESSAGES + 1);
    await clearLimits();
    await expectCode(call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold'))), 'resource-exhausted');
  }, 120_000);
});
