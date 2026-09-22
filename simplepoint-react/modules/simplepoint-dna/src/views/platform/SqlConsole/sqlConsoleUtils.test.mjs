import assert from 'node:assert/strict';
import test from 'node:test';
import {assertConsoleResponse, filterLoadedTree, executionSql, quoteSqlPath, resultCsv, sqlStatementRanges} from './sqlConsoleUtils.ts';

test('HTTP 200 business errors remain failures with their original detail', () => {
  assert.throws(() => assertConsoleResponse({success: false, message: 'permission denied'}, 'failed'), /permission denied/);
  assert.throws(() => assertConsoleResponse(null, 'failed'), /failed/);
  assert.doesNotThrow(() => assertConsoleResponse([], 'failed'));
});
test('loaded object search keeps ancestors without mutating the original tree', () => {
  const tree = [{rawTitle: 'database', children: [{rawTitle: 'orders'}, {rawTitle: 'users'}]}];
  assert.deepEqual(filterLoadedTree(tree, 'ORDER'), [{rawTitle: 'database', children: [{rawTitle: 'orders'}]}]);
  assert.equal(tree[0].children.length, 2);
  assert.equal(filterLoadedTree(tree, ''), tree);
  assert.deepEqual(filterLoadedTree(tree, 'missing'), []);
});
test('executes selection or caret statement, not every statement', () => {
  const sql = 'select 1;\nselect 2;';
  assert.equal(executionSql(sql, 12), 'select 2;');
  assert.equal(executionSql(sql, 0), 'select 1;');
  assert.equal(executionSql(sql, sql.length), 'select 2;');
  assert.equal(executionSql(sql, 0, 8), 'select 1');
  assert.equal(executionSql('', 0), '');
});
test('ignores semicolons inside strings, quoted identifiers, dollar quotes and nested comments', () => {
  for (const part of ["'a;''b'", '"a;""b"', '$body$a;b$body$', '$$a;b$$', '1 /* ; /* ; */ ; */', '1 -- ;\n']) {
    const sql = `select ${part}; select 2;`;
    assert.equal(sqlStatementRanges(sql).length, 2, sql);
    assert.equal(executionSql(sql, 0), `select ${part};`);
  }
});
test('quotes metadata identifiers and exports duplicate columns without losing values', () => {
  assert.equal(quoteSqlPath(['ds', 'a.b', 'a"b']), '"ds"."a.b"."a""b"');
  const csv = resultCsv([{name: 'id'}, {name: 'id'}], [[1, 'a,"b'], [null, '=1+1']]);
  assert.equal(csv, '\uFEFF"id","id"\r\n"1","a,""b"\r\n"","\'=1+1"');
});
