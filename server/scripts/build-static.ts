/**
 * `npm run build:static` (M8 §1, D-M8-13): writes `public/.well-known/security.txt` and `public/security.txt` (RFC 9116)
 * from the owner's values in `docs/privacy.properties` (gitignored), or `docs/privacy.properties.example` when that
 * file does not exist. The disclosure policy itself is docs/SECURITY.md; the privacy page is rendered by
 * tools/privacy (slice C), not here.
 *
 *   npm run build:static              write both files from privacy.properties (else the example)
 *   npm run build:static -- --example write them from the example (what the repository commits)
 *   npm run check:static              fail when the committed files differ from the example's rendering (CI)
 *
 * Keys read: SECURITY_CONTACT (else CONTACT_EMAIL), CANONICAL_HOST (else the host of PRIVACY_URL), EFFECTIVE_DATE
 * (YYYY-MM-DD), DEVELOPER_NAME, and optionally SECURITY_TXT_EXPIRES (YYYY-MM-DD; default EFFECTIVE_DATE plus one
 * year) and SECURITY_POLICY_URL (default `https://<host>/security`, the rendered docs/SECURITY.md).
 */
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

export const DOCS_DIR = fileURLToPath(new URL('../../docs/', import.meta.url));
export const PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));
export const SECURITY_TXT_FILES = ['.well-known/security.txt', 'security.txt'] as const;

/** `KEY=value` lines (`#` and `!` comments, blank lines ignored; no continuations or escapes), as Java reads them. */
export function parseProperties(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '' || line.startsWith('#') || line.startsWith('!')) continue;
    const match = /^([A-Za-z0-9_.-]+)\s*[=:]\s*(.*)$/.exec(line);
    if (!match) throw new Error(`Not a KEY=value line in the properties file: ${line.slice(0, 40)}`);
    out[match[1]] = match[2].trim();
  }
  return out;
}

/** The properties file build:static reads: privacy.properties, else the example ([example] forces the example). */
export function propertiesFile(docsDir = DOCS_DIR, example = false): string {
  const own = join(docsDir, 'privacy.properties');
  const fallback = join(docsDir, 'privacy.properties.example');
  if (!example && existsSync(own)) return own;
  if (existsSync(fallback)) return fallback;
  throw new Error(`Neither ${own} nor ${fallback} exists`);
}

export function loadProperties(file: string): Record<string, string> {
  return parseProperties(readFileSync(file, 'utf8'));
}

function required(props: Record<string, string>, key: string): string {
  const value = props[key];
  if (value === undefined || value === '') throw new Error(`${key} is missing from the properties file`);
  if (/\p{Cc}/u.test(value)) throw new Error(`${key} contains a control character`);
  return value;
}

function date(value: string, key: string): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const parsed = match ? new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))) : undefined;
  if (parsed === undefined || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`${key} must be a date YYYY-MM-DD`);
  }
  return parsed;
}

/** `mailto:` for an address, an `https:` URL as is; anything else is refused. */
function contactUri(value: string): string {
  if (/^https:\/\/[^\s]+$/.test(value)) return value;
  if (/\p{Cc}/u.test(value)) throw new Error('SECURITY_CONTACT contains a control character');
  const address = value.replace(/^mailto:/, '');
  if (/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(address)) return `mailto:${address}`;
  throw new Error('SECURITY_CONTACT must be an e-mail address or an https: URL');
}

/** A bare host name (an `https://` prefix and a trailing slash are dropped). */
function host(value: string): string {
  const bare = value.replace(/^https:\/\//, '').replace(/\/$/, '');
  if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/i.test(bare)) {
    throw new Error('CANONICAL_HOST must be a host name such as sprout.example.com');
  }
  return bare.toLowerCase();
}

