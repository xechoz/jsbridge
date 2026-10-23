import { build } from 'esbuild';
import { cpSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(here, '..');
const assetsRoot = resolve(webRoot, '../demo/src/main/assets/web');

mkdirSync(assetsRoot, { recursive: true });

await build({
  entryPoints: [join(webRoot, 'src/demo/main.ts')],
  bundle: true,
  format: 'iife',
  target: 'es2020',
  sourcemap: 'inline',
  outfile: join(assetsRoot, 'demo.js'),
  logLevel: 'info',
});

cpSync(join(webRoot, 'demo/index.html'), join(assetsRoot, 'index.html'));

console.log(`demo assets written to ${assetsRoot}`);
