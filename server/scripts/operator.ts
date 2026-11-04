/**
 * The operator script (M8 §6, D-M3-42, D-M8-7, D-M8-11, D-M8-12): household operations only the owner runs, with the
 * Admin SDK and the server's service account, for the cases the app cannot handle itself (docs/RUNBOOK.md §2, §4).
 *
 *   npm run operator -- recover-household --hid <hid> --new-parent-uid <uid> [--device <deviceId>] [--ticket <ref>]
 *                                         [--apply --device <deviceId> --confirm <hid>] [--production]
 *   npm run operator -- delete-household  --hid <hid> [--apply --confirm <hid>] [--production]
 *   npm run operator -- export-household  --hid <hid> --out <file> [--production]
 *   npm run operator -- prune-expired     [--apply] [--production]
 *
 * - **Dry run by default**: every command that writes to Firestore or Auth prints what it would do and writes nothing
 *   until `--apply`; the household commands also need `--confirm <hid>` repeating the id. `export-household` only
 *   reads (it writes the local file, mode 0600, never over an existing one).
 * - **Target guard**: it runs against the emulator when the environment is the emulator's (`SPROUT_EMULATOR=true`, a
 *   `demo-` project id and both emulator host variables); anything else is a real project and is refused without
 *   `--production`. Credentials there are those of the server (src/firebase.ts): `FIREBASE_SERVICE_ACCOUNT_JSON` or
 *   `GOOGLE_APPLICATION_CREDENTIALS`, with `FIREBASE_PROJECT_ID` optional.
 * - **Output** names ids and counts only (household id, uids, device ids, collection counts): never a name, an e-mail
 *   address, a key or any household content (T9). The export's content goes to the file only.
 */
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import type { QueryDocumentSnapshot, Transaction } from 'firebase-admin/firestore';
import { isEmulator, projectId } from '../src/config.js';
import type { RevocationPlan } from '../src/handlers/devices.js';

/** A refusal or a usage error: printed as is, exit status 2. */
export class OperatorError extends Error {}

export interface OperatorIo {
  /** One line of output for the operator. */
  out(line: string): void;
  /** The clock (prune-expired); defaults to now. */
  now?: Date;
}

const COMMANDS = ['recover-household', 'delete-household', 'export-household', 'prune-expired'] as const;
type Command = (typeof COMMANDS)[number];

/** The options each command accepts besides `--production` (anything else is refused). */
const ALLOWED: Record<Command, readonly string[]> = {
  'recover-household': ['hid', 'new-parent-uid', 'device', 'ticket', 'apply', 'confirm', 'waiting-period-days'],
  'delete-household': ['hid', 'apply', 'confirm'],
  'export-household': ['hid', 'out'],
  'prune-expired': ['apply'],
};

