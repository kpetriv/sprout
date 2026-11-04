import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadProperties, propertiesFile, renderSecurityTxt, staleSecurityTxt, writeSecurityTxt } from '../scripts/build-static.js';
import { deployProblems } from '../scripts/check-deploy.js';
import { createApp } from '../src/index.js';
import { DEFAULT_PUBLIC_DIR, HTML, STATIC_HEADERS, STATIC_ROUTES, TEXT } from '../src/static.js';
import { app, testAppCheck } from './helpers.js';

interface VercelConfig {
  outputDirectory: string;
  buildCommand: string;
  rewrites: { source: string; destination: string }[];
  headers: { source: string; headers: { key: string; value: string }[] }[];
}

const vercel = JSON.parse(await readFile(new URL('../vercel.json', import.meta.url), 'utf8')) as VercelConfig;

const STATIC_PATHS = [
  '/privacy',
  '/privacy/',
  '/security',
  '/security/',
  '/.well-known/security.txt',
  '/security.txt',
  '/robots.txt',
];

describe('static pages served by the app', () => {
  for (const route of STATIC_ROUTES) {
    for (const path of route.paths) {
      it(`GET ${path} serves public/${route.file} with the static headers`, async () => {
        const response = await app.request(path);
        expect(response.status).toBe(200);
        expect(response.headers.get('content-type')).toBe(route.contentType);
        for (const [name, value] of Object.entries(STATIC_HEADERS)) expect(response.headers.get(name)).toBe(value);
        const body = await response.text();
        expect(body).toBe(await readFile(join(DEFAULT_PUBLIC_DIR, route.file), 'utf8'));
      });
    }
  }

  it('the privacy and security pages are HTML without scripts; security.txt is plain text', async () => {
    for (const path of ['/privacy', '/security']) {
      const response = await app.request(path);
      expect(response.headers.get('content-type')).toBe(HTML);
      const html = await response.text();
      expect(html).toMatch(/^<!doctype html>/i);
      expect(html).not.toMatch(/<script|<link[^>]+stylesheet|<iframe|<form/i);
    }
    const txt = await (await app.request('/.well-known/security.txt')).text();
    expect(txt).toMatch(/^Contact: (mailto:|https:)/m);
    expect(txt).toMatch(/^Expires: \d{4}-\d{2}-\d{2}T00:00:00Z$/m);
    expect(await (await app.request('/security.txt')).text()).toBe(txt);
  });

  it('answers HEAD like GET, without a body', async () => {
    const response = await app.request('/privacy', { method: 'HEAD' });
    expect(response.status).toBe(200);
    expect(response.headers.get('content-security-policy')).toBe(STATIC_HEADERS['Content-Security-Policy']);
    expect(await response.text()).toBe('');
  });

  it('a missing file is the protocol 404, and other paths are untouched', async () => {
    const empty = await mkdtemp(join(tmpdir(), 'sprout-public-'));
    try {
      const bare = createApp({ verifyAppCheck: testAppCheck, publicDir: empty });
      const missing = await bare.request('/privacy');
      expect(missing.status).toBe(404);
      expect(await missing.json()).toEqual({ error: { status: 'NOT_FOUND', message: 'Not found' } });
    } finally {
      await rm(empty, { recursive: true });
    }
    expect((await app.request('/privacy/../package.json')).status).toBe(404);
    expect((await app.request('/privacyx')).status).toBe(404);
    // Static routes are GET only; a callable still answers its protocol.
    expect((await app.request('/privacy', { method: 'POST' })).status).toBe(404);
    expect((await app.request('/createHousehold')).status).toBe(400);
  });
});

