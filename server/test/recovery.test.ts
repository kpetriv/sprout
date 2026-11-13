import { createHmac, hkdfSync, pbkdf2Sync, randomBytes } from 'node:crypto';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { runOperator } from '../scripts/operator.js';
import { sha256Hex } from '../src/crypto.js';
import {
  auth,
  call,
  CallError,
  db,
  encryptionKeyArgs,
  expectCode,
  fns,
  idTokenFor,
  linkKid,
  newDevice,
  newEncryptionKey,
  newHousehold,
  nowSeconds,
  parentKeySignature,
  Proof,
  req,
  resetEmulators,
  signed,
  withProof,
  type Household,
  type TestDevice,
  type Who,
} from './helpers.js';

const TEST_KEY = process.env.RECOVERY_PIN_HMAC_KEY!;

beforeEach(async () => {
  await resetEmulators();
});
afterEach(() => {
  vi.restoreAllMocks();
  process.env.SPROUT_EMULATOR = 'true';
  process.env.RECOVERY_PIN_HMAC_KEY = TEST_KEY;
});

const DAY = 24 * 60 * 60 * 1000;
const WAIT_MS = 7 * DAY;
/** The tests' KDF: PBKDF2 with few iterations, so they run quickly (the server checks only the descriptor's shape). */
const KDF = 'pbkdf2-sha256$i=1000';

/** The client's verifier of [pin] (ADR-0013 §4): PBKDF2-HMAC-SHA256 of the domain-separated password, 32 bytes. */
function verifierOf(hid: string, pin: string, salt: Buffer, iterations = 1000): string {
  const password = Buffer.concat([
    Buffer.from('sprout/recovery-pin/v1', 'utf8'),
    Buffer.from([0]),
    Buffer.from(hid, 'utf8'),
    Buffer.from([0]),
    Buffer.from(pin, 'utf8'),
  ]);
  return pbkdf2Sync(password, salt, iterations, 32, 'sha256').toString('base64');
}

/** The MAC the server must store (ADR-0013 §4), computed here from the test environment's RECOVERY_PIN_HMAC_KEY. */
function expectedMac(hid: string, uid: string, verifier: string): string {
  const key = Buffer.from(hkdfSync('sha256', TEST_KEY, Buffer.alloc(0), 'sprout/recovery-pin/v1', 32));
  const message = Buffer.concat([
    Buffer.from(hid, 'utf8'),
    Buffer.from([0]),
    Buffer.from(uid, 'utf8'),
    Buffer.from([0]),
    Buffer.from(verifier, 'base64'),
  ]);
  return createHmac('sha256', key).update(message).digest('base64');
}

interface Pin {
  readonly pin: string;
  readonly salt: string;
  readonly verifier: string;
}

function pinFor(h: Household, pin = '482915'): Pin {
  const salt = randomBytes(16);
  return { pin, salt: salt.toString('base64'), verifier: verifierOf(h.hid, pin, salt) };
}

/** setRecoveryPin from [device] (default: the household's ACTIVE phone); [newPin] defaults to true. */
async function setPin(h: Household, pin: Pin, options: { device?: TestDevice; who?: Who; newPin?: boolean } = {}) {
  const args = { kdf: KDF, newPin: options.newPin ?? true, salt: pin.salt, verifier: pin.verifier };
  return call(fns.setRecoveryPin, req(options.who ?? h.parent, withProof(h, 'setRecoveryPin', args, options.device)));
}

/** The parent's new phone, signed in with the same account and registered PENDING. */
async function pendingPhone(h: Household): Promise<TestDevice> {
  const device = newDevice();
  await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: device.publicKey }));
  return device;
}

/** [args] plus the proof of the phone [device] itself (`deviceId`, `signature`). */
function ownProof(
  h: Household,
  callable: 'recoveryStatus' | 'recoverParentDevice',
  device: TestDevice,
  args: Record<string, unknown> = {},
  tokenHash?: Buffer,
): Record<string, unknown> {
  const { callerDeviceId, signature } = signed(device, callable, h.hid, args, tokenHash);
  return { ...args, deviceId: callerDeviceId, signature };
}

const status = (h: Household, phone: TestDevice, who: Who = h.parent) =>
  call(fns.recoveryStatus, req(who, ownProof(h, 'recoveryStatus', phone)));

const recoverWithPin = (h: Household, phone: TestDevice, verifier: string, who: Who = h.parent) =>
  call(fns.recoverParentDevice, req(who, ownProof(h, 'recoverParentDevice', phone, { method: 'PIN', verifier })));

const recoverByWaiting = (h: Household, phone: TestDevice, who: Who = h.parent) =>
  call(fns.recoverParentDevice, req(who, ownProof(h, 'recoverParentDevice', phone, { method: 'WAIT' })));

/** The error a call fails with ([CallError]), or undefined when it succeeds. */
async function errorOf(promise: Promise<unknown>): Promise<CallError | undefined> {
  return promise.then(
    () => undefined,
    (e: unknown) => e as CallError,
  );
}

/** Expects a call to fail with [code] and exactly [details]. */
async function expectRefusal(promise: Promise<unknown>, code: string, details?: unknown): Promise<void> {
  const error = await errorOf(promise);
  expect(error).toBeInstanceOf(CallError);
  expect(error!.code).toBe(code);
  if (details !== undefined) expect(error!.details).toEqual(details);
}

const deviceRow = async (hid: string, id: string) => (await db.doc(`households/${hid}/devices/${id}`).get()).data()!;
const pinRow = async (uid: string) => db.doc(`recoveryPins/${uid}`).get();
const memberRow = async (h: Household) => db.doc(`households/${h.hid}/members/${h.parent.uid}`).get();
const requestedAtOf = async (hid: string, id: string) =>
  ((await deviceRow(hid, id)).recoveryRequestedAt as Timestamp | undefined)?.toMillis();

