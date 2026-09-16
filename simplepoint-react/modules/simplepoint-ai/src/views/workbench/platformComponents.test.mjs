import assert from 'node:assert/strict';
import {readdir, readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const workbenchRoot = path.dirname(fileURLToPath(import.meta.url));

const sourceFiles = async (directory) => {
  const entries = await readdir(directory, {withFileTypes: true});
  const nested = await Promise.all(entries.map(async (entry) => {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(absolute);
    return entry.isFile() && entry.name.endsWith('.tsx') ? [absolute] : [];
  }));
  return nested.flat();
};

test('AI workbench tables and schema forms use platform components', async () => {
  const files = await sourceFiles(workbenchRoot);
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, 'utf8');
    if (/import\s*\{[^}]*\bTable\b[^}]*}\s*from\s*['"]antd['"]/.test(source)) {
      violations.push(`${path.relative(workbenchRoot, file)} imports antd Table`);
    }
    if (/from\s*['"]@rjsf\/antd['"]/.test(source)) {
      violations.push(`${path.relative(workbenchRoot, file)} imports a raw RJSF form`);
    }
  }

  assert.deepEqual(violations, []);
});