/** CANONICAL_HOST, else the host of PRIVACY_URL. */
function canonicalHost(props: Record<string, string>): string {
  if (props.CANONICAL_HOST) return host(props.CANONICAL_HOST);
  const privacyUrl = required(props, 'PRIVACY_URL');
  const match = /^https:\/\/([^/\s]+)(\/[^\s]*)?$/.exec(privacyUrl);
  if (!match) throw new Error('PRIVACY_URL must be an https: URL');
  return host(match[1]);
}

/** The security.txt of [props] (RFC 9116 §2.5: Contact and Expires required; Canonical, Policy, Preferred-Languages). */
export function renderSecurityTxt(props: Record<string, string>): string {
  const contact = contactUri(props.SECURITY_CONTACT || required(props, 'CONTACT_EMAIL'));
  const canonical = canonicalHost(props);
  let expires: Date;
  if (props.SECURITY_TXT_EXPIRES) {
    expires = date(props.SECURITY_TXT_EXPIRES, 'SECURITY_TXT_EXPIRES');
  } else {
    expires = date(required(props, 'EFFECTIVE_DATE'), 'EFFECTIVE_DATE');
    expires.setUTCFullYear(expires.getUTCFullYear() + 1);
  }
  const developer = required(props, 'DEVELOPER_NAME');
  const policy = props.SECURITY_POLICY_URL || `https://${canonical}/security`;
  if (!/^https:\/\/[^\s]+$/.test(policy)) throw new Error('SECURITY_POLICY_URL must be an https: URL');
  return [
    `# Security contact for Sprout (${developer}), RFC 9116.`,
    '# Coordinated disclosure: report privately, 90 days before publication, no bug bounty (docs/SECURITY.md).',
    '# Generated by `npm run build:static` in server/ from docs/privacy.properties; do not edit by hand.',
    `Contact: ${contact}`,
    `Expires: ${expires.toISOString().replace(/\.\d{3}Z$/, 'Z')}`,
    // Both URIs the file is served at (RFC 9116 §2.5.2: a copy at a URI not listed as Canonical should not be trusted).
    `Canonical: https://${canonical}/.well-known/security.txt`,
    `Canonical: https://${canonical}/security.txt`,
    `Policy: ${policy}`,
    'Preferred-Languages: en',
    '',
  ].join('\n');
}

/** Writes the security.txt files of [props] under [publicDir]. */
export async function writeSecurityTxt(props: Record<string, string>, publicDir = PUBLIC_DIR): Promise<string[]> {
  const text = renderSecurityTxt(props);
  const written: string[] = [];
  for (const file of SECURITY_TXT_FILES) {
    const path = join(publicDir, file);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, text, 'utf8');
    written.push(path);
  }
  return written;
}

/** The files under [publicDir] that differ from the rendering of [props] (empty: up to date). */
export async function staleSecurityTxt(props: Record<string, string>, publicDir = PUBLIC_DIR): Promise<string[]> {
  const text = renderSecurityTxt(props);
  const stale: string[] = [];
  for (const file of SECURITY_TXT_FILES) {
    const current = await readFile(join(publicDir, file), 'utf8').catch(() => undefined);
    if (current !== text) stale.push(file);
  }
  return stale;
}

async function main(argv: string[]): Promise<number> {
  const { values } = parseArgs({
    args: argv,
    options: { check: { type: 'boolean', default: false }, example: { type: 'boolean', default: false } },
    strict: true,
  });
  const file = propertiesFile(DOCS_DIR, values.example || values.check);
  const props = loadProperties(file);
  if (values.check) {
    const stale = await staleSecurityTxt(props);
    if (stale.length > 0) {
      process.stderr.write(
        `public/${stale.join(', public/')} differ from ${file}: run \`npm run build:static -- --example\` and commit\n`,
      );
      return 1;
    }
    process.stdout.write('security.txt is up to date with the example properties\n');
    return 0;
  }
  const written = await writeSecurityTxt(props);
  process.stdout.write(`Wrote ${written.length} files from ${file}\n`);
  return 0;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (error: unknown) => {
      process.stderr.write(`build:static: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exit(2);
    },
  );
}
