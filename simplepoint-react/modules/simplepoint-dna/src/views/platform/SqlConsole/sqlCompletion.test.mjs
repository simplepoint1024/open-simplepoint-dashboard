import assert from 'node:assert/strict';
import test from 'node:test';
import {analyzeSqlCompletion, completeSqlMetadata, resolveSqlSymbol} from './sqlCompletion.ts';

const dataSource = {
  id: 'source-1',
  code: 'platform_postgresql',
  name: 'Platform PostgreSQL',
  databaseProductName: 'PostgreSQL',
};

const catalog = {
  dialectCode: 'postgresql',
  dialectName: 'PostgreSQL',
  identifierQuote: '"',
  currentCatalog: 'postgres',
  currentSchema: 'public',
  relations: [
    {
      path: [
        {type: 'DATABASE', name: 'postgres'},
        {type: 'SCHEMA', name: 'public'},
        {type: 'TABLE', name: '用户表'},
      ],
      type: 'TABLE',
      remarks: '用户信息',
    },
    {
      path: [
        {type: 'DATABASE', name: 'postgres'},
        {type: 'SCHEMA', name: 'sales'},
        {type: 'VIEW', name: 'monthly_orders'},
      ],
      type: 'VIEW',
    },
  ],
};

const provider = {
  dataSources: [dataSource],
  catalogCode: dataSource.code,
  defaultSchema: 'public',
  async loadCatalog(id) {
    assert.equal(id, dataSource.id);
    return catalog;
  },
  async loadStructure(id, path) {
    assert.equal(id, dataSource.id);
    assert.equal(path.at(-1)?.name, '用户表');
    return {
      columns: [
        {name: 'id', typeName: 'BIGINT', nullable: false},
        {name: '姓名', typeName: 'VARCHAR', size: 100, remarks: '显示名称'},
        {name: 'Order', typeName: 'INTEGER'},
      ],
    };
  },
};

test('resolves qualified physical table aliases to Unicode columns', async () => {
  const sql = 'SELECT u.\nFROM platform_postgresql.public."用户表" AS u;';
  const analysis = analyzeSqlCompletion(sql, sql.indexOf('u.') + 2);
  assert.deepEqual(analysis.qualifier, ['u']);
  assert.deepEqual(analysis.bindings, [{alias: 'u', path: ['platform_postgresql', 'public', '用户表']}]);

  const suggestions = await completeSqlMetadata(analysis, provider);
  assert.deepEqual(suggestions.map(item => item.label), ['id', '姓名', 'Order']);
  assert.equal(suggestions.find(item => item.label === '姓名')?.apply, '"姓名"');
  assert.equal(suggestions.find(item => item.label === '姓名')?.info, '显示名称');
  assert.equal(suggestions.find(item => item.label === 'Order')?.apply, '"Order"');
  assert.deepEqual(suggestions.find(item => item.label === '姓名')?.metadataTarget, {
    dataSourceId: dataSource.id,
    path: [...catalog.relations[0].path, {type: 'COLUMN', name: '姓名'}],
  });
});

test('suggests CTE and derived-table projected fields through aliases', async () => {
  const cteSql = 'WITH recent(user_id, display_name) AS (SELECT id, 姓名 FROM public."用户表")\nSELECT r. FROM recent r;';
  const cteAnalysis = analyzeSqlCompletion(cteSql, cteSql.indexOf('r.') + 2);
  assert.deepEqual(cteAnalysis.ctes, [{name: 'recent', columns: ['user_id', 'display_name']}]);
  assert.deepEqual((await completeSqlMetadata(cteAnalysis, provider)).map(item => item.label), ['user_id', 'display_name']);

  const derivedSql = 'SELECT d. FROM (SELECT id, 姓名 AS display_name FROM public."用户表") AS d;';
  const derivedAnalysis = analyzeSqlCompletion(derivedSql, derivedSql.indexOf('d.') + 2);
  assert.deepEqual(derivedAnalysis.bindings, [{alias: 'd', path: [], columns: ['id', 'display_name']}]);
  assert.deepEqual((await completeSqlMetadata(derivedAnalysis, provider)).map(item => item.label), ['id', 'display_name']);
});

