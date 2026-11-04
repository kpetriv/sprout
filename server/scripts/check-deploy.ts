/**
 * `npm run check:deploy` (D-M8-13): run by Vercel's build (vercel.json `buildCommand`). For a **production** build
 * (`VERCEL_ENV=production`, or `--force`) it refuses static pages that are not the owner's: the committed privacy
 * placeholders, unfilled `{{…}}` or "(to be filled)" values, example-domain contacts (what the repository commits, rendered from
 * docs/privacy.properties.example), a missing or expired security.txt, or the two security.txt copies differing.
 * Preview and local builds pass without checking. The fix is always: fill docs/privacy.properties, render the privacy
 * page (tools/privacy) and `npm run build:static`, then deploy from that working tree (docs/RUNBOOK.md §8).
 */
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PUBLIC_DIR, SECURITY_TXT_FILES } from './build-static.js';

/** The marker of the committed placeholder page (server/public/privacy/index.html). */
export const PLACEHOLDER_MARKER = 'sprout-placeholder';

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
      if (html.includes(PLACEHOLDER_MARKER)) problems.push(`public/${page} is the committed placeholder`);
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
    const canonical = /^Canonical: (.+)$/m.exec(wellKnown)?.[1];
    if (canonical !== undefined && EXAMPLE_DOMAIN.test(canonical)) {
      problems.push('security.txt has an example-domain Canonical');
    }
    const expires = Date.parse(/^Expires: (.+)$/m.exec(wellKnown)?.[1] ?? '');
    if (Number.isNaN(expires)) problems.push('security.txt has no valid Expires');
    else if (expires <= now.getTime()) problems.push('security.txt has expired: set EFFECTIVE_DATE or SECURITY_TXT_EXPIRES');
  }
  return problems;
}

async function main(argv: string[]): Promise<number> {
  const force = argv.includes('--force');
  if (!force && process.env.VERCEL_ENV !== 'production') {
    process.stdout.write('check:deploy: not a production build, static pages not checked\n');
    return 0;
  }
  const problems = await deployProblems();
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