/** Starts the 7-day wait of [phone] (its first WAIT call) and returns when it ends. */
async function startWait(h: Household, phone: TestDevice): Promise<number> {
  const error = await errorOf(recoverByWaiting(h, phone));
  expect(error?.details).toMatchObject({ reason: 'wait' });
  return (error!.details as { waitEndsAt: number }).waitEndsAt;
}

/** Moves the `recoveryRequestedAt` of device [id] to [ms] milliseconds ago (the test's clock). */
async function requestedAgo(hid: string, id: string, ms: number): Promise<void> {
  await db.doc(`households/${hid}/devices/${id}`).update({ recoveryRequestedAt: Timestamp.fromMillis(Date.now() - ms) });
}

/** Empties `rateLimits`, so a test can make more calls than one hour's allowance. */
async function clearRateLimits(): Promise<void> {
  const rows = await db.collection('rateLimits').get();
  await Promise.all(rows.docs.map((d) => d.ref.delete()));
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe('setRecoveryPin', () => {
  it('stores the MAC of the verifier under RECOVERY_PIN_HMAC_KEY, never the verifier', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await expect(setPin(h, pin)).resolves.toEqual({ set: true, attemptsLeft: 10 });

    const row = await pinRow(h.parent.uid);
    expect(row.data()).toMatchObject({
      hid: h.hid,
      salt: pin.salt,
      kdf: KDF,
      mac: expectedMac(h.hid, h.parent.uid, pin.verifier),
      failedAttempts: 0,
      setByDeviceId: h.device.deviceId,
    });
    const keyId = createHmac('sha256', Buffer.from(hkdfSync('sha256', TEST_KEY, Buffer.alloc(0), 'sprout/recovery-pin/v1', 32)))
      .update('sprout/recovery-pin/key-id')
      .digest()
      .subarray(0, 16)
      .toString('hex');
    expect(row.get('keyId')).toBe(keyId);
    expect(Object.keys(row.data()!).sort()).toEqual([
      'failedAttempts',
      'hid',
      'kdf',
      'keyId',
      'mac',
      'salt',
      'setAt',
      'setByDeviceId',
    ]);
    expect(JSON.stringify(row.data())).not.toContain(pin.verifier);
  });

  it('newPin: true starts the count at 0; newPin: false keeps the count of a row of the same household', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    // No row yet: false starts at 0 too.
    await expect(setPin(h, pin, { newPin: false })).resolves.toEqual({ set: true, attemptsLeft: 10 });
    const lastFailedAt = Timestamp.fromMillis(Date.now() - 60_000);
    await (await pinRow(h.parent.uid)).ref.update({ failedAttempts: 7, lastFailedAt });

    const again = pinFor(h); // the same PIN, a new salt (the 30-day refresh)
    await expect(setPin(h, again, { newPin: false })).resolves.toEqual({ set: true, attemptsLeft: 3 });
    const kept = await pinRow(h.parent.uid);
    expect(kept.data()).toMatchObject({ salt: again.salt, failedAttempts: 7, lastFailedAt });

    const changed = pinFor(h, '000111');
    await expect(setPin(h, changed)).resolves.toEqual({ set: true, attemptsLeft: 10 });
    const reset = await pinRow(h.parent.uid);
    expect(reset.data()).toMatchObject({ mac: expectedMac(h.hid, h.parent.uid, changed.verifier), failedAttempts: 0 });
    expect(reset.get('lastFailedAt')).toBeUndefined();

    // A row of another household (cannot happen: it is deleted with its household) is not carried over.
    await reset.ref.update({ hid: 'another-household', failedAttempts: 9, lastFailedAt });
    await expect(setPin(h, changed, { newPin: false })).resolves.toEqual({ set: true, attemptsLeft: 10 });
    expect((await pinRow(h.parent.uid)).get('lastFailedAt')).toBeUndefined();
  });

  it('accepts the Argon2id descriptor of the devices', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    const kdf = 'argon2id$v=19$m=65536,t=3,p=1';
    const args = { kdf, newPin: true, salt: pin.salt, verifier: pin.verifier };
    await call(fns.setRecoveryPin, req(h.parent, withProof(h, 'setRecoveryPin', args)));
    expect((await pinRow(h.parent.uid)).get('kdf')).toBe(kdf);
  });

  it("is refused from a PENDING or REVOKED phone, another household's phone, a kid and a removed member", async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    const phone = await pendingPhone(h);
    await expectCode(setPin(h, pin, { device: phone }), 'permission-denied');

    const revoked = await pendingPhone(h);
    await db.doc(`households/${h.hid}/devices/${revoked.deviceId}`).update({ status: 'REVOKED' });
    await expectCode(setPin(h, pin, { device: revoked }), 'permission-denied');

    const other = await newHousehold();
    await expectCode(setPin(h, pin, { device: other.device }), 'permission-denied');
    await setPin(other, pin);
    expect((await pinRow(h.parent.uid)).exists).toBe(false);
    expect((await pinRow(other.parent.uid)).get('hid')).toBe(other.hid);

    const linked = await linkKid(h);
    const args = { kdf: KDF, newPin: true, salt: pin.salt, verifier: pin.verifier };
    await expectCode(
      call(fns.setRecoveryPin, req(linked.kid, { ...args, ...signed(linked.device, 'setRecoveryPin', h.hid, args) })),
      'permission-denied',
    );
    await expectCode(call(fns.setRecoveryPin, req(null, {})), 'unauthenticated');

    // A household being deleted loses its members first: no row comes back.
    await db.doc(`households/${h.hid}/members/${h.parent.uid}`).delete();
    await expectCode(setPin(h, pin), 'permission-denied');
    expect((await pinRow(h.parent.uid)).exists).toBe(false);
  });

  it('refuses bad shapes and a signature over other arguments', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    const good = { kdf: KDF, newPin: true, salt: pin.salt, verifier: pin.verifier };
    const bad: Record<string, unknown>[] = [
      { salt: randomBytes(15).toString('base64') },
      { salt: randomBytes(17).toString('base64') },
      { salt: 'AAAAAAAAAAAAAAAAAAAAAB==' }, // 16 bytes, but not the canonical spelling
      { salt: pin.salt.replace(/=+$/, '') },
      { salt: 42 },
      { salt: undefined },
      { verifier: randomBytes(31).toString('base64') },
      { verifier: randomBytes(33).toString('base64') },
      { verifier: `${pin.verifier.slice(0, -2)}-=` },
      { verifier: `${'_'.repeat(43)}=` },
      { verifier: undefined },
      { kdf: 'argon2id$v=19$m=65536,t=3,p=10' },
      { kdf: 'argon2id$v=18$m=65536,t=3,p=1' },
      { kdf: 'argon2id$v=19$m=12345678,t=3,p=1' },
      { kdf: 'pbkdf2-sha256$i=123456789' },
      { kdf: 'pbkdf2-sha1$i=1000' },
      { kdf: 'scrypt' },
      { kdf: `${KDF}\n` },
      { kdf: undefined },
      { newPin: undefined },
      { newPin: 'true' },
      { newPin: 1 },
      { newPin: null },
    ];
    for (const change of bad) {
      const args = { ...good, ...change };
      await expectCode(call(fns.setRecoveryPin, req(h.parent, withProof(h, 'setRecoveryPin', args))), 'invalid-argument');
      await clearRateLimits();
    }
    // Signed over another verifier, or over newPin: false and sent as true.
    const other = pinFor(h, '111111');
    const proof = signed(h.device, 'setRecoveryPin', h.hid, { ...good, verifier: other.verifier });
    await expectCode(call(fns.setRecoveryPin, req(h.parent, { ...good, ...proof })), 'permission-denied');
    const keepProof = signed(h.device, 'setRecoveryPin', h.hid, { ...good, newPin: false });
    await expectCode(call(fns.setRecoveryPin, req(h.parent, { ...good, ...keepProof })), 'permission-denied');
    expect((await pinRow(h.parent.uid)).exists).toBe(false);
  });
});

