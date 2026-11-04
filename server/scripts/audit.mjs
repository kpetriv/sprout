/**
 * `npm run audit` in server/ and firestore/ (SC-4, D-M8-10, D-M8-15): `npm audit` at high severity, with an
 * audit-ci-style exceptions file. Fails (exit 1) when an advisory of severity high or critical affects the installed
 * tree and is not excepted; moderate and low advisories are reported by `npm audit` but do not fail.
 *
 * Exceptions live in `audit-exceptions.json` next to package.json (docs/FIREBASE.md "Dependency audit"):
 *
 *   { "exceptions": [ { "advisory": "GHSA-xxxx-xxxx-xxxx", "package": "name", "reason": "why it does not apply",
 *                       "expires": "YYYY-MM-DD" } ] }
 *
 * Every entry needs all four fields and expires at most 90 days ahead; an expired entry stops excepting (and fails), an entry nothing matches is reported
 * so it can be removed. Plain JavaScript so firestore/ runs it without a TypeScript runner: `node ../server/scripts/audit.mjs`.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const FAILING = new Set(['high', 'critical']);
/** An exception is reviewed again at least this often. */
const MAX_EXCEPTION_DAYS = 90;
const GHSA = /^GHSA(-[23456789cfghjmpqrvwx]{4}){3}$/;

/** The exceptions of [file] (none when it does not exist), validated. */
export function loadExceptions(file, today = new Date().toISOString().slice(0, 10)) {
  if (!existsSync(file)) return [];
  const latest = new Date(Date.parse(today) + MAX_EXCEPTION_DAYS * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const parsed = JSON.parse(readFileSync(file, 'utf8'));
  const list = parsed?.exceptions;
  if (!Array.isArray(list)) throw new Error(`${file}: "exceptions" must be an array`);
  return list.map((entry, i) => {
    const { advisory, package: pkg, reason, expires } = entry ?? {};
    if (typeof advisory !== 'string' || !GHSA.test(advisory)) throw new Error(`${file}[${i}]: advisory must be a GHSA id`);
    if (typeof pkg !== 'string' || pkg === '') throw new Error(`${file}[${i}]: package is required`);
    if (typeof reason !== 'string' || reason.trim().length < 10) throw new Error(`${file}[${i}]: reason is required`);
    if (typeof expires !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(expires) || Number.isNaN(Date.parse(expires))) {
      throw new Error(`${file}[${i}]: expires must be YYYY-MM-DD`);
    }
    if (expires > latest) throw new Error(`${file}[${i}]: expires is more than ${MAX_EXCEPTION_DAYS} days ahead`);
    return { advisory, package: pkg, reason, expires };
  });
}

/** The high and critical advisories of an `npm audit --json` (v2) report: `{ advisory, package, title, severity }`. */
export function failingAdvisories(report) {
  const found = new Map();
  for (const vulnerability of Object.values(report?.vulnerabilities ?? {})) {
    for (const via of vulnerability.via ?? []) {
      if (typeof via !== 'object' || via === null || !FAILING.has(via.severity)) continue;
      const advisory = /GHSA-[a-z0-9-]+/i.exec(String(via.url ?? ''))?.[0] ?? `npm-${String(via.source)}`;
      found.set(`${advisory}|${via.name}`, { advisory, package: via.name, title: via.title, severity: via.severity });
    }
  }
  return [...found.values()];
}

/** Splits [advisories] by [exceptions] at [today] (YYYY-MM-DD): `{ failing, excepted, expired, unused }`. */
export function evaluate(advisories, exceptions, today) {
  const failing = [];
  const excepted = [];
  const expired = [];
  const used = new Set();
  for (const advisory of advisories) {
    const match = exceptions.find((e) => e.advisory === advisory.advisory && e.package === advisory.package);
    if (match === undefined) failing.push(advisory);
    else if (match.expires < today) {
      used.add(match);
      expired.push({ ...advisory, expires: match.expires });
    } else {
      used.add(match);
      excepted.push({ ...advisory, expires: match.expires, reason: match.reason });
    }
  }
  return { failing, excepted, expired, unused: exceptions.filter((e) => !used.has(e)) };
}

function main() {
  const cwd = process.cwd();
  const npm = spawnSync('npm', ['audit', '--json'], { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  let report;
  try {
    report = JSON.parse(npm.stdout);
  } catch {
    process.stderr.write(`npm audit did not answer with JSON (exit ${npm.status}):\n${npm.stderr}\n`);
    return 2;
  }
  if (report.error) {
    process.stderr.write(`npm audit failed: ${report.error.summary ?? JSON.stringify(report.error)}\n`);
    return 2;
  }
  const today = new Date().toISOString().slice(0, 10);
  const exceptions = loadExceptions(join(cwd, 'audit-exceptions.json'), today);
  const { failing, excepted, expired, unused } = evaluate(failingAdvisories(report), exceptions, today);
  const counts = report.metadata?.vulnerabilities ?? {};
  process.stdout.write(
    `npm audit: ${counts.critical ?? 0} critical, ${counts.high ?? 0} high, ${counts.moderate ?? 0} moderate, ` +
      `${counts.low ?? 0} low (packages)\n`,
  );
  for (const a of excepted) {
    process.stdout.write(`  excepted until ${a.expires}: ${a.advisory} ${a.package} (${a.severity}): ${a.reason}\n`);
  }
  for (const e of unused) {
    process.stdout.write(`  unused exception (remove it): ${e.advisory} ${e.package}\n`);
  }
  for (const a of expired) {
    process.stderr.write(`  EXPIRED exception (${a.expires}): ${a.advisory} ${a.package} (${a.severity}) ${a.title}\n`);
  }
  for (const a of failing) {
    process.stderr.write(`  ${a.severity.toUpperCase()}: ${a.advisory} ${a.package}: ${a.title}\n`);
  }
  if (failing.length + expired.length > 0) {
    process.stderr.write(
      'Fix by upgrading (or an npm "overrides" pin), or add a reviewed entry to audit-exceptions.json ' +
        '(docs/FIREBASE.md "Dependency audit").\n',
    );
    return 1;
  }
  process.stdout.write('No unexcepted high or critical advisories.\n');
  return 0;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = main();
  } catch (error) {
    process.stderr.write(`audit: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 2;
  }
}
