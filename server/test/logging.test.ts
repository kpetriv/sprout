import { pbkdf2Sync, randomBytes } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  addKid,
  call,
  db,
  expectCode,
  fns,
  newDevice,
  newHousehold,
  newUser,
  parentKeySignature,
  Proof,
  req,
  resetEmulators,
  signed,
  withProof,
} from './helpers.js';

// Every production log line goes through src/log.ts to the console as one JSON line (eslint forbids console and other
// loggers elsewhere in src/), so capturing the console captures everything the server logs.
const consoleOutput: string[] = [];

/** The server's log lines: [event, fields] of every captured JSON line with a severity. */
function captured(): [string, Record<string, unknown>][] {
  return consoleOutput.flatMap((text) => {
    try {
      const { message, severity, time, ...fields } = JSON.parse(text) as Record<string, unknown>;
      if (typeof severity !== 'string' || typeof time !== 'string') return [];
      return [[message as string, fields] as [string, Record<string, unknown>]];
    } catch {
      return [];
    }
  });
}

beforeEach(async () => {
  await resetEmulators();
  consoleOutput.length = 0;
  for (const method of ['log', 'info', 'warn', 'error', 'debug'] as const) {
    vi.spyOn(console, method).mockImplementation((...args: unknown[]) => void consoleOutput.push(args.map(String).join(' ')));
  }
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('logging', () => {
  it('logs ids and counts only: never a link code, a name, an IP, a key or a challenge', async () => {
    const ip = '203.0.113.99';
    const h = await newHousehold(ip);
    const kidName = 'Matilda';
    const kidId = await addKid(h.hid, 'kid-a', kidName);
    const linkProof = withProof(h, 'createLinkCode', { kidId });
    const { code } = await call(fns.createLinkCode, req(h.parent, linkProof));
    const kidUid = await newUser();
    const kidDevice = newDevice();
    const { challenge } = await call(fns.beginDeviceRegistration, req({ uid: kidUid, anonymous: true, ip }));
    await expectCode(
      call(fns.redeemLinkCode, req({ uid: kidUid, anonymous: true, ip }, { code: 'WRNGCDEX', devicePublicKey: kidDevice.publicKey })),
      'not-found',
    );
    await call(fns.redeemLinkCode, req({ uid: kidUid, anonymous: true, ip }, { code, devicePublicKey: kidDevice.publicKey }));
    const parentKey = newDevice();
    const keySignature = parentKeySignature(h.device, h.hid, parentKey.spki);
    await call(
      fns.registerParentKey,
      req(h.parent, { publicKey: parentKey.publicKey, deviceId: h.device.deviceId, signature: keySignature }),
    );
    await expectCode(
      call(fns.registerParentKey, req(h.parent, { publicKey: parentKey.publicKey, deviceId: h.device.deviceId, signature: 'AAAA' })),
      'permission-denied',
    );
    const second = newDevice();
    await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: second.publicKey }));
    const revokeProof = withProof(h, 'revokeDevice', { deviceId: second.deviceId });
    await call(fns.revokeDevice, req(h.parent, revokeProof));
    for (let i = 0; i < 6; i++) await call(fns.exportHousehold, req(h.parent, withProof(h, 'exportHousehold'))).catch(() => undefined);
    const deleteProof = withProof(h, 'deleteHousehold', { confirmation: h.hid });
    await call(fns.deleteHousehold, req(h.parent, deleteProof));

    const lines = captured();
    expect(lines.length).toBeGreaterThan(5);
    const events = lines.map((args) => args[0]);
    expect(events).toEqual(
      expect.arrayContaining([
        'household.created',
        'linkCode.created',
        'linkCode.rejected',
        'linkCode.redeemed',
        'device.registered',
        'parentKey.registered',
        'device.revoked',
        'household.exported',
        'rateLimit.exceeded',
        'household.deleted',
      ]),
    );
    for (const [event, fields] of lines) {
      expect(typeof event).toBe('string');
      for (const value of Object.values(fields)) {
        expect(['string', 'number', 'boolean'].includes(typeof value) || value === null).toBe(true);
      }
    }
    const everything = consoleOutput.join('\n');
    const secrets = [
      code,
      code.toLowerCase(),
      'WRNGCDEX',
      kidName,
      ip,
      challenge,
      kidDevice.publicKey,
      h.device.publicKey,
      parentKey.publicKey,
      ...keySignature.sent,
      ...(linkProof.signature as Proof).sent,
      ...(revokeProof.signature as Proof).sent,
      ...(deleteProof.signature as Proof).sent,
    ];
    expect(secrets.length).toBe(13);
    for (const secret of secrets) {
      expect(everything).not.toContain(secret);
    }
  });

  it('logs account recovery as ids and counts: never the PIN, its salt, verifier, MAC or KDF', async () => {
    const h = await newHousehold();
    const pin = '730461';
    const kdf = 'pbkdf2-sha256$i=1000';
    const saltBytes = randomBytes(16);
    const salt = saltBytes.toString('base64');
    const derive = (p: string) =>
      pbkdf2Sync(Buffer.from(`sprout/recovery-pin/v1\u0000${h.hid}\u0000${p}`, 'utf8'), saltBytes, 1000, 32, 'sha256').toString('base64');
    const verifier = derive(pin);
    const wrong = derive('111111');
    const setProof = withProof(h, 'setRecoveryPin', { kdf, newPin: true, salt, verifier });
    await call(fns.setRecoveryPin, req(h.parent, setProof));
    const mac = (await db.doc(`recoveryPins/${h.parent.uid}`).get()).get('mac') as string;

    const phone = newDevice();
    await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: phone.publicKey }));
    const own = (callable: 'recoveryStatus' | 'recoverParentDevice', args: Record<string, unknown> = {}) => {
      const { callerDeviceId, signature } = signed(phone, callable, h.hid, args);
      return { ...args, deviceId: callerDeviceId, signature };
    };
    const statusProof = own('recoveryStatus');
    await call(fns.recoveryStatus, req(h.parent, statusProof));
    const wrongProof = own('recoverParentDevice', { method: 'PIN', verifier: wrong });
    await expectCode(call(fns.recoverParentDevice, req(h.parent, wrongProof)), 'permission-denied');
    const waitProof = own('recoverParentDevice', { method: 'WAIT' });
    await expectCode(call(fns.recoverParentDevice, req(h.parent, waitProof)), 'failed-precondition');
    const rightProof = own('recoverParentDevice', { method: 'PIN', verifier });
    await call(fns.recoverParentDevice, req(h.parent, rightProof));

    const lines = captured();
    const events = lines.map((args) => args[0]);
    expect(events).toEqual(
      expect.arrayContaining([
        'recoveryPin.set',
        'recovery.statusChecked',
        'device.recoveryRefused',
        'device.recoveryWaitStarted',
        'device.recovered',
      ]),
    );
    const refusals = lines.filter(([event]) => event === 'device.recoveryRefused').map(([, fields]) => fields);
    expect(refusals).toEqual([expect.objectContaining({ reason: 'wrong-pin', attemptsLeft: 9, deviceId: phone.deviceId })]);
    const waitStarted = lines.find(([event]) => event === 'device.recoveryWaitStarted')![1];
    expect(waitStarted.deviceId).toBe(phone.deviceId);
    expect(waitStarted.waitEndsAt).toBeTypeOf('number');
    expect(lines.find(([event]) => event === 'device.recovered')![1]).toMatchObject({
      hid: h.hid,
      deviceId: phone.deviceId,
      method: 'PIN',
      revokedDevices: 1,
    });
    for (const [, fields] of lines) {
      for (const value of Object.values(fields)) {
        expect(['string', 'number', 'boolean'].includes(typeof value) || value === null).toBe(true);
      }
    }
    const everything = consoleOutput.join('\n');
    const secrets = [
      pin,
      salt,
      verifier,
      wrong,
      mac,
      kdf,
      Buffer.from(verifier, 'base64').toString('hex'),
      Buffer.from(mac, 'base64').toString('hex'),
      ...(setProof.signature as Proof).sent,
      ...(statusProof.signature as Proof).sent,
      ...(wrongProof.signature as Proof).sent,
      ...(waitProof.signature as Proof).sent,
      ...(rightProof.signature as Proof).sent,
    ];
    expect(secrets.length).toBe(13);
    for (const secret of secrets) {
      expect(everything).not.toContain(secret);
    }
  });
});