describe('recoveryStatus', () => {
  it('tells the PENDING phone whether PIN recovery is available and the attempts left; no wait until it asks', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    // Registered long ago: still no wait (audit M2).
    await db.doc(`households/${h.hid}/devices/${phone.deviceId}`).update({ registeredAt: Timestamp.fromMillis(Date.now() - 30 * DAY) });

    await expect(status(h, phone)).resolves.toEqual({ pin: null, attemptsLeft: 0, waitEndsAt: null });

    const pin = pinFor(h);
    await setPin(h, pin);
    await expect(status(h, phone)).resolves.toEqual({ pin: { salt: pin.salt, kdf: KDF }, attemptsLeft: 10, waitEndsAt: null });

    await (await pinRow(h.parent.uid)).ref.update({ failedAttempts: 9 });
    await expect(status(h, phone)).resolves.toMatchObject({ pin: { salt: pin.salt, kdf: KDF }, attemptsLeft: 1 });

    await (await pinRow(h.parent.uid)).ref.update({ failedAttempts: 10 });
    await expect(status(h, phone)).resolves.toEqual({ pin: null, attemptsLeft: 0, waitEndsAt: null });

    // Reading the status never starts the wait.
    expect(await requestedAtOf(h.hid, phone.deviceId)).toBeUndefined();
    const ends = await startWait(h, phone);
    expect(ends).toBe((await requestedAtOf(h.hid, phone.deviceId))! + WAIT_MS);
    await expect(status(h, phone)).resolves.toEqual({ pin: null, attemptsLeft: 0, waitEndsAt: ends });

    // A row of another household (cannot happen: it is deleted with its household) counts as none.
    await setPin(h, pin);
    await (await pinRow(h.parent.uid)).ref.update({ hid: 'another-household' });
    await expect(status(h, phone)).resolves.toEqual({ pin: null, attemptsLeft: 0, waitEndsAt: ends });
  });

  it('answers only the PENDING phone itself, with its own key', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    await expectRefusal(status(h, h.device), 'failed-precondition', { reason: 'not-pending', status: 'ACTIVE' });
    await db.doc(`households/${h.hid}/devices/${phone.deviceId}`).update({ status: 'REVOKED' });
    await expectRefusal(status(h, phone), 'failed-precondition', { reason: 'not-pending', status: 'REVOKED' });

    const second = await pendingPhone(h);
    const forged = { deviceId: second.deviceId, signature: signed(phone, 'recoveryStatus', h.hid).signature };
    await expectCode(call(fns.recoveryStatus, req(h.parent, forged)), 'permission-denied');
    const wrongDomain = { deviceId: second.deviceId, signature: signed(second, 'recoverParentDevice', h.hid).signature };
    await expectCode(call(fns.recoveryStatus, req(h.parent, wrongDomain)), 'permission-denied');
    await expectCode(status(h, newDevice()), 'permission-denied');
    const other = await newHousehold();
    const otherPhone = await pendingPhone(other);
    await expectCode(status(h, otherPhone), 'permission-denied');
    await expectCode(call(fns.recoveryStatus, req(h.parent, { deviceId: 'x', signature: 'AAAA' })), 'invalid-argument');
  });
});

