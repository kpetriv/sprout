import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  addKid,
  call,
  expectCode,
  fns,
  newDevice,
  newHousehold,
  newUser,
  parentKeySignature,
  req,
  resetEmulators,
  withProof,
} from './helpers.js';

// Every production log line goes through src/log.ts to firebase-functions/logger (eslint forbids console and other
// logger imports in src/), so capturing the logger captures everything the functions log.
const captured = vi.hoisted(() => [] as unknown[][]);
vi.mock('firebase-functions/logger', () => {
  const capture = (...args: unknown[]) => void captured.push(args);
  return { info: capture, warn: capture, error: capture, debug: capture, log: capture, write: capture };
});

const consoleOutput: string[] = [];

beforeEach(async () => {
  await resetEmulators();
  captured.length = 0;
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

    expect(captured.length).toBeGreaterThan(5);
    const events = captured.map((args) => args[0]);
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
    for (const args of captured) {
      expect(typeof args[0]).toBe('string');
      for (const value of Object.values((args[1] ?? {}) as Record<string, unknown>)) {
        expect(['string', 'number', 'boolean'].includes(typeof value) || value === null).toBe(true);
      }
    }
    const everything = JSON.stringify(captured) + consoleOutput.join('\n');
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
      keySignature,
      linkProof.signature as string,
      revokeProof.signature as string,
      deleteProof.signature as string,
    ];
    for (const secret of secrets) {
      expect(everything).not.toContain(secret);
    }
  });
});
