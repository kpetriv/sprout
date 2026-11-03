import { randomBytes } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SecurityLevel } from '@peculiar/asn1-android';
import {
  AttestationError,
  GOOGLE_ATTESTATION_ROOTS_PEM,
  readAttestationChallenge,
  verifyAttestation,
  type StatusList,
} from '../src/attestation.js';
import { setAttestationOptionsForTest } from '../src/registration.js';
import { fakeChain, newKeyPair, spkiOf } from './attestationFixtures.js';
import { call, db, expectCode, fns, newUser, req, resetEmulators } from './helpers.js';
import { X509Certificate } from '@peculiar/x509';

const noRevocations = () => Promise.resolve<StatusList | null>({ entries: {} });

async function rejectsWith(promise: Promise<unknown>, reason: string) {
  await expect(promise).rejects.toBeInstanceOf(AttestationError);
  await expect(promise).rejects.toMatchObject({ reason });
}

describe('verifyAttestation (self-generated chain)', () => {
  const challenge = randomBytes(32);

  it('accepts a TEE chain and reports the key match', async () => {
    const fake = await fakeChain({ challenge });
    const result = await verifyAttestation(fake.chain, challenge, fake.leafSpki, {
      roots: [fake.root],
      statusList: noRevocations,
    });
    expect(result).toEqual({ level: 'TEE', keyMatches: true });
    expect(readAttestationChallenge(fake.chain).equals(challenge)).toBe(true);
  });

  it('maps StrongBox and software levels', async () => {
    const strong = await fakeChain({ challenge, securityLevel: SecurityLevel.strongBox });
    expect(
      (await verifyAttestation(strong.chain, challenge, strong.leafSpki, { roots: [strong.root], statusList: noRevocations }))
        .level,
    ).toBe('STRONGBOX');
    const soft = await fakeChain({ challenge, securityLevel: SecurityLevel.software, propertiesInSoftwareList: true });
    expect(
      (await verifyAttestation(soft.chain, challenge, soft.leafSpki, { roots: [soft.root], statusList: noRevocations }))
        .level,
    ).toBe('SOFTWARE');
  });

  it('reports keyMatches = false for a different submitted key', async () => {
    const fake = await fakeChain({ challenge });
    const other = await spkiOf(await newKeyPair());
    const result = await verifyAttestation(fake.chain, challenge, other, { roots: [fake.root], statusList: noRevocations });
    expect(result.keyMatches).toBe(false);
  });

  it('rejects a mismatched challenge', async () => {
    const fake = await fakeChain({ challenge });
    await rejectsWith(
      verifyAttestation(fake.chain, randomBytes(32), fake.leafSpki, { roots: [fake.root], statusList: noRevocations }),
      'CHALLENGE_MISMATCH',
    );
  });

  it('rejects keys that are not EC P-256 signing keys generated in the keystore', async () => {
    const opts = (f: Awaited<ReturnType<typeof fakeChain>>) => ({ roots: [f.root], statusList: noRevocations });
    for (const bad of [{ purposes: [0, 1] }, { algorithm: 1 }, { ecCurve: 2 }, { origin: 2 }]) {
      const fake = await fakeChain({ challenge, ...bad });
      await rejectsWith(verifyAttestation(fake.chain, challenge, fake.leafSpki, opts(fake)), 'KEY_PROPERTIES');
    }
    // A hardware level must have the properties in the hardware-enforced list.
    const misplaced = await fakeChain({ challenge, propertiesInSoftwareList: true });
    await rejectsWith(
      verifyAttestation(misplaced.chain, challenge, misplaced.leafSpki, opts(misplaced)),
      'KEY_PROPERTIES',
    );
  });

  it('rejects a broken chain, an expired leaf, a missing extension and malformed input', async () => {
    const broken = await fakeChain({ challenge, breakLeafSignature: true });
    await rejectsWith(
      verifyAttestation(broken.chain, challenge, broken.leafSpki, { roots: [broken.root], statusList: noRevocations }),
      'BAD_SIGNATURE',
    );
    const expired = await fakeChain({ challenge, leafNotAfter: new Date(Date.now() - 1000) });
    await rejectsWith(
      verifyAttestation(expired.chain, challenge, expired.leafSpki, { roots: [expired.root], statusList: noRevocations }),
      'EXPIRED',
    );
    const bare = await fakeChain({ challenge, omitExtension: true });
    await rejectsWith(
      verifyAttestation(bare.chain, challenge, bare.leafSpki, { roots: [bare.root], statusList: noRevocations }),
      'NO_ATTESTATION_EXTENSION',
    );
    await rejectsWith(verifyAttestation(['AAAA', 'AAAA'], challenge, Buffer.alloc(0)), 'MALFORMED_CHAIN');
    await rejectsWith(verifyAttestation([bare.chain[0]], challenge, Buffer.alloc(0)), 'MALFORMED_CHAIN');
  });

  it('rejects a certificate on the revocation list and allows when the list is unavailable', async () => {
    const fake = await fakeChain({ challenge });
    const serial = fake.serials[1].toLowerCase().replace(/^0+/, '');
    await rejectsWith(
      verifyAttestation(fake.chain, challenge, fake.leafSpki, {
        roots: [fake.root],
        statusList: () => Promise.resolve({ entries: { [serial]: { status: 'REVOKED', reason: 'KEY_COMPROMISE' } } }),
      }),
      'REVOKED',
    );
    const unknown = await verifyAttestation(fake.chain, challenge, fake.leafSpki, {
      roots: [fake.root],
      statusList: () => Promise.resolve(null),
    });
    expect(unknown.level).toBe('TEE');
  });

  it('the production path rejects a chain not rooted at the embedded Google roots', async () => {
    const fake = await fakeChain({ challenge });
    await rejectsWith(verifyAttestation(fake.chain, challenge, fake.leafSpki), 'UNTRUSTED_ROOT');
  });

  it('embeds parseable Google roots (RSA-4096 and ECDSA P-384)', () => {
    const roots = GOOGLE_ATTESTATION_ROOTS_PEM.map((pem) => new X509Certificate(pem));
    expect(roots.map((r) => (r.publicKey.algorithm as { name: string }).name)).toEqual(['RSASSA-PKCS1-v1_5', 'ECDSA']);
    for (const r of roots) expect(r.subject).toBe(r.issuer);
  });
});