describe('RECOVERY_PIN_HMAC_KEY', () => {
  it('after a rotation a stored value counts as absent and uses up no attempt', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    process.env.RECOVERY_PIN_HMAC_KEY = 'rotated-recovery-pin-hmac-key-not-a-secret-44';
    await expect(status(h, phone)).resolves.toMatchObject({ pin: null, attemptsLeft: 0 });
    await expectRefusal(recoverWithPin(h, phone, pin.verifier), 'failed-precondition', { reason: 'pin-unavailable' });
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(0);
    // Registered again under the new key, it works.
    await setPin(h, pin, { newPin: false });
    await expect(status(h, phone)).resolves.toMatchObject({ attemptsLeft: 10 });
    await expect(recoverWithPin(h, phone, pin.verifier)).resolves.toMatchObject({ status: 'ACTIVE' });
  });

  it('outside the emulator: without the key the PIN paths fail closed and the wait still works', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    delete process.env.SPROUT_EMULATOR;
    delete process.env.RECOVERY_PIN_HMAC_KEY;

    await expectCode(setPin(h, pin), 'internal');
    // The waiting screen can still offer the wait: no PIN way rather than an error.
    await expect(status(h, phone)).resolves.toEqual({ pin: null, attemptsLeft: 0, waitEndsAt: null });
    await expectCode(recoverWithPin(h, phone, pin.verifier), 'internal');
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(0);
    await expectRefusal(recoverByWaiting(h, phone), 'failed-precondition');
    expect(await requestedAtOf(h.hid, phone.deviceId)).toBeTypeOf('number');
    // The rest of the server is unaffected.
    await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold')));
    // Without a stored value the status needs no key.
    await (await pinRow(h.parent.uid)).ref.delete();
    await expect(status(h, phone)).resolves.toMatchObject({ pin: null, attemptsLeft: 0 });
    // A key shorter than 32 characters, or the link-code key's value, is refused like a missing one.
    process.env.RECOVERY_PIN_HMAC_KEY = 'short';
    await expectCode(setPin(h, pin), 'internal');
    process.env.RECOVERY_PIN_HMAC_KEY = process.env.LINK_CODE_HMAC_KEY;
    await expectCode(setPin(h, pin), 'internal');
    // The emulator's dev key is never used in production.
    process.env.RECOVERY_PIN_HMAC_KEY = 'production-recovery-pin-hmac-key-not-real-44';
    await expect(setPin(h, pin)).resolves.toEqual({ set: true, attemptsLeft: 10 });
  });
});

