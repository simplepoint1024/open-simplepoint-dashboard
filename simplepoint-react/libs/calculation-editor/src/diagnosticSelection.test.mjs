import test from 'node:test';
import assert from 'node:assert/strict';
import {parseFormula} from '@simplepoint/calculation';
import {diagnosticSelection} from './diagnosticSelection.ts';

test('syntax errors after astral characters select the actual token, including multiline expressions', () => {
  for (const source of ['="😀" + @', '=\n"😀日本語" + @']) {
    let error; try {parseFormula(source);} catch (failure) {error = failure;}
    assert.equal(error.code, 'SYNTAX');
    const [start, end] = diagnosticSelection(source, error.line, error.column);
    assert.equal(source.slice(start, end), '@');
  }
});
test('selection handles end of input and never splits a surrogate pair', () => {
  assert.deepEqual(diagnosticSelection('😀', 1, 0), [0, 2]);
  assert.deepEqual(diagnosticSelection('😀', 1, 1), [2, 2]);
});
