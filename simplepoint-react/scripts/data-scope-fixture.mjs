import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';
const contract = name => JSON.parse(readFileSync(new URL('./data-scope-contracts/' + name + '.json', import.meta.url), 'utf8'));
const base = '/common/data-scopes', schema = contract('schema');
const fields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([key]) => [key, {type: 'string'}]));

/** The actual H2/controller JSON contracts are served only to the production browser bundle. */
export function createDataScopeFixture() {
  const row = contract('row'), reads = [], validations = [], writes = [], holds = new Map(), failures = new Map();
  let enabled = false, mode = 'all', malformed = false;
  const held = async (kind, item) => {const hold = holds.get(kind); holds.delete(kind); if (hold) await hold; item.completed = true;};
  return {row, reads, validations, writes, enable() {enabled = true;}, mode(value) {mode = value;}, malformed(value) {malformed = value;},
    fail(kind) {failures.set(kind, 503);},
    hold(kind) {let release; holds.set(kind, new Promise(resolve => {release = resolve;})); return () => release();},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled || !url.pathname.startsWith(base)) return false;
      const path = url.pathname, snapshot = mode;
      if (req.method === 'GET') {
        const kind = path === base ? 'list' : path.slice(base.length + 1), params = new URLSearchParams(url.searchParams);
        const read = {kind, mode: snapshot, params, completed: false}; reads.push(read);
        if (['masked-presentation', 'hidden-presentation'].includes(snapshot) && ['schema', 'list', 'presentation', 'effective'].includes(kind)) {
          if (kind === 'list') {assert.equal(Number(params.get('page') ?? 0), 0); assert.equal(Number(params.get('size') ?? 20), 20);}
          respond(contract(snapshot.split('-')[0] + '-' + kind)); read.completed = true; return true;
        }
        const target = kind === 'expression/catalog' ? params.get('resourceCode') : null;
        const failureKey = target != null ? kind + ':' + target : kind + ':all';
        const bad = failures.get(failureKey) ?? failures.get(kind); failures.delete(failureKey); failures.delete(kind);
        const result = kind === 'schema' ? contract(snapshot === 'protected' ? 'protected-schema' : 'schema')
          : kind === 'presentation' ? contract('presentation') : kind === 'expression/catalog' ? contract('catalog')
          : kind === 'effective' ? (malformed ? {...contract('effective'), includeSelf: 'false'} : contract('effective')) : undefined;
        let output = result;
        if (kind === 'expression/catalog' && target != null) {
          output = Object.prototype.hasOwnProperty.call(result, target) ? {[target]: result[target]} : {};
        }
        if (kind === 'list') {
          const number = Number(params.get('page') ?? 0), size = Number(params.get('size') ?? 20);
          assert.ok(Number.isSafeInteger(number) && number >= 0 && Number.isSafeInteger(size) && size > 0 && size <= 200);
          const query = compileLocalQuery(fields, {filters: Object.fromEntries([...params].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: params.getAll('sort')});
          const actual = snapshot === 'protected' ? {...row, type: contract('protected-row').type} : {...row};
          const all = [actual].filter(query.matches).sort(query.compare);
          output = {content: all.slice(number * size, (number + 1) * size), page: {number, size, totalElements: all.length, totalPages: all.length ? 1 : 0}};
        }
        if (output === undefined) throw Error('Unregistered DataScope read: ' + path);
        await held(target != null ? kind + ':' + target : kind, read);
        if (bad) {expectedFailure(bad); respond({message: '权限元数据读取失败（隔离测试）'}, bad);} else respond(output);
        return true;
      }
      if (['masked-presentation', 'hidden-presentation'].includes(snapshot)) throw Error('Native protected presentation scenario is read-only');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = raw ? JSON.parse(raw) : null;
      if (req.method === 'POST' && path === base + '/expression/validate') {
        const validation = {data, mode: snapshot, completed: false}; validations.push(validation);
        assert.equal(data.resourceCode, 'organizations.view', 'must not invent a target page');
        const valid = contract('validation');
        if (data.ast) {
          assert.deepEqual(data.ast, valid.ast, 'visual roundtrip uses the actual exported immutable AST');
        } else assert.equal(data.expression?.trim(), valid.expression);
        const bad = failures.get('validation'); failures.delete('validation'); await held('validation', validation);
        if (bad) {expectedFailure(bad); respond({message: '表达式预校验失败（隔离测试）'}, bad);} else respond(valid);
        return true;
      }
      if (req.method === 'PUT' && path === base) {
        assert.equal(data.id, row.id);
        // The established default controller carries its captured server projections unchanged.
        // Explicit ACL fields, unlike trusted contextual/display-only projections, must be omitted.
        for (const field of ['createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'tenantId', 'createOrgDeptId', 'deletedAt'])
          if (field in data) assert.deepEqual(data[field], row[field], 'server-owned projection must not be changed: ' + field);
        if (snapshot === 'protected') for (const field of ['name', 'type', 'expression', 'resourceCode']) assert.ok(!(field in data), 'protected field must be omitted: ' + field);
        const write = {data, mode: snapshot, completed: false}; writes.push(write);
        const bad = failures.get('write'); failures.delete('write'); await held('write', write);
        if (bad) {expectedFailure(bad); respond({message: '策略保存失败（隔离测试）'}, bad);} else {Object.assign(row, data); respond(row);}
        return true;
      }
      throw Error('Unexpected DataScope mutation: ' + req.method + ' ' + path);
    },
  };
}
