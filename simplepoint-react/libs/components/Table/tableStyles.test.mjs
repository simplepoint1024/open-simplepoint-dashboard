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

test('bounded DataTables shrink the row area, not the toolbar or pagination', () => {
  assert.match(css, /\.sp-data-table-fit\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*display:\s*flex;[^}]*overflow:\s*hidden;/);
  assert.match(css, /\.sp-data-table-fit > \.sp-data-table,[^{]*\{[^}]*height:\s*auto;[^}]*flex:\s*1;[^}]*min-height:\s*0;/);
  assert.match(css, /\.sp-data-table-fit > \.sp-data-table-toolbar,[^{]*\.sp-data-table-fit \.ant-table-pagination,[^{]*\{\s*flex-shrink:\s*0;/);
  const naturalRoot = css.match(/\.sp-data-table-root\s*\{([^}]*)\}/);
  assert.doesNotMatch(naturalRoot[1], /height|overflow/, 'normal-flow tables must not become height constrained globally');
});

test('table height propagates through both current and legacy loading wrappers', () => {
  assert.match(css, /\.sp-table-fill \.ant-spin-nested-loading,\s*\.sp-data-table-fit > \.sp-table-fill > \.ant-spin,\s*\.sp-data-table-root:fullscreen > \.sp-table-fill > \.ant-spin,\s*\.sp-table-fill \.ant-spin-container\s*\{\s*height:\s*100%;\s*min-height:\s*0;/);
  assert.doesNotMatch(css, /^\.sp-table-fill > \.ant-spin[,\s{]/m, 'existing pixel-measured and natural-flow tables retain their own loading-wrapper layout');
});

test('light and dark header gradients have an opaque base beneath sticky cells', () => {
  assert.match(css, /background: linear-gradient\(180deg, #fafcff[^;]+\) var\(--ant-color-bg-container, #fff\) !important;/);
  assert.match(css, /background: linear-gradient\(180deg, rgba\(255, 255, 255, 0\.065\)[^;]+\) var\(--ant-color-bg-container, #141414\) !important;/);
});
