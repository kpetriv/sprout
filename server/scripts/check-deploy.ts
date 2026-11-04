/**
 * `npm run check:deploy` (D-M8-13): run by Vercel's build (vercel.json `buildCommand`). Unless the build is a Vercel
 * **preview** or **development** one (`VERCEL_ENV`), it refuses static pages that are not the owner's: unfilled `{{…}}`
 * or "(to be filled)" values, example-domain contacts (what the repository commits, rendered from
 * docs/privacy.properties.example), a missing or expired security.txt, or the two security.txt copies differing. A
 * build without `VERCEL_ENV` (system variables not exposed, `vercel build` without the environment, a local run) is
 * checked like production (audit L5). It warns when security.txt expires within [EXPIRY_WARNING_DAYS] days. The fix is
 * always: fill docs/privacy.properties, render the privacy page (tools/privacy) and `npm run build:static`, then
 * deploy from that working tree (docs/RUNBOOK.md §8).
 */
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PUBLIC_DIR, SECURITY_TXT_FILES } from './build-static.js';

/** The rendered pages (tools/privacy): the privacy policy and the security and disclosure policy. */
const PAGES = ['privacy/index.html', 'security/index.html'] as const;

/** RFC 2606 / 6761 example domains, as the example properties use them. */
const EXAMPLE_DOMAIN = /[@./]example\.(com|org|net)\b|(@|\/\/)[\w.-]+\.(example|invalid|test)\b/i;

/** What stands between [publicDir] and a production deploy at [now]; empty when nothing does. */
export async function deployProblems(publicDir = PUBLIC_DIR, now = new Date()): Promise<string[]> {
  const problems: string[] = [];
  const read = (file: string) => readFile(join(publicDir, file), 'utf8').catch(() => undefined);

  for (const page of PAGES) {
    const html = await read(page);
    if (html === undefined) problems.push(`public/${page} is missing`);
    else {
      if (html.includes('{{')) problems.push(`public/${page} has unfilled {{…}} placeholders`);
      if (html.includes('(to be filled)')) problems.push(`public/${page} has "(to be filled)" values`);
      if (EXAMPLE_DOMAIN.test(html)) problems.push(`public/${page} names an example domain`);
    }
  }

  const [wellKnown, root] = await Promise.all(SECURITY_TXT_FILES.map(read));
  if (wellKnown === undefined || root === undefined) {
    problems.push('public/.well-known/security.txt or public/security.txt is missing');
  } else {
    if (wellKnown !== root) problems.push('public/.well-known/security.txt and public/security.txt differ');
    const contact = /^Contact: (.+)$/m.exec(wellKnown)?.[1];
    if (contact === undefined) problems.push('security.txt has no Contact');
    else if (EXAMPLE_DOMAIN.test(contact)) problems.push('security.txt has an example-domain Contact');
    const canonicals = [...wellKnown.matchAll(/^Canonical: (.+)$/gm)].map((m) => m[1]);
    if (canonicals.some((canonical) => EXAMPLE_DOMAIN.test(canonical))) {
      problems.push('security.txt has an example-domain Canonical');
    }
    const expires = Date.parse(/^Expires: (.+)$/m.exec(wellKnown)?.[1] ?? '');
    if (Number.isNaN(expires)) problems.push('security.txt has no valid Expires');
    else if (expires <= now.getTime()) problems.push('security.txt has expired: set EFFECTIVE_DATE or SECURITY_TXT_EXPIRES');
  }
  return problems;
}

/** True unless this is a Vercel preview or development build (and [force] is not set). */
export function shouldCheck(env: NodeJS.ProcessEnv, force = false): boolean {
  return force || !(env.VERCEL_ENV === 'preview' || env.VERCEL_ENV === 'development');
}

/** security.txt expiring within this many days gets a warning (RFC 9116 recommends less than a year). */
export const EXPIRY_WARNING_DAYS = 60;

/** A warning when the security.txt in [publicDir] expires within [EXPIRY_WARNING_DAYS] days of [now], else undefined. */
export async function expiryWarning(publicDir = PUBLIC_DIR, now = new Date()): Promise<string | undefined> {
  const text = await readFile(join(publicDir, SECURITY_TXT_FILES[0]), 'utf8').catch(() => undefined);
  const expires = Date.parse(/^Expires: (.+)$/m.exec(text ?? '')?.[1] ?? '');
  if (Number.isNaN(expires) || expires <= now.getTime()) return undefined;
  const days = Math.floor((expires - now.getTime()) / (24 * 60 * 60 * 1000));
  if (days >= EXPIRY_WARNING_DAYS) return undefined;
  return `security.txt expires in ${days} day(s): set a new EFFECTIVE_DATE or SECURITY_TXT_EXPIRES and redeploy (RUNBOOK §8)`;
}

async function main(argv: string[]): Promise<number> {
  if (!shouldCheck(process.env, argv.includes('--force'))) {
    process.stdout.write(`check:deploy: a ${process.env.VERCEL_ENV} build, static pages not checked\n`);
    return 0;
  }
  const problems = await deployProblems();
  const warning = await expiryWarning();
  if (warning !== undefined) process.stderr.write(`check:deploy: warning: ${warning}\n`);
  if (problems.length === 0) {
    process.stdout.write('check:deploy: static pages are ready for production\n');
    return 0;
  }
  process.stderr.write(
    `check:deploy: refusing a production deploy:\n${problems.map((p) => `  - ${p}\n`).join('')}` +
      'Fill docs/privacy.properties, render the privacy page and run `npm run build:static`, then deploy.\n',
  );
  return 1;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (error: unknown) => {
      process.stderr.write(`check:deploy: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exit(2);
    },
  );
}
