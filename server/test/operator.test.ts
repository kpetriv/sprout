import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OperatorError, operatorTarget, pruneRules, runOperator } from '../scripts/operator.js';
import { sha256Hex } from '../src/crypto.js';
import {
  auth,
  call,
  db,
  expectCode,
  fns,
  linkKid,
  newDevice,
  newHousehold,
  nowSeconds,
  req,
  resetEmulators,
  statusSignature,
  withProof,
  type Household,
  type TestDevice,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});

/** Runs the operator with [args], collecting its output lines. */
async function operator(...args: string[]): Promise<string[]> {
  const lines: string[] = [];
  await runOperator(args, { out: (line) => lines.push(line) });
  return lines;
}

async function refused(args: string[], message: RegExp): Promise<void> {
  await expect(runOperator(args, { out: () => undefined })).rejects.toThrow(message);
  await expect(runOperator(args, { out: () => undefined })).rejects.toBeInstanceOf(OperatorError);
}

/** The parent's new phone, signed in with the same account and registered PENDING. */
async function pendingPhone(h: Household): Promise<TestDevice> {
  const device = newDevice();
  await call(fns.registerParentDevice, req(h.parent, { devicePublicKey: device.publicKey }));
  return device;
}

const device = async (hid: string, id: string) => (await db.doc(`households/${hid}/devices/${id}`).get()).data()!;

const DAY = 24 * 60 * 60 * 1000;

