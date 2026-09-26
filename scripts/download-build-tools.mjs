// Optional developer utility: Downloads Android Platform 35 SDK & Google R8 for rebuilding from source.
import fs from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const vendorDir = path.join(root, 'vendor');

const downloads = [
  { name: 'r8.jar', url: 'https://dl.google.com/android/maven2/com/android/tools/r8/8.13.23/r8-8.13.23.jar', dest: path.join(vendorDir, 'r8.jar') }
];

console.log('Downloading build dependencies for developers...');
for (const item of downloads) {
  console.log(`Downloading ${item.name} ...`);
  const res = await fetch(item.url);
  if (!res.ok) throw new Error(`Failed to download ${item.name}: ${res.status}`);
  await fs.writeFile(item.dest, Buffer.from(await res.arrayBuffer()));
  console.log(`Downloaded ${item.name}`);
}
console.log('Done! Pre-compiled build/dsh-vdisplay.jar is already ready for regular users.');