describe('vercel.json', () => {
  /** vercel.json's rewrite sources here are plain regular expressions over the path. */
  const rewriteOf = (path: string) =>
    vercel.rewrites.find((r) => new RegExp(`^${r.source}$`).test(path))?.destination ?? null;

  it('serves public/ statically and rewrites everything else to the function', () => {
    expect(vercel.outputDirectory).toBe('public');
    for (const path of ['/', '/createHousehold', '/api/createHousehold', '/deviceStatus', '/privacyx', '/securityx', '/x/privacy']) {
      expect(rewriteOf(path), path).toBe('/api');
    }
    for (const path of ['/.well-known/security.txt', '/security.txt', '/robots.txt', '/privacy/index.html', '/security/index.html']) {
      expect(rewriteOf(path), path).toBeNull();
    }
    expect(rewriteOf('/privacy')).toBe('/privacy/index.html');
    expect(rewriteOf('/privacy/')).toBe('/privacy/index.html');
    expect(rewriteOf('/security')).toBe('/security/index.html');
    expect(rewriteOf('/security/')).toBe('/security/index.html');
  });

  it('sets the static headers and content type of src/static.ts on every static path', () => {
    const headerSource = (path: string) => vercel.headers.filter((h) => new RegExp(`^${h.source}$`).test(path));
    for (const path of [...STATIC_PATHS, '/privacy/index.html', '/security/index.html']) {
      const rules = headerSource(path);
      expect(rules, path).toHaveLength(1);
      const headers = Object.fromEntries(rules[0].headers.map((h) => [h.key, h.value]));
      const route = STATIC_ROUTES.find((r) => r.paths.includes(path))!;
      expect(headers, path).toEqual({ ...STATIC_HEADERS, 'Content-Type': route.contentType });
    }
    expect(STATIC_ROUTES.filter((r) => r.contentType === TEXT).map((r) => r.file)).toEqual([
      '.well-known/security.txt',
      'security.txt',
      'robots.txt',
    ]);
  });

  it('every static route is served by vercel.json and the app alike', () => {
    expect(STATIC_ROUTES.flatMap((r) => r.paths).filter((p) => !p.endsWith('index.html')).sort()).toEqual([...STATIC_PATHS].sort());
  });

  it('runs the production deploy check after the build', () => {
    expect(vercel.buildCommand).toBe('npm run build && npm run check:deploy');
  });
});

describe('build:static (security.txt)', () => {
  const props = {
    CONTACT_EMAIL: 'privacy@sprout.dev',
    SECURITY_CONTACT: 'security@sprout.dev',
    DEVELOPER_NAME: 'Jane Developer',
    EFFECTIVE_DATE: '2026-11-04',
    PRIVACY_URL: 'https://sprout.dev/privacy',
  };

  it('renders RFC 9116 fields from the properties', () => {
    expect(renderSecurityTxt(props)).toBe(
      [
        '# Security contact for Sprout (Jane Developer), RFC 9116.',
        '# Coordinated disclosure: report privately, 90 days before publication, no bug bounty (docs/SECURITY.md).',
        '# Generated by `npm run build:static` in server/ from docs/privacy.properties; do not edit by hand.',
        'Contact: mailto:security@sprout.dev',
        'Expires: 2027-11-04T00:00:00Z',
        'Canonical: https://sprout.dev/.well-known/security.txt',
        'Policy: https://sprout.dev/security',
        'Preferred-Languages: en',
        '',
      ].join('\n'),
    );
  });

  it('takes CANONICAL_HOST, SECURITY_TXT_EXPIRES, SECURITY_POLICY_URL and an https contact when given', () => {
    const txt = renderSecurityTxt({
      ...props,
      SECURITY_CONTACT: 'https://sprout.dev/report',
      CANONICAL_HOST: 'https://Policy.Sprout.dev/',
      SECURITY_TXT_EXPIRES: '2027-06-30',
      SECURITY_POLICY_URL: 'https://sprout.dev/disclosure',
    });
    expect(txt).toContain('Contact: https://sprout.dev/report\n');
    expect(txt).toContain('Canonical: https://policy.sprout.dev/.well-known/security.txt\n');
    expect(txt).toContain('Expires: 2027-06-30T00:00:00Z\n');
    expect(txt).toContain('Policy: https://sprout.dev/disclosure\n');
    // Without SECURITY_CONTACT the privacy contact is used.
    expect(renderSecurityTxt({ ...props, SECURITY_CONTACT: '' })).toContain('Contact: mailto:privacy@sprout.dev\n');
  });

  it('refuses missing or malformed values', () => {
    expect(() => renderSecurityTxt({ ...props, DEVELOPER_NAME: '' })).toThrow(/DEVELOPER_NAME is missing/);
    expect(() => renderSecurityTxt({ ...props, EFFECTIVE_DATE: '2026-02-30' })).toThrow(/EFFECTIVE_DATE/);
    expect(() => renderSecurityTxt({ ...props, SECURITY_CONTACT: 'not an address' })).toThrow(/SECURITY_CONTACT/);
    expect(() => renderSecurityTxt({ ...props, PRIVACY_URL: 'http://sprout.dev/privacy' })).toThrow(/PRIVACY_URL/);
    expect(() => renderSecurityTxt({ ...props, CANONICAL_HOST: 'sprout dev' })).toThrow(/CANONICAL_HOST/);
    expect(() => renderSecurityTxt({ ...props, DEVELOPER_NAME: 'a\nContact: mailto:x@y.z' })).toThrow(/control/);
  });

  it('the committed files are the rendering of docs/privacy.properties.example', async () => {
    const example = loadProperties(propertiesFile(undefined, true));
    expect(await staleSecurityTxt(example)).toEqual([]);
  });

  it('writes both copies, and reports stale ones', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'sprout-public-'));
    try {
      expect(await staleSecurityTxt(props, dir)).toEqual(['.well-known/security.txt', 'security.txt']);
      await writeSecurityTxt(props, dir);
      expect(await staleSecurityTxt(props, dir)).toEqual([]);
      expect(await readFile(join(dir, '.well-known/security.txt'), 'utf8')).toBe(renderSecurityTxt(props));
    } finally {
      await rm(dir, { recursive: true });
    }
  });
});

