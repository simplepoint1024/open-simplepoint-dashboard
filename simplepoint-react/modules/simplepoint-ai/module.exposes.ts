import * as fs from 'fs';
import * as path from 'path';

const viewsDir = path.join(__dirname, 'src', 'views');
const exposes: Record<string, string> = {};
const indexEntryFiles = ['index.tsx', 'index.ts', 'index.jsx', 'index.js'];

function findIndexFile(dir: string): string | null {
  for (const name of indexEntryFiles) {
    if (fs.existsSync(path.join(dir, name))) return name;
  }
  return null;
}

function scan(dir: string, parts: string[] = []) {
  if (parts.length > 0) {
    const indexFile = findIndexFile(dir);
    if (indexFile) {
      const relativePath = parts.join('/');
      exposes[`./${relativePath}`] = `./src/views/${relativePath}/${indexFile}`;
    }
  }

  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    if (entry.isDirectory()) {
      scan(path.join(dir, entry.name), [...parts, entry.name]);
    }
  }
}

if (fs.existsSync(viewsDir)) {
  scan(viewsDir);
}

export default exposes;
