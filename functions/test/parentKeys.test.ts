import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SecurityLevel } from '@peculiar/asn1-android';
import { sha256Hex } from '../src/crypto.js';
import { setAttestationOptionsForTest } from '../src/registration.js';
import { fakeChain, newKeyPair, spkiOf, type FakeChainOptions } from './attestationFixtures.js';
import {
  call,
  db,
  expectCode,
  fns,
  linkKid,
  newDevice,
  newHousehold,
  parentKeySignature,
  req,
  resetEmulators,
  type Household,
  type TestDevice,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});
afterEach(() => {
  setAttestationOptionsForTest({});
  process.env.FUNCTIONS_EMULATOR = 'true';
  delete process.env.ALLOW_UNATTESTED;
});

/**
 * Calls registerParentKey after clearing the rate-limit rows: these tests make more calls per uid than the 5 per hour
 * the callable allows (the limits have their own tests).
 */
async function register(who: Parameters<typeof req>[0], args: Record<string, unknown>) {
  const rows = await db.collection('rateLimits').get();
  await Promise.all(rows.docs.map((d) => d.ref.delete()));
  return call(fns.registerParentKey, req(who, args));
}

/** registerParentKey arguments for [key], signed by [signer] (the household device by default). */
function keyArgs(h: Household, key: TestDevice, signer: TestDevice = h.device, extra: Record<string, unknown> = {}) {
  return {
    publicKey: key.publicKey,
    deviceId: signer.deviceId,
    signature: parentKeySignature(signer, h.hid, key.spki),
    ...extra,
  };
}