describe('recoverParentDevice with the PIN', () => {
  it('admits the phone, revokes every other parent phone and every session of the account, and records it', async () => {
    const h = await newHousehold();
    const lost = h.device.deviceId;
    const kidLinked = await linkKid(h);
    await db.doc(`households/${h.hid}/events/e1`).set({ authorDeviceId: lost, lamport: 5 });
    const keyId = '7'.repeat(64);
    await db.doc(`households/${h.hid}/parentKeys/${keyId}`).set({ deviceId: lost, validFromLamport: 1 });
    const pin = pinFor(h);
    await setPin(h, pin);
    const stale = await pendingPhone(h); // an earlier attempt that was never approved
    const phone = await pendingPhone(h);
    const authBefore = (await auth.getUser(h.parent.uid)).tokensValidAfterTime;

    await expectCode(recoverWithPin(h, phone, pinFor(h, '000000').verifier), 'permission-denied');
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(1);

    await wait(1100); // Auth's cut-off has second resolution
    const authTime = nowSeconds() - 30;
    const recovering: Who = { ...h.parent, authTime };
    const lostSession: Who = { ...h.parent, authTime: authTime - 60 };
    const result = await recoverWithPin(h, phone, pin.verifier, recovering);
    expect(result).toEqual({
      deviceId: phone.deviceId,
      status: 'ACTIVE',
      revokedDevices: 2,
      method: 'PIN',
      attemptsLeft: 9,
      signInAgain: true,
    });

    const admitted = await deviceRow(h.hid, phone.deviceId);
    expect(admitted).toMatchObject({ status: 'ACTIVE', approvedBy: 'recovery', recoveryMethod: 'PIN' });
    expect(admitted.recoveredAt).toBeInstanceOf(Timestamp);
    expect(admitted.sessionsRevocationPending).toBeUndefined();
    const lostRow = await deviceRow(h.hid, lost);
    expect(lostRow).toMatchObject({ status: 'REVOKED', revokedAtLamport: 6 });
    expect(lostRow.sessionsRevokedAt).toBeInstanceOf(Timestamp);
    expect(await deviceRow(h.hid, stale.deviceId)).toMatchObject({ status: 'REVOKED', revokedAtLamport: 1 });
    expect((await deviceRow(h.hid, stale.deviceId)).sessionsRevokedAt).toBeInstanceOf(Timestamp);
    expect((await db.doc(`households/${h.hid}/parentKeys/${keyId}`).get()).get('revokedAtLamport')).toBe(6);
    expect((await deviceRow(h.hid, kidLinked.device.deviceId)).status).toBe('ACTIVE');

    const records = await db.collection('recoveries').get();
    expect(records.size).toBe(1);
    const record = records.docs[0];
    expect(record.id).toBe(admitted.recoveryId);
    expect(Object.keys(record.data()).sort()).toEqual(['at', 'deviceId', 'hidHash', 'method', 'revokedDevices', 'ticket']);
    expect(record.data()).toMatchObject({ hidHash: sha256Hex(h.hid), deviceId: phone.deviceId, method: 'PIN', ticket: null });
    expect(JSON.stringify(record.data())).not.toContain(h.parent.uid);

    // The check value and its count stay (audit M1).
    const row = await pinRow(h.parent.uid);
    expect(row.get('failedAttempts')).toBe(1);
    expect(row.get('mac')).toBe(expectedMac(h.hid, h.parent.uid, pin.verifier));

    // Every session is revoked as revokeDevice does (audit H1): refresh tokens and tokensValidAfter.
    const after = (await auth.getUser(h.parent.uid)).tokensValidAfterTime;
    expect(after).not.toBe(authBefore);
    const validAfter = (await memberRow(h)).get('tokensValidAfter') as number;
    expect(validAfter).toBe(Math.floor(Date.parse(after!) / 1000));
    expect(validAfter).toBeGreaterThan(authTime);
    for (const who of [recovering, lostSession]) {
      // Refused by Auth's revocation check before any handler: signedIn-only callables too.
      await expectCode(call(fns.beginDeviceRegistration, req(who)), 'unauthenticated');
      await expectCode(call(fns.exportHousehold, req(who, withProof(h, 'exportHousehold', {}, phone))), 'unauthenticated');
    }
    // The restored phone signs in again and works; the lost one cannot act whatever its session.
    await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold', {}, phone)));
    await expectCode(call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold'))), 'permission-denied');
    // A second recovery from the restored phone has nothing to do.
    await expectRefusal(recoverWithPin(h, phone, pin.verifier), 'failed-precondition', { reason: 'not-pending', status: 'ACTIVE' });
  });

  it('refuses a session older than a stored tokensValidAfter', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const at = nowSeconds() - 60;
    await db.doc(`households/${h.hid}/members/${h.parent.uid}`).update({ tokensValidAfter: at });
    await expectCode(recoverWithPin(h, phone, pin.verifier, { ...h.parent, authTime: at - 1 }), 'unauthenticated');
    expect((await memberRow(h)).get('tokensValidAfter')).toBe(at);
    expect((await deviceRow(h.hid, phone.deviceId)).status).toBe('PENDING');
  });

  it('answers internal and keeps the mark when Auth fails; the same call again finishes and answers as the first would', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const authBefore = (await auth.getUser(h.parent.uid)).tokensValidAfterTime;
    const authTime = nowSeconds() - 10;
    const who: Who = { ...h.parent, authTime };
    const spy = vi
      .spyOn(auth, 'revokeRefreshTokens')
      .mockRejectedValue(Object.assign(new Error('down'), { code: 'auth/internal-error' }));

    await expectCode(recoverWithPin(h, phone, pin.verifier, who), 'internal');
    expect(spy).toHaveBeenCalledTimes(3); // tried three times within the request
    spy.mockRestore();
    const admitted = await deviceRow(h.hid, phone.deviceId);
    expect(admitted).toMatchObject({ status: 'ACTIVE', approvedBy: 'recovery', sessionsRevocationPending: true });
    const lost = await deviceRow(h.hid, h.device.deviceId);
    expect(lost.status).toBe('REVOKED');
    expect(lost.sessionsRevokedAt).toBeUndefined(); // nothing stamped before Auth answered
    expect((await auth.getUser(h.parent.uid)).tokensValidAfterTime).toBe(authBefore);
    expect((await memberRow(h)).get('tokensValidAfter')).toBe(authTime);

    // Another ACTIVE phone without the mark is still not-pending.
    const linked = await pendingPhone(h);
    await db.doc(`households/${h.hid}/devices/${linked.deviceId}`).update({ status: 'ACTIVE' });
    await expectRefusal(recoverByWaiting(h, linked, who), 'failed-precondition', { reason: 'not-pending', status: 'ACTIVE' });

    await expect(recoverWithPin(h, phone, pin.verifier, who)).resolves.toEqual({
      deviceId: phone.deviceId,
      status: 'ACTIVE',
      revokedDevices: 1,
      method: 'PIN',
      attemptsLeft: 10,
      signInAgain: true,
    });
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBeUndefined();
    expect((await deviceRow(h.hid, h.device.deviceId)).sessionsRevokedAt).toBeInstanceOf(Timestamp);
    expect((await auth.getUser(h.parent.uid)).tokensValidAfterTime).not.toBe(authBefore);
    expect((await db.collection('recoveries').get()).size).toBe(1);
  });

  it('rides out a passing Auth failure within the request', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const spy = vi
      .spyOn(auth, 'revokeRefreshTokens')
      .mockRejectedValueOnce(new Error('down'))
      .mockRejectedValueOnce(new Error('down'));
    await expect(recoverWithPin(h, phone, pin.verifier)).resolves.toMatchObject({ status: 'ACTIVE', signInAgain: true });
    expect(spy).toHaveBeenCalledTimes(3);
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBeUndefined();
    expect((await deviceRow(h.hid, h.device.deviceId)).sessionsRevokedAt).toBeInstanceOf(Timestamp);
  });

  it("finishes an unfinished revocation at the restored phone's next device-signed call, which answers unauthenticated", async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const authBefore = (await auth.getUser(h.parent.uid)).tokensValidAfterTime;
    const spy = vi.spyOn(auth, 'revokeRefreshTokens').mockRejectedValue(new Error('down'));
    await expectCode(recoverWithPin(h, phone, pin.verifier), 'internal');
    spy.mockRestore();
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBe(true);

    // The app restarted and never repeats the restore: its next signed call (here an export) finishes it.
    await wait(1100);
    await expectCode(call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold', {}, phone))), 'unauthenticated');
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBeUndefined();
    expect((await deviceRow(h.hid, h.device.deviceId)).sessionsRevokedAt).toBeInstanceOf(Timestamp);
    expect((await auth.getUser(h.parent.uid)).tokensValidAfterTime).not.toBe(authBefore);
    // Signed in again, the same call works.
    await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold', {}, phone)));

    // The two calls a freshly restored phone always makes next do the same (the mark set again by hand).
    const mark = () => db.doc(`households/${h.hid}/devices/${phone.deviceId}`).update({ sessionsRevocationPending: true });
    await mark();
    const key = newEncryptionKey();
    await expectCode(call(fns.registerEncryptionKey, req(h.parent, encryptionKeyArgs(phone, h.hid, key))), 'unauthenticated');
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBeUndefined();
    expect((await deviceRow(h.hid, phone.deviceId)).encryptionKey).toBeUndefined();
    await mark();
    const parentKey = newDevice();
    await expectCode(
      call(fns.registerParentKey, req(h.parent, {
        publicKey: parentKey.publicKey,
        deviceId: phone.deviceId,
        signature: parentKeySignature(phone, h.hid, parentKey.spki),
      })),
      'unauthenticated',
    );
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBeUndefined();
    expect((await db.collection(`households/${h.hid}/parentKeys`).get()).size).toBe(0);
    // A mark on a phone the app's recovery did not admit is ignored.
    await db.doc(`households/${h.hid}/devices/${phone.deviceId}`).update({ sessionsRevocationPending: true, approvedBy: 'x' });
    await call(fns.registerEncryptionKey, req(h.parent, encryptionKeyArgs(phone, h.hid, key)));
  });

  it('lets the operator finish a revocation the app left unfinished', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const spy = vi.spyOn(auth, 'revokeRefreshTokens').mockRejectedValue(new Error('down'));
    await expectCode(recoverWithPin(h, phone, pin.verifier), 'internal');
    spy.mockRestore();

    const lines: string[] = [];
    await runOperator(['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid], { out: (l) => lines.push(l) });
    expect(lines.some((l) => l.startsWith(`    ${phone.deviceId} user ${h.parent.uid} ACTIVE`) && l.endsWith('(restored in the app)'))).toBe(true);
    expect(lines).toContain(
      `  complete: revoke the sessions of user ${h.parent.uid} after the in-app restore of ${phone.deviceId} ` +
        '(its session revocation did not finish)',
    );
    await runOperator(['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--apply', '--confirm', h.hid], {
      out: () => undefined,
    });
    expect((await deviceRow(h.hid, phone.deviceId)).sessionsRevocationPending).toBeUndefined();
    expect((await deviceRow(h.hid, h.device.deviceId)).sessionsRevokedAt).toBeInstanceOf(Timestamp);
  });

  it('counts wrong PINs down and refuses the eleventh attempt even with the right PIN', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    for (let left = 9; left >= 0; left--) {
      await expectRefusal(recoverWithPin(h, phone, pinFor(h, String(100000 + left)).verifier), 'permission-denied', {
        reason: 'wrong-pin',
        attemptsLeft: left,
      });
      expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(10 - left);
    }
    await clearRateLimits();
    await expectRefusal(recoverWithPin(h, phone, pin.verifier), 'failed-precondition', { reason: 'pin-unavailable' });
    expect((await deviceRow(h.hid, phone.deviceId)).status).toBe('PENDING');
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(10);
    await expect(status(h, phone)).resolves.toMatchObject({ pin: null, attemptsLeft: 0 });

    // Registering the same PIN again keeps it closed; only a new PIN reopens it.
    await expect(setPin(h, pin, { newPin: false })).resolves.toEqual({ set: true, attemptsLeft: 0 });
    await expect(status(h, phone)).resolves.toMatchObject({ pin: null, attemptsLeft: 0 });
    await setPin(h, pin);
    await expect(status(h, phone)).resolves.toMatchObject({ attemptsLeft: 10 });
    await expect(recoverWithPin(h, phone, pin.verifier)).resolves.toMatchObject({ status: 'ACTIVE', attemptsLeft: 10 });
  });

  it('allows ten wrong PINs in total across a registration of the same PIN (newPin: false)', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    for (let i = 0; i < 6; i++) await expectCode(recoverWithPin(h, phone, pinFor(h, String(400000 + i)).verifier), 'permission-denied');
    await clearRateLimits();
    await expect(setPin(h, pin, { newPin: false })).resolves.toEqual({ set: true, attemptsLeft: 4 });
    for (let left = 3; left >= 0; left--) {
      await expectRefusal(recoverWithPin(h, phone, pinFor(h, String(500000 + left)).verifier), 'permission-denied', {
        reason: 'wrong-pin',
        attemptsLeft: left,
      });
    }
    await expectRefusal(recoverWithPin(h, phone, pin.verifier), 'failed-precondition', { reason: 'pin-unavailable' });
  });

  it('keeps the count across a successful recovery (audit M1)', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const kidPhone = await pendingPhone(h);
    for (let i = 0; i < 9; i++) await expectCode(recoverWithPin(h, kidPhone, pinFor(h, String(300000 + i)).verifier), 'permission-denied');
    await clearRateLimits();
    const parentPhone = await pendingPhone(h);
    await expect(recoverWithPin(h, parentPhone, pin.verifier)).resolves.toMatchObject({ attemptsLeft: 1, revokedDevices: 2 });
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(9);
    const nextPhone = await pendingPhone(h);
    await expect(status(h, nextPhone)).resolves.toMatchObject({ attemptsLeft: 1 });
    await expectRefusal(recoverWithPin(h, nextPhone, pinFor(h, '999999').verifier), 'permission-denied', {
      reason: 'wrong-pin',
      attemptsLeft: 0,
    });
    await expectRefusal(recoverWithPin(h, nextPhone, pin.verifier), 'failed-precondition', { reason: 'pin-unavailable' });
  });

  it('refuses when no check value is stored', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    await expectRefusal(recoverWithPin(h, phone, pinFor(h).verifier), 'failed-precondition', { reason: 'pin-unavailable' });
  });

  it('allows no more than ten wrong comparisons in total under concurrent calls', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    await (await pinRow(h.parent.uid)).ref.update({ failedAttempts: 6 });
    const wrong = Array.from({ length: 8 }, (_, i) => pinFor(h, String(200000 + i)).verifier);
    const results = await Promise.allSettled(wrong.map((v) => recoverWithPin(h, phone, v)));
    const errors = results.map((r) => (r.status === 'rejected' ? (r.reason as CallError) : undefined));
    expect(errors.every((e) => e instanceof CallError)).toBe(true);
    const reasonOf = (e: CallError | undefined) => (e!.details as { reason?: string } | undefined)?.reason;
    const wrongPin = errors.filter((e) => reasonOf(e) === 'wrong-pin');
    const closed = errors.filter((e) => reasonOf(e) === 'pin-unavailable');
    expect(wrongPin.length).toBe(4);
    expect(closed.length).toBe(4);
    expect(wrongPin.map((e) => (e!.details as { attemptsLeft: number }).attemptsLeft).sort()).toEqual([0, 1, 2, 3]);
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(10);
  });

  it('round-trips the production PBKDF2 parameters', async () => {
    const h = await newHousehold();
    const salt = randomBytes(16);
    const verifier = verifierOf(h.hid, '271828', salt, 600_000);
    const kdf = 'pbkdf2-sha256$i=600000';
    const args = { kdf, newPin: true, salt: salt.toString('base64'), verifier };
    await call(fns.setRecoveryPin, req(h.parent, withProof(h, 'setRecoveryPin', args)));
    const phone = await pendingPhone(h);
    const pinInfo = (await status(h, phone)).pin!;
    expect(pinInfo.kdf).toBe(kdf);
    const derived = verifierOf(h.hid, '271828', Buffer.from(pinInfo.salt, 'base64'), 600_000);
    await expect(recoverWithPin(h, phone, derived)).resolves.toMatchObject({ status: 'ACTIVE', method: 'PIN' });
  });
});