const ID = /^[A-Za-z0-9._-]{1,128}$/;
const DEVICE_ID = /^[0-9a-f]{64}$/;
const TICKET = /^[A-Za-z0-9._#-]{1,64}$/;

/** deletionRecords and recoveries are kept this many months (PRIVACY.md, RUNBOOK §4). */
export const RECORD_RETENTION_MONTHS = 12;

/**
 * recover-household revokes an ACTIVE phone of the account only when the PENDING phone it admits registered at least
 * this many days ago (audit M1, D-M8-43): meanwhile the working phone shows "New device waiting" and can remove it, so a
 * takeover of the Google account cannot evict the real parent through the operator before they notice.
 * `--waiting-period-days` overrides it (tests); the runbook never uses it in production.
 */
export const RECOVERY_WAITING_DAYS = 7;

const DAY_MS = 24 * 60 * 60 * 1000;

export const USAGE = `Usage: npm run operator -- <command> [options]

  recover-household --hid <hid> --new-parent-uid <uid> [--device <deviceId>] [--ticket <ref>]
                    [--apply --device <deviceId> --confirm <hid>]
      Admit the PENDING parent device of <uid> as ACTIVE when the household has no working parent device (D-M3-42),
      revoking that parent's other parent devices, and write a recoveries/{id} audit record. An ACTIVE device of <uid>
      is revoked only when the admitted one registered ${RECOVERY_WAITING_DAYS} or more days ago. With no PENDING device left, completes
      the session revocation an earlier run did not finish.
  delete-household --hid <hid> [--apply --confirm <hid>]
      Delete the household as deleteHousehold does (RUNBOOK §4), writing its deletionRecords row.
  export-household --hid <hid> --out <file>
      Write the household's export bundle (as exportHousehold, without its size limits) to a new file (mode 0600).
  prune-expired [--apply]
      Delete deletionRecords and recoveries older than ${RECORD_RETENTION_MONTHS} months and expired rateLimits, linkCodes
      and challenges (the TTL policies' fallback).

  Dry run unless --apply. Outside the emulator every command also needs --production.`;

type Values = Record<string, string | boolean | undefined>;

function parse(argv: readonly string[]): { command: Command; values: Values } | 'help' {
  let parsed: ReturnType<typeof parseArgs>;
  try {
    parsed = parseArgs({
      args: [...argv],
      allowPositionals: true,
      strict: true,
      options: {
        hid: { type: 'string' },
        'new-parent-uid': { type: 'string' },
        device: { type: 'string' },
        ticket: { type: 'string' },
        confirm: { type: 'string' },
        out: { type: 'string' },
        apply: { type: 'boolean' },
        production: { type: 'boolean' },
        'waiting-period-days': { type: 'string' },
        help: { type: 'boolean' },
      },
    });
  } catch (error) {
    throw new OperatorError(`${error instanceof Error ? error.message : String(error)}\n\n${USAGE}`);
  }
  const { positionals, values } = parsed;
  if (values.help) return 'help';
  if (positionals.length === 0) throw new OperatorError(USAGE);
  const command = positionals[0] as Command;
  if (!COMMANDS.includes(command)) throw new OperatorError(`Unknown command: ${command}\n\n${USAGE}`);
  if (positionals.length > 1) {
    throw new OperatorError(`Unexpected argument: ${positionals.slice(1).join(' ')} (options start with --)`);
  }
  for (const key of Object.keys(values)) {
    if (key !== 'production' && !ALLOWED[command].includes(key)) {
      throw new OperatorError(`${command} does not take --${key}`);
    }
  }
  return { command, values: values as Values };
}

function stringOption(values: Values, name: string, pattern: RegExp, required = true): string | undefined {
  const value = values[name];
  if (value === undefined) {
    if (required) throw new OperatorError(`--${name} is required`);
    return undefined;
  }
  if (typeof value !== 'string' || !pattern.test(value)) throw new OperatorError(`--${name} is not a valid value`);
  return value;
}

/**
 * Where the script would write: `emulator` when the environment is the emulator's, otherwise `production`, which is
 * refused unless [production] (`--production`) is set.
 */
export function operatorTarget(production: boolean, env: NodeJS.ProcessEnv = process.env): 'emulator' | 'production' {
  const emulator =
    isEmulator(env) && (env.FIRESTORE_EMULATOR_HOST ?? '') !== '' && (env.FIREBASE_AUTH_EMULATOR_HOST ?? '') !== '';
  if (emulator) return 'emulator';
  if (!production) {
    throw new OperatorError(
      'Not the emulator (SPROUT_EMULATOR=true, a demo- project and both emulator hosts): this would run against a ' +
        'real project. Pass --production to do that.',
    );
  }
  return 'production';
}

/** Requires `--confirm <hid>` with `--apply`. */
function requireConfirmation(values: Values, hid: string): void {
  if (values.confirm !== hid) throw new OperatorError(`--apply needs --confirm ${hid} (the household id again)`);
}

/**
 * Runs the operator command of [argv] and resolves with its exit status (0); refusals reject with an
 * [OperatorError]. The Admin SDK is loaded only after the target guard passed.
 */
export async function runOperator(argv: readonly string[], io: OperatorIo): Promise<number> {
  const parsed = parse(argv);
  if (parsed === 'help') {
    io.out(USAGE);
    return 0;
  }
  const { command, values } = parsed;
  const target = operatorTarget(values.production === true);
  const { getApp } = await import('firebase-admin/app');
  try {
    await import('../src/firebase.js');
  } catch (error) {
    // src/firebase.ts's own configuration errors (no credentials, an emulator host outside the emulator): no data.
    throw new OperatorError(`Cannot initialise the Admin SDK: ${error instanceof Error ? error.message : String(error)}`);
  }
  const project = getApp().options.projectId ?? projectId() ?? '(from the credentials)';
  io.out(target === 'emulator' ? `Target: emulator, project ${project}` : `Target: PRODUCTION project ${project}`);
  const apply = values.apply === true;

  switch (command) {
    case 'recover-household':
      await recoverHousehold(values, apply, io);
      break;
    case 'delete-household':
      await deleteHousehold(values, apply, io);
      break;
    case 'export-household':
      await exportHousehold(values, io);
      break;
    case 'prune-expired':
      await pruneExpired(apply, io);
      break;
  }
  return 0;
}

function dryRunNote(io: OperatorIo, again: string): void {
  io.out(`Dry run: nothing was written. To do it: ${again}`);
}

/** A Firestore Timestamp (or anything else) as an ISO date-time, `unknown` when it is not a timestamp. */
function isoOf(value: unknown): string {
  const date = (value as { toDate?: () => Date } | undefined)?.toDate?.();
  return date instanceof Date ? date.toISOString().replace(/\.\d{3}Z$/, 'Z') : 'unknown';
}

function millisOf(value: unknown): number | undefined {
  const date = (value as { toDate?: () => Date } | undefined)?.toDate?.();
  return date instanceof Date ? date.getTime() : undefined;
}

/**
 * recover-household (D-M3-42, RUNBOOK §2): the household's parent phones are lost, so no ACTIVE parent device can
 * approve the parent's new phone. After the operator verified out of band that the requester controls the parent's
 * Google account (the uid), and the new phone signed in with it and registered PENDING:
 * - the dry run lists every parent device of the household (uid, status, `registeredAt`) and the plan;
 * - `--apply` admits the PENDING parent device named by `--device` (required with `--apply`) as ACTIVE
 *   (`approvedBy: "operator"`);
 * - every other non-revoked parent device of that uid is revoked as revokeDevice does (revokedAtLamport, parent keys
 *   stamped), and when one was, that parent's sessions are revoked (refresh tokens, `tokensValidAfter`): every phone of
 *   the account signs out, the new one included, and signs in again;
 * - an ACTIVE device of that uid is revoked only when the admitted device registered [RECOVERY_WAITING_DAYS] or more
 *   days ago (audit M1, D-M8-43): during that time the working phone shows "New device waiting" and can remove it;
 * - `recoveries/{autoId}` records `{ hidHash, at, deviceId, revokedDevices, ticket }` (no uid, no names).
 * Refused when the uid is not a PARENT member with matching claims, when it has no PENDING parent device, or when
 * another parent of the household still has an ACTIVE device (that parent approves the phone in the app instead).
 *
 * When the uid has no PENDING device but has REVOKED parent devices whose session revocation never completed (an
 * earlier run, or an in-app revoke, stopped after its transaction), the command completes that step instead
 * (review L13): the dry run names the devices, `--apply --confirm` revokes the sessions.
 */
async function recoverHousehold(values: Values, apply: boolean, io: OperatorIo): Promise<void> {
  const hid = stringOption(values, 'hid', ID)!;
  const uid = stringOption(values, 'new-parent-uid', ID)!;
  const deviceOption = stringOption(values, 'device', DEVICE_ID, false);
  const ticket = stringOption(values, 'ticket', TICKET, false) ?? null;
  const waitingOption = stringOption(values, 'waiting-period-days', /^\d{1,3}$/, false);
  const waitingDays = waitingOption === undefined ? RECOVERY_WAITING_DAYS : Number(waitingOption);
  const now = io.now ?? new Date();
  if (apply) requireConfirmation(values, hid);

  const { FieldValue } = await import('firebase-admin/firestore');
  const { auth, db } = await import('../src/firebase.js');
  const { sha256Hex } = await import('../src/crypto.js');
  const { planRevocation, applyRevocation, revokeParentSessions } = await import('../src/handlers/devices.js');

  const user = await auth.getUser(uid).catch(() => undefined);
  if (user === undefined) throw new OperatorError(`No Auth user ${uid}`);
  if (user.customClaims?.hid !== hid || user.customClaims?.role !== 'PARENT') {
    throw new OperatorError(`User ${uid} is not a parent of household ${hid} (custom claims)`);
  }

  const household = db.doc(`households/${hid}`);
  type Prepared =
    | { kind: 'admit'; admitted: QueryDocumentSnapshot; plans: RevocationPlan[]; devices: QueryDocumentSnapshot[] }
    | { kind: 'complete'; unfinished: QueryDocumentSnapshot[]; devices: QueryDocumentSnapshot[] };
  const prepare = async (tx: Transaction): Promise<Prepared> => {
    const [home, member, parentDevices] = await Promise.all([
      tx.get(household),
      tx.get(household.collection('members').doc(uid)),
      tx.get(household.collection('devices').where('role', '==', 'PARENT')),
    ]);
    if (!home.exists) throw new OperatorError(`No household ${hid}`);
    if (!member.exists || member.get('role') !== 'PARENT') {
      throw new OperatorError(`User ${uid} is not a PARENT member of household ${hid}`);
    }
    const devices = parentDevices.docs;
    const own = devices.filter((d) => d.get('uid') === uid);
    const pending = own.filter((d) => d.get('status') === 'PENDING');
    if (pending.length === 0 && deviceOption === undefined) {
      const unfinished = own.filter((d) => d.get('status') === 'REVOKED' && d.get('sessionsRevokedAt') === undefined);
      if (unfinished.length > 0) return { kind: 'complete', unfinished, devices };
    }
    let admitted: QueryDocumentSnapshot;
    if (deviceOption !== undefined) {
      const chosen = pending.find((d) => d.id === deviceOption);
      if (chosen === undefined) throw new OperatorError(`${deviceOption} is not a PENDING parent device of ${uid}`);
      admitted = chosen;
    } else if (pending.length === 0) {
      throw new OperatorError(
        `User ${uid} has no PENDING parent device: sign in on the new phone first (it registers PENDING)`,
      );
    } else if (pending.length > 1) {
      throw new OperatorError(
        `User ${uid} has ${pending.length} PENDING parent devices (${pending.map((d) => d.id).join(', ')}): ` +
          'choose one with --device after comparing its fingerprint with the new phone',
      );
    } else {
      admitted = pending[0];
    }
    const otherActive = devices.filter((d) => d.get('uid') !== uid && d.get('status') === 'ACTIVE');
    if (otherActive.length > 0) {
      throw new OperatorError(
        `Another parent of household ${hid} has an ACTIVE device (${otherActive.map((d) => d.id).join(', ')}): ` +
          'approve the new phone from it in the app (RUNBOOK §2)',
      );
    }
    const plans: RevocationPlan[] = [];
    for (const device of own) {
      if (device.id === admitted.id || device.get('status') === 'REVOKED') continue;
      plans.push(await planRevocation(tx, household, device));
    }
    return { kind: 'admit', admitted, plans, devices };
  };

  /** Why `--apply` refuses the plan (the waiting period), else undefined. */
  const waitingProblem = (admitted: QueryDocumentSnapshot, plans: readonly RevocationPlan[]): string | undefined => {
    const active = plans.filter((plan) => plan.target.get('status') === 'ACTIVE').map((plan) => plan.target.id);
    if (active.length === 0 || waitingDays === 0) return undefined;
    const registered = millisOf(admitted.get('registeredAt'));
    const until = registered === undefined ? undefined : registered + waitingDays * DAY_MS;
    if (until !== undefined && until <= now.getTime()) return undefined;
    const when = until === undefined ? 'its registration time is unknown' : `wait until ${new Date(until).toISOString()}`;
    return (
      `${active.join(', ')} of user ${uid} is ACTIVE, and ${admitted.id} registered less than ${waitingDays} day(s) ` +
      `ago: ${when}. Meanwhile the working phone shows "New device waiting" and can remove it; if the requester still ` +
      'has that phone, they approve the new one there instead (RUNBOOK §2)'
    );
  };

  const listDevices = (devices: readonly QueryDocumentSnapshot[]) => {
    io.out(`  parent devices of household ${hid}:`);
    for (const d of devices) {
      io.out(
        `    ${d.id} user ${String(d.get('uid'))} ${String(d.get('status'))} registered ${isoOf(d.get('registeredAt'))}` +
          (d.get('approvedBy') === 'operator' ? ' (admitted by the operator)' : ''),
      );
    }
  };

  const describe = (admittedId: string, plans: readonly RevocationPlan[]) => {
    io.out(`  admit PENDING parent device ${admittedId} of user ${uid} as ACTIVE`);
    for (const plan of plans) {
      const status = String(plan.target.get('status'));
      io.out(
        `  revoke parent device ${plan.target.id} (${status}) at lamport ${plan.revokedAtLamport}, ` +
          `${plan.parentKeys.length} parent key(s)` +
          (status === 'ACTIVE' ? ': this phone was working; confirm with the requester that it is lost' : ''),
      );
    }
    if (plans.length > 0) io.out(`  revoke the sessions of user ${uid}: every phone of the account signs in again`);
    else io.out('  no other parent device to revoke');
  };

  const revokeSessions = async (deviceIds: readonly string[]) => {
    const remaining = [...deviceIds];
    try {
      while (remaining.length > 0) {
        await revokeParentSessions(hid, uid, remaining[0]);
        remaining.shift();
      }
    } catch (error) {
      io.out(
        `The sessions of user ${uid} are not revoked yet for device(s) ${remaining.join(', ')}: run ` +
          `recover-household --hid ${hid} --new-parent-uid ${uid} --apply --confirm ${hid} again to complete it`,
      );
      throw error;
    }
  };

  if (!apply) {
    const prepared = await db.runTransaction(prepare, { readOnly: true });
    io.out(`recover-household ${hid} (dry run):`);
    listDevices(prepared.devices);
    if (prepared.kind === 'complete') {
      io.out(
        `  complete: revoke the sessions of user ${uid} for revoked device(s) ` +
          `${prepared.unfinished.map((d) => d.id).join(', ')} (an earlier revocation stopped before this step)`,
      );
      dryRunNote(io, `--apply --confirm ${hid}`);
      return;
    }
    describe(prepared.admitted.id, prepared.plans);
    const problem = waitingProblem(prepared.admitted, prepared.plans);
    if (problem !== undefined) io.out(`  --apply would be refused: ${problem}`);
    dryRunNote(io, `--apply --device ${prepared.admitted.id} --confirm ${hid}`);
    return;
  }

  const record = db.collection('recoveries').doc();
  const result = await db.runTransaction(async (tx) => {
    const prepared = await prepare(tx);
    if (prepared.kind === 'complete') return prepared;
    if (deviceOption === undefined) {
      throw new OperatorError(`--apply needs --device ${prepared.admitted.id} (the device the dry run admits)`);
    }
    const { admitted, plans } = prepared;
    const problem = waitingProblem(admitted, plans);
    if (problem !== undefined) throw new OperatorError(`Refused: ${problem}`);
    const outcomes = plans.map((plan) => ({ deviceId: plan.target.id, ...applyRevocation(tx, plan) }));
    tx.update(admitted.ref, {
      status: 'ACTIVE',
      approvedBy: 'operator',
      approvedAt: FieldValue.serverTimestamp(),
      recoveryId: record.id,
    });
    tx.create(record, {
      hidHash: sha256Hex(hid),
      at: FieldValue.serverTimestamp(),
      deviceId: admitted.id,
      revokedDevices: outcomes.map((o) => ({ deviceId: o.deviceId, revokedAtLamport: o.revokedAtLamport })),
      ticket,
    });
    return { kind: 'admitted' as const, admittedId: admitted.id, plans, outcomes };
  });
  io.out(`recover-household ${hid}:`);
  if (result.kind === 'complete') {
    await revokeSessions(result.unfinished.map((d) => d.id));
    io.out(`Done: the sessions of user ${uid} are revoked; every phone of the account signs in again`);
    return;
  }
  describe(result.admittedId, result.plans);
  await revokeSessions(result.outcomes.filter((o) => o.sessionsPending).map((o) => o.deviceId));
  io.out(`Done: device ${result.admittedId} is ACTIVE; audit record recoveries/${record.id}`);
}

/** delete-household (RUNBOOK §4): deleteHousehold without the device proof, after the operator verified the request. */
async function deleteHousehold(values: Values, apply: boolean, io: OperatorIo): Promise<void> {
  const hid = stringOption(values, 'hid', ID)!;
  if (apply) requireConfirmation(values, hid);
  const { householdFootprint, deleteHouseholdData } = await import('../src/handlers/household.js');
  const footprint = await householdFootprint(hid);
  if (!footprint.exists) throw new OperatorError(`No household ${hid}`);
  const { counts } = footprint;
  io.out(`delete-household ${hid}${apply ? '' : ' (dry run)'}:`);
  io.out(
    `  ${counts.members} member(s), ${counts.devices} device(s), ${counts.events} event(s), ` +
      `${counts.documents} document(s); ${footprint.uids.length} Auth user(s) to delete`,
  );
  if (!apply) {
    dryRunNote(io, `--apply --confirm ${hid}`);
    return;
  }
  const outcome = await deleteHouseholdData(hid);
  io.out(
    `Done: deletionRecords row written (hidHash ${outcome.hidHash}); ${outcome.usersDeleted} user(s) deleted, ` +
      `${outcome.failedUids.length} failure(s)`,
  );
  if (outcome.cleanupFailures > 0) {
    io.out(
      `${outcome.cleanupFailures} link code, challenge or userHouseholds row(s) were not deleted: ` +
        'prune-expired removes expired link codes and challenges; check userHouseholds in the console (RUNBOOK §4)',
    );
  }
  if (outcome.failedUids.length > 0) {
    // The household's rows are gone, so a second run cannot find these users (review M2): delete them by hand.
    io.out(`These Auth users were not deleted: ${outcome.failedUids.join(', ')}`);
    io.out(
      'Delete each in the Firebase console → Authentication → Users (search the UID, ⋮ → Delete account); ' +
        'there is no firebase CLI command for it. Then note it on the deletion request (RUNBOOK §4).',
    );
  }
}

/** export-household: the export bundle without the callable's size limits, to a new local file (mode 0600). */
async function exportHousehold(values: Values, io: OperatorIo): Promise<void> {
  const hid = stringOption(values, 'hid', ID)!;
  const out = values.out;
  if (typeof out !== 'string' || out === '') throw new OperatorError('--out is required');
  const path = resolve(out);
  const { db } = await import('../src/firebase.js');
  const { householdExport, UNLIMITED_EXPORT } = await import('../src/handlers/household.js');
  if (!(await db.doc(`households/${hid}`).get()).exists) throw new OperatorError(`No household ${hid}`);
  const { bundle, stats } = await householdExport(hid, UNLIMITED_EXPORT);
  try {
    await writeFile(path, `${JSON.stringify(bundle, null, 2)}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  } catch (error) {
    if ((error as { code?: unknown }).code === 'EEXIST') throw new OperatorError(`${path} exists: choose a new file`);
    throw error;
  }
  io.out(
    `Exported household ${hid} to ${path}: ${stats.events} event(s), ${stats.documents} document(s), ` +
      `${stats.messages} message(s), ${stats.blobs} blob chunk header(s), ${stats.bytes} bytes`,
  );
  io.out('The file holds personal data: hand it over securely and delete it afterwards (RUNBOOK §4).');
}

/** One prune rule: rows of [collection] whose [field] is before the cut-off. */
interface PruneRule {
  readonly collection: string;
  readonly field: string;
  readonly before: Date;
  readonly why: string;
}

/** The rules of prune-expired at [now]. */
export function pruneRules(now: Date): PruneRule[] {
  const retention = new Date(now);
  retention.setUTCMonth(retention.getUTCMonth() - RECORD_RETENTION_MONTHS);
  return [
    { collection: 'deletionRecords', field: 'at', before: retention, why: `older than ${RECORD_RETENTION_MONTHS} months` },
    { collection: 'recoveries', field: 'at', before: retention, why: `older than ${RECORD_RETENTION_MONTHS} months` },
    { collection: 'rateLimits', field: 'expiresAt', before: now, why: 'expired (stale bucket)' },
    { collection: 'linkCodes', field: 'expiresAt', before: now, why: 'expired' },
    { collection: 'challenges', field: 'expiresAt', before: now, why: 'expired' },
  ];
}

/** Deletes in pages of this many. */
const PRUNE_PAGE = 400;

/** prune-expired: the TTL policies' fallback (FIREBASE.md checklist) and the 12-month record retention. */
async function pruneExpired(apply: boolean, io: OperatorIo): Promise<void> {
  const { Timestamp } = await import('firebase-admin/firestore');
  const { db } = await import('../src/firebase.js');
  const { bulkDelete } = await import('../src/bulkDelete.js');
  io.out(`prune-expired${apply ? '' : ' (dry run)'}:`);
  for (const rule of pruneRules(io.now ?? new Date())) {
    const query = db.collection(rule.collection).where(rule.field, '<', Timestamp.fromDate(rule.before));
    if (!apply) {
      const count = (await query.count().get()).data().count;
      io.out(`  ${rule.collection}: ${count} ${rule.why}`);
      continue;
    }
    let deleted = 0;
    let failures = 0;
    for (;;) {
      const page = await query.limit(PRUNE_PAGE).get();
      if (page.empty) break;
      const failed = await bulkDelete(page.docs.map((d) => d.ref));
      deleted += page.size - failed;
      failures += failed;
      // A page that deleted nothing would be read again unchanged: stop and report.
      if (page.size < PRUNE_PAGE || failed === page.size) break;
    }
    io.out(`  ${rule.collection}: deleted ${deleted} ${rule.why}${failures > 0 ? `, ${failures} failed` : ''}`);
  }
  if (!apply) dryRunNote(io, '--apply');
}

async function main(): Promise<void> {
  try {
    process.exitCode = await runOperator(process.argv.slice(2), { out: (line) => process.stdout.write(`${line}\n`) });
  } catch (error) {
    if (error instanceof OperatorError) {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 2;
    } else {
      // A library error: its name and code only (a message could quote stored data).
      const code: unknown = (error as { code?: unknown } | null)?.code;
      const name = error instanceof Error ? error.name : typeof error;
      const suffix = typeof code === 'string' || typeof code === 'number' ? ` ${code}` : '';
      process.stderr.write(`operator: failed (${name}${suffix})\n`);
      process.exitCode = 1;
    }
  }
  // The Admin SDK keeps sockets open; the work is done.
  process.exit(process.exitCode);
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  void main();
}
