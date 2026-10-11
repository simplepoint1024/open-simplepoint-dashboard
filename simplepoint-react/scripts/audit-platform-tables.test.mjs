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

test('query inventory recognizes shared remote-list props but not unrelated spreads', () => {
  const sites = tableSites(`import Grid from '@simplepoint/components/DataTable';
    import {useRemoteList as useRemote} from '@simplepoint/components/DataTable/useRemoteList';
    const View = () => {const records = useRemote('/app/applications', true); const other = unrelated();
      return <><Grid {...records.tableProps}/><Grid {...other.tableProps}/>
        <Grid {...records.tableProps} query={{mode:'local', fields:{}}}/></>;};`, 'modules/test.tsx');
  assert.deepEqual(sites.map(site => site.queryBinding), ['remote-list', 'not-declared', 'local']);
});

test('query inventory follows conditional selections only when both sides own remote queries', () => {
  const sites = tableSites(`import Grid from '../DataTable'; import {useRemoteList} from '../DataTable/useRemoteList';
    const View = () => {const pending = useRemoteList('/pending', true); const done = useRemoteList('/done', true);
      const list = selected ? pending : done; const ambiguous = selected ? pending : arbitrary;
      return <><Grid {...list.tableProps}/><Grid {...ambiguous.tableProps}/></>;};`, 'libs/test.tsx');
  assert.deepEqual(sites.map(site => site.queryBinding), ['remote-list', 'not-declared']);
});

test('query exceptions remain visible without being counted as a query contract', () => {
  const [site] = tableSites(`import Grid from '../DataTable';
    const View = () => <Grid data-query-exception="external-database-preview"/>;`, 'libs/test.tsx');
  assert.equal(site.queryException, 'external-database-preview');
  assert.equal(site.queryBinding, 'not-declared');
});