describe('recoverParentDevice by waiting', () => {
  it('starts the wait on the first request, never moves it, then admits the phone and forgets the PIN', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    // A phone registered 30 days ago still waits 7 days from its request (audit M2).
    await db.doc(`households/${h.hid}/devices/${phone.deviceId}`).update({ registeredAt: Timestamp.fromMillis(Date.now() - 30 * DAY) });

    const ends = await startWait(h, phone);
    const requested = (await requestedAtOf(h.hid, phone.deviceId))!;
    expect(ends).toBe(requested + WAIT_MS);
    expect(Math.abs(requested - Date.now())).toBeLessThan(60_000);
    // Asking again, reading the status or registering the same key again moves nothing.
    await expectRefusal(recoverByWaiting(h, phone), 'failed-precondition', { reason: 'wait', waitEndsAt: ends });
    await status(h, phone);
    await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: phone.publicKey }));
    expect(await requestedAtOf(h.hid, phone.deviceId)).toBe(requested);

    await requestedAgo(h.hid, phone.deviceId, 6 * DAY);
    await expectRefusal(recoverByWaiting(h, phone), 'failed-precondition');
    expect((await deviceRow(h.hid, phone.deviceId)).status).toBe('PENDING');
    expect((await deviceRow(h.hid, h.device.deviceId)).status).toBe('ACTIVE');

    await requestedAgo(h.hid, phone.deviceId, WAIT_MS + 60_000);
    const result = await recoverByWaiting(h, phone);
    expect(result).toEqual({
      deviceId: phone.deviceId,
      status: 'ACTIVE',
      revokedDevices: 1,
      method: 'WAIT',
      attemptsLeft: 0,
      signInAgain: true,
    });
    expect(await deviceRow(h.hid, phone.deviceId)).toMatchObject({ status: 'ACTIVE', approvedBy: 'recovery', recoveryMethod: 'WAIT' });
    expect((await deviceRow(h.hid, h.device.deviceId)).status).toBe('REVOKED');
    expect((await pinRow(h.parent.uid)).exists).toBe(false);
    expect((await db.collection('recoveries').get()).docs[0].data()).toMatchObject({ method: 'WAIT', ticket: null });
  });

  it('works without a check value and with every other phone already revoked', async () => {
    const h = await newHousehold();
    await db.doc(`households/${h.hid}/devices/${h.device.deviceId}`).update({ status: 'REVOKED', revokedAtLamport: 1 });
    const phone = await pendingPhone(h);
    await startWait(h, phone);
    await requestedAgo(h.hid, phone.deviceId, WAIT_MS);
    await expect(recoverByWaiting(h, phone)).resolves.toMatchObject({ revokedDevices: 0, method: 'WAIT', signInAgain: true });
    expect((await db.collection('recoveries').get()).docs[0].data().revokedDevices).toEqual([]);
  });
});