describe('registerParentKey', () => {
  it('registers an unattested key in the emulator, valid from the household max lamport + 1', async () => {
    const h = await newHousehold();
    await db.doc(`households/${h.hid}/events/e1`).set({ authorDeviceId: h.device.deviceId, lamport: 7 });
    await db.doc(`households/${h.hid}/chores/c1`).set({ authorDeviceId: 'f'.repeat(64), lamport: 15 });
    await db.doc(`households/${h.hid}/choreSubmissions/s1`).set({ authorDeviceId: 'e'.repeat(64), lamport: 11 });
    const key = newDevice();

    const result = await register(h.parent, keyArgs(h, key));
    expect(result).toEqual({ fingerprint: sha256Hex(key.spki), validFromLamport: 16 });
    const stored = (await db.doc(`households/${h.hid}/parentKeys/${result.fingerprint}`).get()).data();
    expect(Object.keys(stored ?? {}).sort()).toEqual(
      ['attestation', 'deviceId', 'publicKey', 'registeredAt', 'uid', 'validFromLamport'].sort(),
    );
    expect(stored).toMatchObject({
      publicKey: key.publicKey,
      uid: h.parent.uid,
      deviceId: h.device.deviceId,
      validFromLamport: 16,
      attestation: 'NONE',
    });

    // Retrying the same registration returns the stored row; nothing moves.
    await db.doc(`households/${h.hid}/events/e2`).set({ authorDeviceId: h.device.deviceId, lamport: 40 });
    expect(await register(h.parent, keyArgs(h, key))).toEqual(result);
  });

  it('is valid from 1 in an empty household', async () => {
    const h = await newHousehold();
    const result = await register(h.parent, keyArgs(h, newDevice()));
    expect(result.validFromLamport).toBe(1);
  });

  it('requires the signature of an ACTIVE parent device of the caller over hid and the key', async () => {
    const h = await newHousehold();
    const key = newDevice();
    // Signed by a key that is not the named device's.
    await expectCode(
      register(h.parent, { ...keyArgs(h, key), signature: parentKeySignature(newDevice(), h.hid, key.spki) }),
      'permission-denied',
    );
    // The device's signature over another key or for another household.
    await expectCode(
      register(h.parent, { ...keyArgs(h, key), signature: parentKeySignature(h.device, h.hid, newDevice().spki) }),
      'permission-denied',
    );
    await expectCode(
      register(h.parent, { ...keyArgs(h, key), signature: parentKeySignature(h.device, 'other', key.spki) }),
      'permission-denied',
    );
    // A PENDING device of the caller, even with its own valid signature.
    const pending = newDevice();
    await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: pending.publicKey }));
    await expectCode(register(h.parent, keyArgs(h, key, pending)), 'permission-denied');
    // A kid device, and a kid caller.
    const linked = await linkKid(h);
    await expectCode(register(h.parent, keyArgs(h, key, linked.device)), 'permission-denied');
    await expectCode(register(linked.kid, keyArgs(h, key, linked.device)), 'permission-denied');
    // Malformed input.
    await expectCode(register(h.parent, { ...keyArgs(h, key), publicKey: 'AAAA' }), 'invalid-argument');
    await expectCode(register(h.parent, { ...keyArgs(h, key), deviceId: 'x' }), 'invalid-argument');
    expect((await db.collection(`households/${h.hid}/parentKeys`).get()).size).toBe(0);
  });

  it('refuses a fingerprint already registered by another device', async () => {
    const h = await newHousehold();
    const key = newDevice();
    await register(h.parent, keyArgs(h, key));
    await db.doc(`households/${h.hid}/parentKeys/${sha256Hex(key.spki)}`).update({ deviceId: 'b'.repeat(64) });
    await expectCode(register(h.parent, keyArgs(h, key)), 'already-exists');
  });

  it('outside the emulator refuses a key without an attestation chain', async () => {
    const h = await newHousehold();
    delete process.env.FUNCTIONS_EMULATOR;
    await expectCode(register(h.parent, keyArgs(h, newDevice())), 'failed-precondition');
  });

  describe('with attestation', () => {
    async function attestedKey(h: Household, options: Omit<FakeChainOptions, 'challenge' | 'leafKeys'>) {
      const { challenge } = await call(fns.beginDeviceRegistration, req(h.parent));
      const keys = await newKeyPair();
      const fake = await fakeChain({ challenge: Buffer.from(challenge, 'base64'), leafKeys: keys, ...options });
      setAttestationOptionsForTest({ roots: [fake.root], statusList: () => Promise.resolve(null) });
      const spki = await spkiOf(keys);
      const key = { publicKey: spki.toString('base64'), spki } as TestDevice;
      return keyArgs(h, key, h.device, { attestationChain: fake.chain });
    }

    it('accepts a TEE or StrongBox key that requires a biometric for every use', async () => {
      const h = await newHousehold();
      delete process.env.FUNCTIONS_EMULATOR;
      const tee = await register(h.parent, await attestedKey(h, { userAuthType: 2 }));
      expect((await db.doc(`households/${h.hid}/parentKeys/${tee.fingerprint}`).get()).get('attestation')).toBe('TEE');
      const strong = await call(
        fns.registerParentKey,
        req(h.parent, await attestedKey(h, { userAuthType: 3, authTimeout: 0, securityLevel: SecurityLevel.strongBox })),
      );
      expect((await db.doc(`households/${h.hid}/parentKeys/${strong.fingerprint}`).get()).get('attestation')).toBe(
        'STRONGBOX',
      );
    });

    it('refuses a software key, a key without biometric per-use authentication, or a wrong challenge', async () => {
      const h = await newHousehold();
      const refused = async (options: Omit<FakeChainOptions, 'challenge' | 'leafKeys'>) =>
        expectCode(register(h.parent, await attestedKey(h, options)), 'permission-denied');
      await refused({ userAuthType: 2, securityLevel: SecurityLevel.software, propertiesInSoftwareList: true });
      await refused({});
      await refused({ userAuthType: 1 });
      await refused({ userAuthType: 2, authTimeout: 30 });
      await refused({ userAuthType: 2, noAuthRequired: true });
      const args = await attestedKey(h, { userAuthType: 2 });
      await call(fns.beginDeviceRegistration, req(h.parent)); // replaces the challenge the chain was made for
      await expectCode(register(h.parent, args), 'permission-denied');
      expect((await db.collection(`households/${h.hid}/parentKeys`).get()).size).toBe(0);
    });
  });
});