describe('registration with attestation', () => {
  beforeEach(async () => {
    await resetEmulators();
  });
  afterEach(() => {
    setAttestationOptionsForTest({});
    process.env.FUNCTIONS_EMULATOR = 'true';
    delete process.env.ALLOW_UNATTESTED;
  });

  async function attested(uid: string, override?: { challenge?: Buffer; submitOther?: boolean }) {
    const { challenge } = await call(fns.beginDeviceRegistration, req({ uid }));
    const keys = await newKeyPair();
    const fake = await fakeChain({ challenge: override?.challenge ?? Buffer.from(challenge, 'base64'), leafKeys: keys });
    setAttestationOptionsForTest({ roots: [fake.root], statusList: () => Promise.resolve(null) });
    const submitted = override?.submitOther ? await spkiOf(await newKeyPair()) : fake.leafSpki;
    return { chain: fake.chain, publicKey: submitted.toString('base64'), challenge };
  }

  it('beginDeviceRegistration stores only the challenge hash, for 10 minutes', async () => {
    const uid = await newUser();
    const { challenge } = await call(fns.beginDeviceRegistration, req({ uid }));
    expect(Buffer.from(challenge, 'base64')).toHaveLength(32);
    const stored = await db.doc(`challenges/${uid}`).get();
    expect(Object.keys(stored.data() ?? {}).sort()).toEqual(['expiresAt', 'hash']);
    expect(JSON.stringify(stored.data())).not.toContain(challenge);
    const ttl = (stored.get('expiresAt') as { toMillis(): number }).toMillis() - Date.now();
    expect(ttl).toBeGreaterThan(9 * 60_000);
    expect(ttl).toBeLessThanOrEqual(10 * 60_000);
  });

  it('createHousehold records the attested level and consumes the challenge', async () => {
    const uid = await newUser();
    const a = await attested(uid);
    const { hid, deviceId } = await call(
      fns.createHousehold,
      req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: a.publicKey, attestationChain: a.chain }),
    );
    const device = await db.doc(`households/${hid}/devices/${deviceId}`).get();
    expect(device.get('attestation')).toBe('TEE');
    expect((await db.doc(`challenges/${uid}`).get()).exists).toBe(false);
  });

  it('rejects a chain for another challenge, a different submitted key, and no outstanding challenge', async () => {
    const uid = await newUser();
    const wrong = await attested(uid, { challenge: randomBytes(32) });
    await expectCode(
      call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: wrong.publicKey, attestationChain: wrong.chain })),
      'permission-denied',
    );
    const other = await attested(uid, { submitOther: true });
    await expectCode(
      call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: other.publicKey, attestationChain: other.chain })),
      'permission-denied',
    );
    await db.doc(`challenges/${uid}`).delete();
    await expectCode(
      call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: other.publicKey, attestationChain: other.chain })),
      'failed-precondition',
    );
  });

  it('outside the emulator an unattested key is refused unless ALLOW_UNATTESTED=true', async () => {
    const uid = await newUser();
    const { publicKey } = await attested(uid);
    delete process.env.FUNCTIONS_EMULATOR;
    await expectCode(
      call(fns.createHousehold, req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: publicKey })),
      'failed-precondition',
    );
    process.env.ALLOW_UNATTESTED = 'true';
    const { hid, deviceId } = await call(
      fns.createHousehold,
      req({ uid }, { fiatCurrency: 'EUR', devicePublicKey: publicKey }),
    );
    expect((await db.doc(`households/${hid}/devices/${deviceId}`).get()).get('attestation')).toBe('NONE');
  });

  it('outside the emulator a request without an App Check token is refused', async () => {
    const uid = await newUser();
    delete process.env.FUNCTIONS_EMULATOR;
    await expectCode(call(fns.beginDeviceRegistration, req({ uid, app: false })), 'failed-precondition');
  });
});
