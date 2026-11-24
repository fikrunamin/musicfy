#!/usr/bin/env node
// Size and self-hosting check for the built web client: `npm run build && npm run size`.
// Fails when the main JS chunk (the one dist/index.html loads) is over 45 kB gzip, when the CSS
// is over 10 kB gzip, or when anything in dist/ points at an external font or stylesheet (fonts
// and styles are self-hosted; the CSP has no CDN). kB = 1000 bytes, as Vite reports it.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const LIMITS = { js: 45_000, css: 10_000 };
// An other build folder may be given as the first argument (used to test this script).
const dist = process.argv[2] ? resolve(process.argv[2]) : fileURLToPath(new URL('../dist/', import.meta.url));

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

let html;
try {
  html = readFileSync(join(dist, 'index.html'), 'utf8');
} catch {
  console.error('size: dist/index.html is missing, run `npm run build` first');
  process.exit(1);
}

const failures = [];
const kB = (bytes) => `${(bytes / 1000).toFixed(2)} kB`;
const gzip = (path) => gzipSync(readFileSync(path)).length;

// The main chunk is the module script of index.html; hls.js and other lazy chunks do not count.
const mainJs = [...html.matchAll(/<script[^>]*\bsrc="\/?([^"]+\.js)"/g)].map((m) => m[1]);
const css = [...html.matchAll(/<link[^>]*rel="stylesheet"[^>]*href="\/?([^"]+\.css)"/g)].map((m) => m[1]);
if (mainJs.length === 0) failures.push('no main script found in dist/index.html');
if (css.length === 0) failures.push('no stylesheet found in dist/index.html');

for (const [kind, list] of [
  ['js', mainJs],
  ['css', css],
]) {
  const total = list.reduce((sum, file) => sum + gzip(join(dist, file)), 0);
  const ok = total <= LIMITS[kind];
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${kind === 'js' ? 'main JS' : 'CSS    '} ${kB(total)} gzip (limit ${kB(LIMITS[kind])}): ${list.join(', ')}`);
  if (!ok) failures.push(`${kind} is ${kB(total)} gzip, over ${kB(LIMITS[kind])}`);
}

// External fonts or stylesheets: an absolute (or protocol-relative) URL in an @import, a url()
// that names a font, a <link rel="stylesheet"> / preload, or a font CDN anywhere.
const EXTERNAL = [
  [/@import\s+(?:url\()?\s*['"]?(?:https?:)?\/\//gi, 'external @import'],
  [/url\(\s*['"]?(?:https?:)?\/\/[^)'"]*\.(?:woff2?|ttf|otf|eot)\b/gi, 'external font url()'],
  [/<link[^>]*href=["'](?:https?:)?\/\/[^"']*["'][^>]*>/gi, 'external <link>'],
  [/fonts\.(?:googleapis|gstatic)\.com|use\.typekit\.net|fonts\.bunny\.net/gi, 'font or style CDN'],
];
for (const path of files(dist).filter((p) => /\.(?:html|css|js)$/.test(p))) {
  const text = readFileSync(path, 'utf8');
  for (const [pattern, what] of EXTERNAL) {
    for (const match of text.matchAll(pattern)) {
      failures.push(`${what} in ${relative(dist, path)}: ${match[0].slice(0, 120)}`);
    }
  }
}

if (failures.length > 0) {
  for (const failure of failures) console.error(`size: ${failure}`);
  process.exit(1);
}
console.log('ok   no external font or stylesheet URL in dist/');