describe('check:deploy', () => {
  let dir: string;
  const page = (title: string) =>
    `<!doctype html><html><head><style>body{margin:0}</style><title>${title}</title></head><body>Contact privacy@sprout.dev</body></html>`;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), 'sprout-public-'));
    await mkdir(join(dir, 'privacy'));
    await mkdir(join(dir, 'security'));
    await writeFile(join(dir, 'privacy/index.html'), page('Privacy'));
    await writeFile(join(dir, 'security/index.html'), page('Security'));
    await writeSecurityTxt(
      {
        SECURITY_CONTACT: 'security@sprout.dev',
        DEVELOPER_NAME: 'Jane Developer',
        EFFECTIVE_DATE: '2026-11-04',
        PRIVACY_URL: 'https://sprout.dev/privacy',
      },
      dir,
    );
  });
  afterEach(async () => {
    await rm(dir, { recursive: true });
  });

  it('passes the owner-rendered pages', async () => {
    expect(await deployProblems(dir, new Date('2026-11-05T00:00:00Z'))).toEqual([]);
  });

  it('refuses the committed placeholders and example rendering', async () => {
    expect(await deployProblems(DEFAULT_PUBLIC_DIR, new Date('2026-11-05T00:00:00Z'))).toEqual(
      expect.arrayContaining(['security.txt has an example-domain Contact', 'security.txt has an example-domain Canonical']),
    );
    const problems = await deployProblems(DEFAULT_PUBLIC_DIR, new Date('2026-11-05T00:00:00Z'));
    expect(problems.some((p) => p.startsWith('public/privacy/index.html'))).toBe(true);
    expect(problems.some((p) => p.startsWith('public/security/index.html'))).toBe(true);
  });

  it('refuses placeholders, example domains, unfilled values, a missing page and an expired or split security.txt', async () => {
    await writeFile(join(dir, 'privacy/index.html'), '<meta name="sprout-placeholder" content="true">{{CONTACT_EMAIL}}');
    await writeFile(join(dir, 'security/index.html'), page('x').replace('privacy@sprout.dev', 'a@example.invalid (to be filled)'));
    await writeFile(join(dir, 'security.txt'), 'Contact: mailto:x@sprout.dev\n');
    expect(await deployProblems(dir, new Date('2027-11-05T00:00:00Z'))).toEqual([
      'public/privacy/index.html is the committed placeholder',
      'public/privacy/index.html has unfilled {{…}} placeholders',
      'public/security/index.html has "(to be filled)" values',
      'public/security/index.html names an example domain',
      'public/.well-known/security.txt and public/security.txt differ',
      'security.txt has expired: set EFFECTIVE_DATE or SECURITY_TXT_EXPIRES',
    ]);
    await rm(join(dir, 'privacy/index.html'));
    expect(await deployProblems(dir, new Date('2026-11-05T00:00:00Z'))).toContain('public/privacy/index.html is missing');
  });
});
