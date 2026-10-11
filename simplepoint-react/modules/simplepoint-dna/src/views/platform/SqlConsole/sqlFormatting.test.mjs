import assert from 'node:assert/strict';
import test from 'node:test';
import {formatSqlText, sqlFormatterLanguageFor} from './sqlFormatting.ts';

test('maps JDBC product names to formatter dialects with a conservative fallback', () => {
  assert.equal(sqlFormatterLanguageFor('PostgreSQL 17.2'), 'postgresql');
  assert.equal(sqlFormatterLanguageFor('MariaDB'), 'mariadb');
  assert.equal(sqlFormatterLanguageFor('MySQL'), 'mysql');
  assert.equal(sqlFormatterLanguageFor('Microsoft SQL Server'), 'transactsql');
  assert.equal(sqlFormatterLanguageFor('Oracle Database 23ai'), 'plsql');
  assert.equal(sqlFormatterLanguageFor('H2'), 'sql');
});

test('formats PostgreSQL SQL while preserving Unicode identifiers and values', () => {
  assert.equal(
    formatSqlText('select u."姓名",count(*) as 件数 from public."用户表" u where u."状态"=? group by u."姓名";', 'PostgreSQL'),
    [
      'SELECT',
      '  u."姓名",',
      '  COUNT(*) AS 件数',
      'FROM',
      '  public."用户表" u',
      'WHERE',
      '  u."状态" = ?',
      'GROUP BY',
      '  u."姓名";',
    ].join('\n'),
  );
});

test('formats multiple statements without changing quoted semicolons', () => {
  const formatted = formatSqlText("insert into logs(message) values ('中文;日本語');select * from logs;", 'MySQL');
  assert.match(formatted, /VALUES\n  \('中文;日本語'\);/u);
  assert.match(formatted, /;\n\nSELECT/u);
});

test('leaves the platform cache command and blank content unchanged', () => {
  assert.equal(formatSqlText('  FLUSH CACHE;\n', 'PostgreSQL'), '  FLUSH CACHE;\n');
  assert.equal(formatSqlText(' \n', 'PostgreSQL'), ' \n');
});
