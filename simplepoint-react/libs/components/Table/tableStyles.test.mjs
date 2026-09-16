import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const css = await readFile(new URL('../Simplepoint.css', import.meta.url), 'utf8');

test('fixed table headers retain sticky positioning', () => {
  assert.match(
    css,
    /th\.ant-table-cell-fix-start,[\s\S]*?th\.ant-table-cell-fix-end\s*\{\s*position:\s*sticky;/,
  );

  const genericHeaderRule = css.match(
    /\.sp-table-fill\.ant-table-wrapper \.ant-table-thead > tr > th,\s*[\s\S]*?\{([\s\S]*?)\}/,
  );
  assert.ok(genericHeaderRule);
  assert.doesNotMatch(genericHeaderRule[1], /position:\s*relative/);
});
