import test from 'node:test';
import assert from 'node:assert/strict';
import {tableSites, auditTables} from './audit-platform-tables.mjs';

test('table audit resolves aliases, namespace imports, direct paths and native markup', () => {
  const sites = tableSites(`import {Table as Grid} from 'antd'; import * as Ant from 'antd';
    import Direct from 'antd/es/table'; import Standard from '@simplepoint/components/DataTable';
    const View = () => <><Grid/><Ant.Table/><Direct/><table/><Standard/></>;`, 'modules/test.tsx');
  assert.deepEqual(sites.map(site => site.kind), ['nonstandard', 'nonstandard', 'nonstandard', 'nonstandard', 'platform']);
});

test('business tables only render through platform components', async () => {
  const result = await auditTables();
  assert.ok(result.filesScanned > 100, 'audit must cover all module and library TSX sources');
  assert.deepEqual(result.sites.filter(site => site.kind === 'nonstandard'), []);
  assert.equal(result.sites.filter(site => site.kind === 'date-grid').length, 1);
});

test('assignment wrapper remains part of the platform table inventory', () => {
  const sites = tableSites(`import Assign from '@simplepoint/components/AssignmentTable';
    const View = () => <Assign/>;`, 'modules/test.tsx');
  assert.equal(sites.length, 1);
  assert.equal(sites[0].kind, 'platform');
});

test('audit does not count action helpers as tables and records explicit query ownership', () => {
  const sites = tableSites(`import {TableActions} from '@simplepoint/components/Table';
    import Table from '@simplepoint/components/DataTable';
    const View = () => <><TableActions/><Table query={{mode:'local', scope:'complete', fields:{}}}/></>;`, 'modules/test.tsx');
  assert.equal(sites.length, 1);
  assert.equal(sites[0].queryBinding, 'local');
});