describe('recoverParentDevice refusals', () => {
  it('needs a recent sign-in', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const old: Who = { ...h.parent, authTime: nowSeconds() - 6 * 60 };
    await expectRefusal(recoverWithPin(h, phone, pin.verifier, old), 'failed-precondition', { reason: 'recent-sign-in' });
    await expectRefusal(recoverByWaiting(h, phone, old), 'failed-precondition', { reason: 'recent-sign-in' });
    expect((await deviceRow(h.hid, phone.deviceId)).status).toBe('PENDING');
    expect(await requestedAtOf(h.hid, phone.deviceId)).toBeUndefined();
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(0);
  });

  it('refuses a signature by another key, a replay under another ID token and mismatched arguments', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const other = await pendingPhone(h);
    const pinArgs = { method: 'PIN', verifier: pin.verifier };

    const forged = { ...pinArgs, deviceId: phone.deviceId, signature: signed(other, 'recoverParentDevice', h.hid, pinArgs).signature };
    await expectCode(call(fns.recoverParentDevice, req(h.parent, forged)), 'permission-denied');

    // A request captured under one ID token, replayed under another.
    const captured = ownProof(h, 'recoverParentDevice', phone, { method: 'WAIT' });
    await expectCode(call(fns.recoverParentDevice, req(h.parent, captured)), 'failed-precondition');
    const sent = (captured.signature as Proof).sent[0];
    const otherToken = await idTokenFor({ ...h.parent, authTime: nowSeconds() - 1 });
    await expectCode(
      call(fns.recoverParentDevice, req({ ...h.parent, idToken: otherToken }, { method: 'WAIT', deviceId: phone.deviceId, signature: sent })),
      'permission-denied',
    );
    await expectCode(
      call(fns.recoverParentDevice, req(h.parent, ownProof(h, 'recoverParentDevice', phone, pinArgs, randomBytes(32)))),
      'permission-denied',
    );

    const waitProof = signed(phone, 'recoverParentDevice', h.hid, { method: 'WAIT' });
    await expectCode(call(fns.recoverParentDevice, req(h.parent, { ...pinArgs, deviceId: phone.deviceId, signature: waitProof.signature })), 'permission-denied');
    const pinProof = signed(phone, 'recoverParentDevice', h.hid, { method: 'PIN', verifier: pinFor(h, '999999').verifier });
    await expectCode(call(fns.recoverParentDevice, req(h.parent, { ...pinArgs, deviceId: phone.deviceId, signature: pinProof.signature })), 'permission-denied');
    const statusProof = signed(phone, 'recoveryStatus', h.hid, pinArgs);
    await expectCode(call(fns.recoverParentDevice, req(h.parent, { ...pinArgs, deviceId: phone.deviceId, signature: statusProof.signature })), 'permission-denied');
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(0);
    expect((await deviceRow(h.hid, phone.deviceId)).status).toBe('PENDING');
  });

  it('refuses a wrong method and verifier combination', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    const phone = await pendingPhone(h);
    const bad: Record<string, unknown>[] = [
      { method: 'PIN' },
      { method: 'PIN', verifier: randomBytes(31).toString('base64') },
      { method: 'PIN', verifier: 'not base64!' },
      { method: 'WAIT', verifier: pin.verifier },
      { method: 'WAIT', verifier: null },
      { method: 'EMAIL' },
      { method: 'pin', verifier: pin.verifier },
      {},
    ];
    for (const args of bad) {
      await expectCode(call(fns.recoverParentDevice, req(h.parent, ownProof(h, 'recoverParentDevice', phone, args))), 'invalid-argument');
      await clearRateLimits();
    }
    expect(await requestedAtOf(h.hid, phone.deviceId)).toBeUndefined();
  });

  it('refuses a phone that is not PENDING, an unknown phone and a phone of another account', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    await expectRefusal(recoverWithPin(h, h.device, pin.verifier), 'failed-precondition', { reason: 'not-pending', status: 'ACTIVE' });
    const revoked = await pendingPhone(h);
    await db.doc(`households/${h.hid}/devices/${revoked.deviceId}`).update({ status: 'REVOKED' });
    await expectRefusal(recoverWithPin(h, revoked, pin.verifier), 'failed-precondition', { reason: 'not-pending', status: 'REVOKED' });
    await expectCode(recoverWithPin(h, newDevice(), pin.verifier), 'permission-denied');
    const other = await newHousehold();
    const otherPhone = await pendingPhone(other);
    await expectCode(recoverWithPin(h, otherPhone, pin.verifier), 'permission-denied');
    const linked = await linkKid(h);
    await expectCode(
      call(fns.recoverParentDevice, req(linked.kid, ownProof(h, 'recoverParentDevice', linked.device, { method: 'WAIT' }))),
      'permission-denied',
    );
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(0);
  });

  it('refuses both methods while another parent of the household has an ACTIVE phone', async () => {
    const h = await newHousehold();
    const pin = pinFor(h);
    await setPin(h, pin);
    const phone = await pendingPhone(h);
    const second = newDevice();
    await db.doc(`households/${h.hid}/members/second-parent`).set({ role: 'PARENT', addedAt: FieldValue.serverTimestamp() });
    await db.doc(`households/${h.hid}/devices/${second.deviceId}`).set({ uid: 'second-parent', role: 'PARENT', status: 'ACTIVE' });
    await expectRefusal(recoverWithPin(h, phone, pin.verifier), 'failed-precondition', { reason: 'other-parent' });
    await expectRefusal(recoverWithPin(h, phone, pinFor(h, '000000').verifier), 'failed-precondition', { reason: 'other-parent' });
    await expectRefusal(recoverByWaiting(h, phone), 'failed-precondition', { reason: 'other-parent' });
    expect(await requestedAtOf(h.hid, phone.deviceId)).toBeUndefined();
    // Even after a wait that was somehow started.
    await requestedAgo(h.hid, phone.deviceId, WAIT_MS + 1);
    await expectRefusal(recoverByWaiting(h, phone), 'failed-precondition', { reason: 'other-parent' });
    expect((await pinRow(h.parent.uid)).get('failedAttempts')).toBe(0);
    expect((await deviceRow(h.hid, phone.deviceId)).status).toBe('PENDING');
  });
});