/** Sets the `registeredAt` of device [id] to [days] days ago and returns it as the dry run prints it. */
async function registeredDaysAgo(hid: string, id: string, days: number): Promise<string> {
  const at = Timestamp.fromMillis(Math.floor((Date.now() - days * DAY) / 1000) * 1000);
  await db.doc(`households/${hid}/devices/${id}`).update({ registeredAt: at });
  return at.toDate().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

describe('target guard', () => {
  it('refuses anything but the emulator without --production', () => {
    const emulator = {
      SPROUT_EMULATOR: 'true',
      FIREBASE_PROJECT_ID: 'demo-sprout',
      FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080',
      FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099',
    };
    expect(operatorTarget(false, emulator)).toBe('emulator');
    expect(operatorTarget(true, emulator)).toBe('emulator');
    for (const env of [
      { ...emulator, FIREBASE_PROJECT_ID: 'sprout-3e1c7' },
      { ...emulator, SPROUT_EMULATOR: undefined },
      { ...emulator, FIRESTORE_EMULATOR_HOST: undefined },
      { FIREBASE_PROJECT_ID: 'sprout-3e1c7' },
    ]) {
      expect(() => operatorTarget(false, env)).toThrow(/Pass --production/);
      expect(operatorTarget(true, env)).toBe('production');
    }
  });

  it('refuses before touching anything when the environment is not the emulator', async () => {
    const saved = process.env.SPROUT_EMULATOR;
    process.env.SPROUT_EMULATOR = 'false';
    try {
      await refused(['prune-expired', '--apply'], /Pass --production/);
    } finally {
      process.env.SPROUT_EMULATOR = saved;
    }
  });

  it('prints the usage for --help and exits 0', async () => {
    const lines: string[] = [];
    await expect(runOperator(['--help'], { out: (line) => lines.push(line) })).resolves.toBe(0);
    expect(lines.join('\n')).toMatch(/^Usage: npm run operator/);
  });

  it('refuses unknown commands and options, and options of another command', async () => {
    await refused([], /Usage: npm run operator/);
    await refused(['wipe-everything'], /Unknown command: wipe-everything/);
    await refused(['delete-household', 'abc'], /Unexpected argument: abc/);
    await refused(['recover-household', '--hid', 'x', '--new-parent-uid', 'u', '--waiting-period-days', 'x'], /--waiting-period-days is not a valid value/);
    await refused(['prune-expired', '--force'], /Unknown option/);
    await refused(['prune-expired', '--hid', 'x'], /prune-expired does not take --hid/);
    await refused(['export-household', '--hid', 'x', '--apply'], /does not take --apply/);
    await refused(['delete-household', '--hid', 'a/b'], /--hid is not a valid value/);
    await refused(['recover-household', '--hid', 'x'], /--new-parent-uid is required/);
  });
});

describe('recover-household', () => {
  it('admits the PENDING phone, revokes the lost one and its keys, signs the parent out and writes an audit record', async () => {
    const h = await newHousehold();
    const lost = h.device.deviceId;
    await db.doc(`households/${h.hid}/events/e1`).set({ authorDeviceId: lost, lamport: 5 });
    await db.doc(`households/${h.hid}/parentKeys/${'7'.repeat(64)}`).set({ deviceId: lost, validFromLamport: 1 });
    const phone = await pendingPhone(h);
    const linked = await linkKid(h);
    const lostAt = await registeredDaysAgo(h.hid, lost, 90);
    const phoneAt = await registeredDaysAgo(h.hid, phone.deviceId, 8);

    const dry = await operator('recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid);
    expect(dry).toEqual([
      'Target: emulator, project demo-sprout',
      `recover-household ${h.hid} (dry run):`,
      `  parent devices of household ${h.hid}:`,
      ...[
        `    ${lost} user ${h.parent.uid} ACTIVE registered ${lostAt}`,
        `    ${phone.deviceId} user ${h.parent.uid} PENDING registered ${phoneAt}`,
      ].sort((a, b) => a.localeCompare(b)),
      `  admit PENDING parent device ${phone.deviceId} of user ${h.parent.uid} as ACTIVE`,
      `  revoke parent device ${lost} (ACTIVE) at lamport 6, 1 parent key(s): this phone was working; confirm with the requester that it is lost`,
      `  revoke the sessions of user ${h.parent.uid}: every phone of the account signs in again`,
      `Dry run: nothing was written. To do it: --apply --device ${phone.deviceId} --confirm ${h.hid}`,
    ]);
    expect((await device(h.hid, phone.deviceId)).status).toBe('PENDING');
    expect((await device(h.hid, lost)).status).toBe('ACTIVE');
    expect((await db.collection('recoveries').get()).size).toBe(0);

    await refused(
      ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--apply'],
      /--apply needs --confirm/,
    );
    await refused(
      ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--apply', '--confirm', 'other'],
      /--apply needs --confirm/,
    );
    await refused(
      ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--apply', '--confirm', h.hid],
      new RegExp(`--apply needs --device ${phone.deviceId}`),
    );
    expect((await device(h.hid, phone.deviceId)).status).toBe('PENDING');

    const before = nowSeconds() - 1;
    const out = await operator(
      'recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--device', phone.deviceId,
      '--ticket', 'INC-7', '--apply', '--confirm', h.hid,
    );
    expect(out.at(-1)).toMatch(new RegExp(`^Done: device ${phone.deviceId} is ACTIVE; audit record recoveries/\\w+$`));

    const admitted = await device(h.hid, phone.deviceId);
    expect(admitted).toMatchObject({ status: 'ACTIVE', approvedBy: 'operator' });
    const revoked = await device(h.hid, lost);
    expect(revoked).toMatchObject({ status: 'REVOKED', revokedAtLamport: 6 });
    expect(revoked.sessionsRevokedAt).toBeDefined();
    expect((await db.doc(`households/${h.hid}/parentKeys/${'7'.repeat(64)}`).get()).get('revokedAtLamport')).toBe(6);
    // The kid's phone is untouched.
    expect((await device(h.hid, linked.device.deviceId)).status).toBe('ACTIVE');

    const records = await db.collection('recoveries').get();
    expect(records.size).toBe(1);
    const record = records.docs[0];
    expect(record.id).toBe(admitted.recoveryId);
    expect(record.data()).toMatchObject({
      hidHash: sha256Hex(h.hid),
      deviceId: phone.deviceId,
      revokedDevices: [{ deviceId: lost, revokedAtLamport: 6 }],
      ticket: 'INC-7',
    });
    expect(Object.keys(record.data()).sort()).toEqual(['at', 'deviceId', 'hidHash', 'revokedDevices', 'ticket']);

    // Every session of the account signed in before the recovery is refused; the new phone signs in again and works.
    const validAfter = (await db.doc(`households/${h.hid}/members/${h.parent.uid}`).get()).get('tokensValidAfter') as number;
    expect(validAfter).toBeGreaterThanOrEqual(before);
    await expectCode(
      call(fns.exportHousehold, req({ ...h.parent, authTime: validAfter - 1 }, withProof(h, 'exportHousehold', {}, phone))),
      'unauthenticated',
    );
    await call(fns.exportHousehold, req({ ...h.parent, authTime: validAfter }, withProof(h, 'exportHousehold', {}, phone)));
    // The lost phone learns it was revoked.
    const status = await call(fns.deviceStatus, req(null, { hid: h.hid, deviceId: lost, signature: statusSignature(h.device, h.hid) }));
    expect(status).toEqual({ status: 'REVOKED', householdGone: false });

    // A second run has nothing left to admit.
    await refused(
      ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid],
      /has no PENDING parent device/,
    );
  });

  it('revokes a working phone of the account only after the waiting period (audit M1)', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    const args = ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--device', phone.deviceId];

    // Just registered: the dry run says why --apply would refuse, and --apply refuses without writing.
    const dry = await operator(...args);
    expect(dry.find((line) => line.startsWith('  --apply would be refused:'))).toMatch(
      new RegExp(`${h.device.deviceId} of user ${h.parent.uid} is ACTIVE, and ${phone.deviceId} registered less than 7 day\\(s\\) ago: wait until `),
    );
    await refused([...args, '--apply', '--confirm', h.hid], /Refused: .* is ACTIVE, .*New device waiting/);
    await registeredDaysAgo(h.hid, phone.deviceId, 6);
    await refused([...args, '--apply', '--confirm', h.hid], /Refused: /);
    expect((await device(h.hid, phone.deviceId)).status).toBe('PENDING');
    expect((await device(h.hid, h.device.deviceId)).status).toBe('ACTIVE');
    expect((await db.collection('recoveries').get()).size).toBe(0);

    // No registration time: refused as well.
    await db.doc(`households/${h.hid}/devices/${phone.deviceId}`).update({ registeredAt: FieldValue.delete() });
    await refused([...args, '--apply', '--confirm', h.hid], /its registration time is unknown/);

    // The override exists for tests and is never used by the runbook.
    await operator(...args, '--waiting-period-days', '0', '--apply', '--confirm', h.hid);
    expect((await device(h.hid, phone.deviceId)).status).toBe('ACTIVE');
    expect((await device(h.hid, h.device.deviceId)).status).toBe('REVOKED');
  });

  it('admits after the waiting period when the working phone did not remove the new one', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    await registeredDaysAgo(h.hid, phone.deviceId, 7);
    const dry = await operator('recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid);
    expect(dry.some((line) => line.includes('would be refused'))).toBe(false);
    await operator(
      'recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--device', phone.deviceId,
      '--apply', '--confirm', h.hid,
    );
    expect((await device(h.hid, phone.deviceId)).status).toBe('ACTIVE');
    expect((await device(h.hid, h.device.deviceId)).status).toBe('REVOKED');
  });

  it('completes a session revocation an earlier run did not finish (review L13)', async () => {
    const h = await newHousehold();
    const phone = await pendingPhone(h);
    await registeredDaysAgo(h.hid, phone.deviceId, 8);
    await operator(
      'recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--device', phone.deviceId,
      '--apply', '--confirm', h.hid,
    );
    // As if revokeParentSessions had failed after the transaction.
    await db.doc(`households/${h.hid}/devices/${h.device.deviceId}`).update({ sessionsRevokedAt: FieldValue.delete() });
    await db.doc(`households/${h.hid}/members/${h.parent.uid}`).update({ tokensValidAfter: FieldValue.delete() });

    const dry = await operator('recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid);
    expect(dry).toContain(
      `  complete: revoke the sessions of user ${h.parent.uid} for revoked device(s) ${h.device.deviceId} ` +
        '(an earlier revocation stopped before this step)',
    );
    const out = await operator('recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--apply', '--confirm', h.hid);
    expect(out.at(-1)).toBe(`Done: the sessions of user ${h.parent.uid} are revoked; every phone of the account signs in again`);
    expect((await device(h.hid, h.device.deviceId)).sessionsRevokedAt).toBeDefined();
    expect((await db.doc(`households/${h.hid}/members/${h.parent.uid}`).get()).get('tokensValidAfter')).toBeTypeOf('number');
    expect((await db.collection('recoveries').get()).size).toBe(1);
    await refused(['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid], /has no PENDING parent device/);
  });

  it('only admits when every parent device is already revoked, without signing anyone out', async () => {
    const h = await newHousehold();
    await db.doc(`households/${h.hid}/devices/${h.device.deviceId}`).update({ status: 'REVOKED', revokedAtLamport: 1 });
    const phone = await pendingPhone(h);
    const dry = await operator('recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid);
    expect(dry).toContain('  no other parent device to revoke');
    expect(dry.some((line) => line.includes('would be refused'))).toBe(false);
    // Nothing ACTIVE is revoked, so no waiting period applies.
    await operator(
      'recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--device', phone.deviceId,
      '--apply', '--confirm', h.hid,
    );
    expect((await device(h.hid, phone.deviceId)).status).toBe('ACTIVE');
    expect((await db.doc(`households/${h.hid}/members/${h.parent.uid}`).get()).get('tokensValidAfter')).toBeUndefined();
    const record = (await db.collection('recoveries').get()).docs[0].data();
    expect(record).toMatchObject({ revokedDevices: [], ticket: null });
  });

  it('needs --device to choose between several PENDING phones, and revokes the other one', async () => {
    const h = await newHousehold();
    const first = await pendingPhone(h);
    const second = await pendingPhone(h);
    await refused(['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid], /2 PENDING parent devices/);
    await refused(
      ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid, '--device', h.device.deviceId],
      /is not a PENDING parent device/,
    );
    await registeredDaysAgo(h.hid, second.deviceId, 8);
    await operator(
      'recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid,
      '--device', second.deviceId, '--apply', '--confirm', h.hid,
    );
    expect((await device(h.hid, second.deviceId)).status).toBe('ACTIVE');
    expect((await device(h.hid, first.deviceId)).status).toBe('REVOKED');
    expect((await device(h.hid, h.device.deviceId)).status).toBe('REVOKED');
  });

  it('refuses when another parent still has an ACTIVE phone, a stranger, a kid and an unknown household', async () => {
    const h = await newHousehold();
    await pendingPhone(h);
    const other = newDevice();
    await db.doc(`households/${h.hid}/members/second-parent`).set({ role: 'PARENT', addedAt: FieldValue.serverTimestamp() });
    await db.doc(`households/${h.hid}/devices/${other.deviceId}`).set({ uid: 'second-parent', role: 'PARENT', status: 'ACTIVE' });
    await refused(
      ['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid],
      /Another parent of household .* has an ACTIVE device/,
    );

    const linked = await linkKid(h, 'kid-b');
    await refused(['recover-household', '--hid', h.hid, '--new-parent-uid', linked.kid.uid], /is not a parent of household/);
    const stranger = await newHousehold();
    await refused(['recover-household', '--hid', h.hid, '--new-parent-uid', stranger.parent.uid], /is not a parent of household/);
    await refused(['recover-household', '--hid', h.hid, '--new-parent-uid', 'no-such-user'], /No Auth user/);

    // Claims that name the household while the member row is gone.
    await db.doc(`households/${h.hid}/members/${h.parent.uid}`).delete();
    await refused(['recover-household', '--hid', h.hid, '--new-parent-uid', h.parent.uid], /is not a PARENT member/);
    await auth.setCustomUserClaims(stranger.parent.uid, { hid: 'gone', role: 'PARENT' });
    await refused(['recover-household', '--hid', 'gone', '--new-parent-uid', stranger.parent.uid], /No household gone/);
  });
});

describe('delete-household', () => {
  it('dry-runs, needs --confirm, then deletes as deleteHousehold does', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    await db.doc(`households/${h.hid}/events/e1`).set({ authorDeviceId: h.device.deviceId, lamport: 1 });

    const dry = await operator('delete-household', '--hid', h.hid);
    expect(dry).toEqual([
      'Target: emulator, project demo-sprout',
      `delete-household ${h.hid} (dry run):`,
      '  2 member(s), 2 device(s), 1 event(s), 1 document(s); 2 Auth user(s) to delete',
      `Dry run: nothing was written. To do it: --apply --confirm ${h.hid}`,
    ]);
    expect((await db.doc(`households/${h.hid}`).get()).exists).toBe(true);
    await refused(['delete-household', '--hid', h.hid, '--apply'], /--apply needs --confirm/);

    const out = await operator('delete-household', '--hid', h.hid, '--apply', '--confirm', h.hid);
    expect(out.at(-1)).toBe(
      `Done: deletionRecords row written (hidHash ${sha256Hex(h.hid)}); 2 user(s) deleted, 0 failure(s)`,
    );
    expect((await db.doc(`households/${h.hid}`).get()).exists).toBe(false);
    expect((await db.collection(`households/${h.hid}/members`).get()).size).toBe(0);
    expect((await db.doc(`userHouseholds/${h.parent.uid}`).get()).exists).toBe(false);
    await expect(auth.getUser(h.parent.uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    await expect(auth.getUser(linked.kid.uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    const records = await db.collection('deletionRecords').where('hidHash', '==', sha256Hex(h.hid)).get();
    expect(records.docs.map((d) => d.get('counts') as unknown)).toEqual([{ members: 2, devices: 2, events: 1, documents: 1 }]);
    const status = await call(
      fns.deviceStatus,
      req(null, { hid: h.hid, deviceId: h.device.deviceId, signature: statusSignature(h.device, h.hid) }),
    );
    expect(status).toEqual({ status: 'UNKNOWN', householdGone: true });

    await refused(['delete-household', '--hid', h.hid], /No household/);
  });

  it('prints the Auth users it could not delete, since a second run cannot find them (review M2)', async () => {
    const h = await newHousehold();
    const linked = await linkKid(h);
    const spy = vi.spyOn(auth, 'deleteUsers').mockResolvedValueOnce({
      successCount: 1,
      failureCount: 1,
      errors: [{ index: 1, error: new Error('internal') as never }],
    });
    try {
      const out = await operator('delete-household', '--hid', h.hid, '--apply', '--confirm', h.hid);
      const uids = spy.mock.calls[0][0];
      expect([...uids].sort()).toEqual([h.parent.uid, linked.kid.uid].sort());
      expect(out).toContain(`These Auth users were not deleted: ${uids[1]}`);
      expect(out.at(-1)).toMatch(/Firebase console → Authentication → Users .* there is no firebase CLI command/);
      expect(out.join('\n')).toContain('1 user(s) deleted, 1 failure(s)');
    } finally {
      spy.mockRestore();
    }
    await refused(['delete-household', '--hid', h.hid], /No household/);
  });
});

describe('export-household', () => {
  let dir: string;
  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), 'sprout-export-'));
  });
  afterEach(async () => {
    await rm(dir, { recursive: true });
  });

  it('writes the bundle to a new owner-only file and prints counts only', async () => {
    const h = await newHousehold();
    await linkKid(h);
    await db.doc(`households/${h.hid}/events/e1`).set({ authorDeviceId: h.device.deviceId, lamport: 1 });
    const file = join(dir, 'export.json');
    const out = await operator('export-household', '--hid', h.hid, '--out', file);
    expect(out[1]).toMatch(new RegExp(`^Exported household ${h.hid} to .*export.json: 1 event\\(s\\), 1 document\\(s\\), 0 message\\(s\\), 0 blob chunk header\\(s\\), \\d+ bytes$`));
    expect(out.join('\n')).not.toContain('Matilda');
    expect((await stat(file)).mode & 0o777).toBe(0o600);
    const bundle = JSON.parse(await readFile(file, 'utf8')) as Record<string, unknown[]> & { collections: Record<string, unknown[]> };
    expect(bundle.devices).toHaveLength(2);
    expect(bundle.events).toHaveLength(1);
    expect(bundle.collections.kids).toHaveLength(1);

    await refused(['export-household', '--hid', h.hid, '--out', file], /exists: choose a new file/);
    await refused(['export-household', '--hid', 'nope', '--out', join(dir, 'other.json')], /No household nope/);
    await refused(['export-household', '--hid', h.hid], /--out is required/);
  });
});

describe('prune-expired', () => {
  it('deletes records past their retention and expired TTL rows, and nothing else', async () => {
    const now = new Date();
    const ago = (days: number) => Timestamp.fromMillis(now.getTime() - days * 24 * 60 * 60 * 1000);
    await Promise.all([
      db.doc('deletionRecords/old').set({ hidHash: 'a', at: ago(400) }),
      db.doc('deletionRecords/new').set({ hidHash: 'b', at: ago(30) }),
      db.doc('recoveries/old').set({ hidHash: 'a', at: ago(370) }),
      db.doc('recoveries/new').set({ hidHash: 'b', at: ago(300) }),
      db.doc('rateLimits/stale').set({ hits: [], expiresAt: ago(1) }),
      db.doc('rateLimits/live').set({ hits: [], expiresAt: ago(-1) }),
      db.doc('linkCodes/expired').set({ hid: 'h', expiresAt: ago(1) }),
      db.doc('linkCodes/live').set({ hid: 'h', expiresAt: ago(-1) }),
      db.doc('challenges/expired').set({ hash: 'x', expiresAt: ago(1) }),
      db.doc('challenges/live').set({ hash: 'x', expiresAt: ago(-1) }),
    ]);

    const dry = await operator('prune-expired');
    expect(dry).toEqual([
      'Target: emulator, project demo-sprout',
      'prune-expired (dry run):',
      '  deletionRecords: 1 older than 12 months',
      '  recoveries: 1 older than 12 months',
      '  rateLimits: 1 expired (stale bucket)',
      '  linkCodes: 1 expired',
      '  challenges: 1 expired',
      'Dry run: nothing was written. To do it: --apply',
    ]);
    expect((await db.collection('rateLimits').get()).size).toBe(2);

    const out = await operator('prune-expired', '--apply');
    expect(out).toContain('  deletionRecords: deleted 1 older than 12 months');
    for (const name of ['deletionRecords', 'recoveries', 'rateLimits', 'linkCodes', 'challenges']) {
      const ids = (await db.collection(name).get()).docs.map((d) => d.id);
      expect(ids, name).toEqual([name === 'deletionRecords' || name === 'recoveries' ? 'new' : 'live']);
    }
  });

  it('cuts records at 12 calendar months', () => {
    const rules = pruneRules(new Date('2026-11-04T10:00:00Z'));
    expect(rules.find((r) => r.collection === 'deletionRecords')!.before.toISOString()).toBe('2025-11-04T10:00:00.000Z');
    expect(rules.find((r) => r.collection === 'rateLimits')!.before.toISOString()).toBe('2026-11-04T10:00:00.000Z');
  });
});