test('derives SELECT aliases for ORDER BY completion', async () => {
  const sql = 'SELECT u.id, u.姓名 AS display_name\nFROM public."用户表" u\nORDER BY dis';
  const analysis = analyzeSqlCompletion(sql, sql.length);
  assert.equal(analysis.prefix, 'dis');
  assert.ok(analysis.selectAliases.includes('display_name'));
  const suggestions = await completeSqlMetadata(analysis, provider);
  assert.ok(suggestions.some(item => item.label === 'display_name' && item.detail === 'SELECT ALIAS'));
});

test('suggests datasource, namespaces, current-schema relations, and qualified children', async () => {
  const relationSql = 'SELECT * FROM ';
  const relationAnalysis = analyzeSqlCompletion(relationSql, relationSql.length);
  assert.equal(relationAnalysis.expected, 'relation');
  const root = await completeSqlMetadata(relationAnalysis, provider);
  assert.ok(root.some(item => item.label === 'platform_postgresql' && item.type === 'namespace'));
  assert.ok(root.some(item => item.label === '用户表' && item.type === 'table'));
  assert.ok(root.some(item => item.label === 'sales' && item.type === 'namespace'));

  const namespaceSql = 'SELECT * FROM platform_postgresql.postgres.sales.';
  const namespace = await completeSqlMetadata(analyzeSqlCompletion(namespaceSql, namespaceSql.length), provider);
  assert.deepEqual(namespace.map(item => item.label), ['monthly_orders']);
});

test('keeps completion statement-local and disables it inside literals and comments', () => {
  const sql = 'SELECT old_alias. FROM old_table old_alias;\nSELECT current_alias. FROM "用户表" current_alias;';
  const analysis = analyzeSqlCompletion(sql, sql.lastIndexOf('current_alias.') + 'current_alias.'.length);
  assert.deepEqual(analysis.qualifier, ['current_alias']);
  assert.deepEqual(analysis.bindings.map(item => item.alias), ['current_alias']);

  const literal = "SELECT '用户表';";
  assert.equal(analyzeSqlCompletion(literal, literal.indexOf('用户') + 1).blocked, true);
  const comment = 'SELECT 1 -- 用户表';
  assert.equal(analyzeSqlCompletion(comment, comment.length).blocked, true);
});

test('tracks a Unicode identifier prefix without splitting it into ASCII fragments', () => {
  const sql = 'SELECT 用户';
  const analysis = analyzeSqlCompletion(sql, sql.length);
  assert.equal(analysis.prefix, '用户');
  assert.equal(analysis.from, 'SELECT '.length);
});

test('resolves table and qualified column symbols to permission-scoped metadata targets', async () => {
  const sql = 'SELECT u.姓名 FROM platform_postgresql.postgres.public."用户表" AS u;';
  const column = await resolveSqlSymbol(sql, sql.indexOf('姓名') + 1, provider);
  assert.deepEqual(column?.suggestion.metadataTarget, {
    dataSourceId: dataSource.id,
    path: [...catalog.relations[0].path, {type: 'COLUMN', name: '姓名'}],
  });

  const table = await resolveSqlSymbol(sql, sql.indexOf('用户表') + 1, provider);
  assert.deepEqual(table?.suggestion.metadataTarget, {
    dataSourceId: dataSource.id,
    path: catalog.relations[0].path,
  });
});

test('does not resolve symbols inside SQL comments or string literals', async () => {
  const comment = '-- 用户表\nSELECT 1';
  assert.equal(await resolveSqlSymbol(comment, comment.indexOf('用户表') + 1, provider), null);
  const literal = "SELECT '用户表'";
  assert.equal(await resolveSqlSymbol(literal, literal.indexOf('用户表') + 1, provider), null);
});