describe('rate limits', () => {
  it('counts per device only once the proof verified, and has no per-account bucket', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    const forger = newDevice();
    // Calls with a bad proof count against the IP only.
    for (let i = 0; i < 9; i++) {
      const forged = { method: 'WAIT', deviceId: phone.deviceId, signature: signed(forger, 'recoverParentDevice', h.hid, { method: 'WAIT' }).signature };
      await expectCode(call(fns.recoverParentDevice, req(h.parent, forged)), 'permission-denied');
    }
    for (let i = 0; i < 10; i++) await expectRefusal(recoverByWaiting(h, phone), 'failed-precondition');
    await expectCode(recoverByWaiting(h, phone), 'resource-exhausted');
    // Another phone of the same account, from another address, is not locked out.
    const other = await pendingPhone(h);
    const otherError = await errorOf(recoverByWaiting(h, other, { ...h.parent, ip: '198.51.100.77' }));
    expect(otherError?.details).toMatchObject({ reason: 'wait' });

    for (let i = 0; i < 30; i++) await status(h, other, { ...h.parent, ip: '198.51.100.78' });
    await expectCode(status(h, other, { ...h.parent, ip: '198.51.100.78' }), 'resource-exhausted');

    const pin = pinFor(h);
    for (let i = 0; i < 10; i++) await setPin(h, pin);
    await expectCode(setPin(h, pin), 'resource-exhausted');
  });

  it('limits per IP before anything is read, across accounts', async () => {
    const ip = '198.51.100.23';
    const households = [await newHousehold(ip), await newHousehold(ip), await newHousehold(ip)];
    let calls = 0;
    for (const h of households) {
      for (let i = 0; i < 10 && calls < 20; i++, calls++) {
        await expectCode(call(fns.recoverParentDevice, req(h.parent, { method: 'EMAIL' })), 'invalid-argument');
      }
    }
    await expectCode(call(fns.recoverParentDevice, req(households[2].parent, { method: 'EMAIL' })), 'resource-exhausted');
  });
});

describe('household deletion', () => {
  it('deletes the recovery PIN check value of every parent', async () => {
    const h = await newHousehold();
    await setPin(h, pinFor(h));
    await db.doc(`recoveryPins/second-parent`).set({ hid: h.hid });
    await db.doc(`households/${h.hid}/members/second-parent`).set({ role: 'PARENT' });
    const other = await newHousehold();
    await setPin(other, pinFor(other));
    await call(fns.deleteHousehold, req(h.parent, withProof(h, 'deleteHousehold', { confirmation: h.hid })));
    expect((await pinRow(h.parent.uid)).exists).toBe(false);
    expect((await pinRow('second-parent')).exists).toBe(false);
    expect((await pinRow(other.parent.uid)).exists).toBe(true);
  });
});
