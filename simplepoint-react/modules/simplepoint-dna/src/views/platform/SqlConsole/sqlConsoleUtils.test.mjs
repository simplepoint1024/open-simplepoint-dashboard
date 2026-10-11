import assert from 'node:assert/strict';
import test from 'node:test';
import {assertConsoleResponse, buildTableSqlTemplate, filterLoadedTree, executionSql, quoteSqlPath, resultCsv, sqlStatementRanges, sqlStatements} from './sqlConsoleUtils.ts';

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
test('builds an executable statement queue and ignores trailing comment-only ranges', () => {
  assert.deepEqual(
    sqlStatements('select 1; -- attached to the next statement\nselect 2;\n-- trailing comment'),
    ['select 1;', '-- attached to the next statement\nselect 2;'],
  );
  assert.deepEqual(sqlStatements(' ; -- no SQL\n /* nested /* comment */ only */ ;'), []);
});
test('preserves Unicode literals, comments and quoted identifiers across statements', () => {
  assert.deepEqual(
    sqlStatements("-- 中文注释\nINSERT INTO \"用户表\" (\"姓名\") VALUES ('张三;テスト');\nSELECT \"姓名\" FROM \"用户表\";"),
    [
      "-- 中文注释\nINSERT INTO \"用户表\" (\"姓名\") VALUES ('张三;テスト');",
      'SELECT "姓名" FROM "用户表";',
    ],
  );
  assert.equal(quoteSqlPath(['数据源', '業務', '用户表']), '"数据源"."業務"."用户表"');
});
test('quotes metadata identifiers and exports duplicate columns without losing values', () => {
  assert.equal(quoteSqlPath(['ds', 'a.b', 'a"b']), '"ds"."a.b"."a""b"');
  const csv = resultCsv([{name: 'id'}, {name: 'id'}], [[1, 'a,"b'], [null, '=1+1']]);
  assert.equal(csv, '\uFEFF"id","id"\r\n"1","a,""b"\r\n"","\'=1+1"');
});
test('builds parameterized CRUD templates from table metadata', () => {
  const path = ['source', 'public', 'order'];
  const structure = {
    columns: [
      {name: 'id', autoIncrement: true},
      {name: 'display_name', autoIncrement: false},
      {name: 'group'},
    ],
    constraints: [{type: 'PRIMARY_KEY', columns: ['id']}],
  };

  assert.equal(buildTableSqlTemplate('SELECT', path), 'SELECT *\nFROM "source"."public"."order";');
  assert.equal(
    buildTableSqlTemplate('INSERT', path, structure),
    'INSERT INTO "source"."public"."order" (\n  "display_name",\n  "group"\n) VALUES (\n  ?,\n  ?\n);',
  );
  assert.equal(
    buildTableSqlTemplate('UPDATE', path, structure),
    'UPDATE "source"."public"."order"\nSET\n  "display_name" = ?,\n  "group" = ?\nWHERE "id" = ?;',
  );
  assert.equal(
    buildTableSqlTemplate('DELETE', path, structure),
    'DELETE FROM "source"."public"."order"\nWHERE "id" = ?;',
  );
});
test('uses non-destructive write guards when a table has no primary key metadata', () => {
  const structure = {columns: [{name: 'value'}], constraints: []};
  assert.match(buildTableSqlTemplate('UPDATE', ['source', 'events'], structure), /WHERE 1 = 0;/);
  assert.match(buildTableSqlTemplate('DELETE', ['source', 'events'], structure), /WHERE 1 = 0;/);
  assert.equal(
    buildTableSqlTemplate('INSERT', ['source', 'heartbeat'], {columns: [{name: 'id', autoIncrement: true}]}),
    'INSERT INTO "source"."heartbeat"\nDEFAULT VALUES;',
  );
});
