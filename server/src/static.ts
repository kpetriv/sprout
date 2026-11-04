/**
 * The static pages under `server/public` (M8 §1, D-M8-1, D-M8-13): the privacy policy (`/privacy`, rendered from
 * docs/PRIVACY.md by tools/privacy), the security and disclosure policy (`/security`, rendered from docs/SECURITY.md
 * by the same tool), `security.txt` (`/.well-known/security.txt` and `/security.txt`, RFC 9116,
 * written by `npm run build:static`) and `robots.txt`.
 *
 * In production Vercel's CDN serves them straight from `public/`: vercel.json keeps these paths out of the rewrite to
 * the function and sets the headers below on them (test/static.test.ts checks vercel.json against this file). The
 * Hono app serves the same files with the same headers for `npm run dev` and the tests, so one definition of the
 * routes and headers is tested end to end.
 */
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Hono } from 'hono';

/**
 * Headers of every static response: cacheable for an hour (the pages change only with a deploy), no scripts, frames,
 * forms or external resources (the privacy page uses an inline stylesheet and at most `data:` images), no MIME
 * sniffing and no referrer.
 */
export const STATIC_HEADERS: Readonly<Record<string, string>> = {
  'Cache-Control': 'public, max-age=3600',
  'Content-Security-Policy':
    "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
};

export const HTML = 'text/html; charset=utf-8';
export const TEXT = 'text/plain; charset=utf-8';

/** One served file: the request paths, the file under `public/` and its content type. */
export interface StaticRoute {
  readonly paths: readonly string[];
  readonly file: string;
  readonly contentType: string;
}

export const STATIC_ROUTES: readonly StaticRoute[] = [
  { paths: ['/privacy', '/privacy/', '/privacy/index.html'], file: 'privacy/index.html', contentType: HTML },
  { paths: ['/security', '/security/', '/security/index.html'], file: 'security/index.html', contentType: HTML },
  { paths: ['/.well-known/security.txt'], file: '.well-known/security.txt', contentType: TEXT },
  { paths: ['/security.txt'], file: 'security.txt', contentType: TEXT },
  { paths: ['/robots.txt'], file: 'robots.txt', contentType: TEXT },
];

/** `server/public`, from src/ (tsx, vitest) and dist/ alike. */
export const DEFAULT_PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));

/**
 * Serves [STATIC_ROUTES] from [publicDir] on [app] (GET; Hono answers HEAD from the GET route). A file that is missing
 * answers the app's 404. The file is read per request: these are rare requests and the files are small.
 */
export function serveStatic(app: Hono, publicDir: string = DEFAULT_PUBLIC_DIR): void {
  for (const route of STATIC_ROUTES) {
    for (const path of route.paths) {
      app.get(path, async (c) => {
        let body: Buffer;
        try {
          body = await readFile(join(publicDir, route.file));
        } catch {
          return c.notFound();
        }
        return c.body(new Uint8Array(body), 200, { ...STATIC_HEADERS, 'Content-Type': route.contentType });
      });
    }
  }
}
