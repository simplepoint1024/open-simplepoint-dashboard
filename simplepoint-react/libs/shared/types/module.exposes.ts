import {existsSync, readdirSync} from 'node:fs';
import path from 'node:path';

const entryFiles = ['index.tsx', 'index.ts', 'index.jsx', 'index.js'];
const privateDirectories = new Set(['config', 'components', '__tests__']);

/** Expose route pages, not implementation details such as configuration drawers.
 * Keep traversing public directories: nested public pages can
 * be legitimate hidden routes even when their parent also has an index.
 */
export function collectPageExposes(moduleDirectory: string): Record<string, string> {
  const viewsDirectory = path.join(moduleDirectory, 'src/views');
  const exposes: Record<string, string> = {};
  const scan = (directory: string, parts: string[]) => {
    const entry = entryFiles.find(file => existsSync(path.join(directory, file)));
    if (parts.length && entry) {
      const page = parts.join('/');
      exposes[`./${page}`] = `./src/views/${page}/${entry}`;
    }
    for (const child of readdirSync(directory, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name))) {
      if (child.isDirectory() && !privateDirectories.has(child.name)) {
        scan(path.join(directory, child.name), [...parts, child.name]);
      }
    }
  };
  if (existsSync(viewsDirectory)) scan(viewsDirectory, []);
  return exposes;
}
