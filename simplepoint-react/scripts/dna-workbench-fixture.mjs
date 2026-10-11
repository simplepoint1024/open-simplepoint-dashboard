import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';
export const metadataRoute = {id: 'metadata-workbench-test', code: 'dna.metadata.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/metadata-workbench', component: 'dna/platform/JdbcMetadata', label: '元数据标准测试', scopeTypes: ['PLATFORM']};
const sourceBase = '/dna/platform/dna/data-sources';
const metadataBase = '/dna/platform/dna/metadata';
const sqlBase = '/dna/platform/dna/federation/sql-console';
const path = [{type: 'SCHEMA', name: 'public'}, {type: 'TABLE', name: 'demo.table'}];
const permissions = ['view', 'preview', 'createNamespace', 'createTable', 'createView', 'alterColumn', 'dropColumn', 'addConstraint', 'dropConstraint', 'drop'];
export function createDnaWorkbenchFixture() {
  const sources = Array.from({length: 521}, (_, index) => ({id: 'source-id-' + index,
    code: 'source-' + String(index).padStart(3, '0'), name: 'Source' + String(index).padStart(3, '0'), enabled: true}));
  let active = false, readOnly = false, failedWrite = false;
  const requests = [], writes = [], executions = [];
  const columns = [{name: 'id', typeName: 'INTEGER', nullable: false}, {name: 'a,b', typeName: 'TEXT', nullable: true}];
  const constraints = [{name: 'ck_actual', type: 'CHECK', columns: [], checkExpression: 'id >= 0'}];
  const read = async req => {let value = ''; for await (const chunk of req) value += chunk; return value ? JSON.parse(value) : {};};
  return {requests, writes, executions, enable() {active = true;}, disable() {active = false;},
    readonly(value) {readOnly = value;}, failWrite() {failedWrite = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!active) return false;
      const pathname = url.pathname;
      if (![sourceBase, metadataBase, sqlBase].some(base => pathname.startsWith(base))) return false;
      requests.push({path: pathname, query: new URLSearchParams(url.searchParams)});
      if (pathname === sourceBase) {
        const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
        if (size > 20) throw Error('Workbench source reads must remain bounded to 20');
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
        const query = compileLocalQuery({name: {type: 'string'}, code: {type: 'string'}, enabled: {type: 'boolean'}},
          {filters, sort: url.searchParams.getAll('sort')});
        const rows = sources.filter(query.matches).sort(query.compare);
        return respond({content: rows.slice(page * size, (page + 1) * size),
          page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
      }
      if (pathname === metadataBase + '/workbench-permissions') return respond(Object.fromEntries(permissions.map(key => [key, !readOnly || key === 'view']))), true;
      if (pathname === sqlBase + '/workbench-permissions') return respond({canExecute: !readOnly, canExplain: false}), true;
      if (pathname === sqlBase + '/execute') {
        const payload = await read(req);
        if (readOnly) throw Error('Read-only console must not submit SQL');
        executions.push(payload);
        await new Promise(resolve => setTimeout(resolve, 250));
        return respond({type: 'QUERY', queryResult: {columns: [{name: 'actual', typeName: 'INTEGER'}], rows: [[1]],
          catalogCode: payload.catalogCode, returnedRows: 1, executionTimeMs: 1, truncated: false}}), true;
      }
      const metadata = pathname.match(/\/metadata\/(source-id-\d+)\/(.+)$/);
      if (!metadata) return false;
      const [, id, operation] = metadata;
      if (operation === 'completion-catalog') return respond({dataSourceId: id, dialectCode: 'postgresql',
        defaultSchema: 'public', relations: [{type: 'TABLE', path}]}), true;
      const payload = await read(req);
      if (operation === 'children') {
        const parts = payload.path ?? [];
        const nodes = parts.length === 0 ? [{title: 'public', type: 'SCHEMA', path: path.slice(0, 1), leaf: false}]
          : parts.length === 1 ? [{title: 'demo.table', type: 'TABLE', path, leaf: false}]
            : columns.map(column => ({title: column.name, type: 'COLUMN', path: [...path, {type: 'COLUMN', name: column.name}], leaf: true, dataType: column.typeName}));
        return respond(nodes.map(node => ({...node, key: id + ':' + JSON.stringify(node.path)}))), true;
      }
      if (operation === 'structure') return respond({columns, constraints, dialectName: 'PostgreSQL', tablePath: path}), true;
      if (operation === 'preview') {
        if (readOnly) throw Error('Viewing metadata must not query physical data without preview permission');
        const page = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'));
        return respond({columns: ['id', 'a,b'], content: Array.from({length: Math.min(size, 521 - page * size)}, (_, index) => ({id: page * size + index, 'a,b': 'actual-' + index})),
          totalElements: 521, pageNumber: page, pageSize: size}), true;
      }
      if (readOnly) throw Error('Read-only metadata must not issue DDL');
      writes.push({id, operation, payload});
      await new Promise(resolve => setTimeout(resolve, 250));
      if (failedWrite) {failedWrite = false; expectedFailure(503); return respond({message: '元数据保存失败（测试）'}, 503), true;}
      if (operation === 'columns') columns.push(payload.column);
      if (operation === 'columns/drop') {
        const index = columns.findIndex(column => column.name === payload.columnName); if (index >= 0) columns.splice(index, 1);
      }
      if (operation === 'constraints') constraints.push(payload.constraint);
      return respond(null), true;
    }};
}
