import {parseColumnFilter} from './filters.ts';

export type Scalar = string | number | boolean;
export type QueryNode = {op: string; field?: string; value?: Scalar | Scalar[]; children?: QueryNode[]; where?: QueryNode};
export type QueryContract = {version: 1; maxSort: number; maxNodes: number; maxDepth: number; maxPageSize?: number;
  fields: Record<string, {operators: string[]; sortable: boolean; javaType: string}>};
export const nullOps = ['is:null', 'is:not:null'];
export const arrayOps = ['in', 'not:in', 'between', 'not:between'];
export const operatorLabels: Record<string, string> = {
  like: '包含', 'not:like': '不包含', equals: '等于', 'not:equals': '不等于', in: '属于', 'not:in': '不属于',
  between: '介于', 'not:between': '不介于', 'than:greater': '大于', 'than:less': '小于',
  'than:equal:greater': '大于等于', 'than:equal:less': '小于等于', 'is:null': '为空', 'is:not:null': '非空',
};

export function readDocument(text?: string): QueryNode | undefined {
  if (!text) return undefined;
  try { const doc = JSON.parse(text); return doc.version === 1 ? doc.where : undefined; } catch { return undefined; }
}
export function encodeDocument(where: QueryNode): string { return JSON.stringify({version: 1, where}); }

export function summarizeNode(node: QueryNode, fields: Record<string, any>, label: (op: string) => string): string {
  if (node.children) return node.op === 'not' ? `NOT (${summarizeNode(node.children[0], fields, label)})`
    : `(${node.children.map(n => summarizeNode(n, fields, label)).join(node.op === 'or' ? ' OR ' : ' AND ')})`;
  const value = Array.isArray(node.value) ? node.value.map(v => JSON.stringify(v)).join(', ') : JSON.stringify(node.value);
  return `${fields[node.field ?? '']?.title ?? node.field} ${label(node.op)}${value === undefined ? '' : ` ${value}`}`;
}

/** Validate persisted/untrusted views against the *current* server capability contract. */
export function validateNode(node: QueryNode, contract: QueryContract): void {
  let count = 0;
  const visit = (n: QueryNode, depth: number) => {
    if (!n || depth > contract.maxDepth || ++count > contract.maxNodes) throw Error('筛选条件过于复杂');
    if (['and', 'or', 'not'].includes(n.op)) {
      if (!n.children?.length || (n.op === 'not' && n.children.length !== 1)) throw Error('条件组不能为空');
      n.children.forEach(child => visit(child, depth + 1)); return;
    }
    if (!n.field || !contract.fields[n.field]?.operators.includes(n.op)) throw Error('筛选字段或操作已不可用');
    if (nullOps.includes(n.op)) return;
    const values = Array.isArray(n.value) ? n.value : [n.value];
    if (arrayOps.includes(n.op) !== Array.isArray(n.value) || values.length === 0 || values.length > 100
      || (n.op.includes('between') && values.length !== 2)
      || values.some(v => v === undefined || v === null || !['string', 'boolean', 'number'].includes(typeof v)
        || String(v).length > 2000 || (typeof v === 'number' && !Number.isFinite(v)))) throw Error('请填写有效的筛选值');
    const javaType = contract.fields[n.field].javaType;
    if (['boolean', 'Boolean'].includes(javaType) && values.some(v => typeof v !== 'boolean')) throw Error('请选择布尔值');
    const integer = ['byte', 'Byte', 'short', 'Short', 'int', 'Integer', 'long', 'Long', 'BigInteger'].includes(javaType);
    const numeric = integer || ['float', 'Float', 'double', 'Double', 'BigDecimal'].includes(javaType);
    if (numeric && values.some(v => typeof v === 'boolean' || !Number.isFinite(Number(v))
      || !(integer ? /^[+-]?\d+$/ : /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/).test(String(v)))) throw Error('请填写有效的数值');
  };
  visit(node, 0);
  if (encodeDocument(node).length > 8192) throw Error('筛选内容过长');
}

export function filterNode(field: string, text: string): QueryNode | undefined {
  const structured = readDocument(text);
  if (structured) return structured;
  const old = parseColumnFilter(text);
  if (!old.op) return undefined;
  return {field, op: old.op, ...(nullOps.includes(old.op) ? {} : {
    value: arrayOps.includes(old.op) ? old.text.split(',') : old.text,
  })};
}

export type SavedView = {name: string; filters: Record<string, string>; sort?: string | string[]};

/** Keep business fields named page/size/sort out of the pagination parameter namespace. */
export function transportFilters(filters: Record<string, string>): Record<string, string> {
  const result = {...filters};
  const reserved = ['page', 'size', 'sort'].filter(field => Object.prototype.hasOwnProperty.call(result, field));
  if (!reserved.length) return result;
  const conditions = reserved.map(field => {
    const node = filterNode(field, result[field]);
    if (!node || node.field !== field || node.children) throw Error('无效的列筛选');
    delete result[field];
    return node;
  });
  const advanced = readDocument(result.$query);
  if (result.$query && !advanced) throw Error('无效的高级筛选');
  if (advanced?.op === 'and') conditions.unshift(...advanced.children!);
  else if (advanced) conditions.unshift(advanced);
  result.$query = encodeDocument(conditions.length === 1 ? conditions[0] : {op: 'and', children: conditions});
  return result;
}

export function validateView(view: SavedView, contract: QueryContract): void {
  if (!view || typeof view.name !== 'string' || !view.name.trim() || view.name.length > 40
    || !view.filters || typeof view.filters !== 'object' || Object.keys(view.filters).length > contract.maxNodes) throw Error('无效的已保存视图');
  Object.entries(view.filters).forEach(([field, value]) => {
    if (typeof value !== 'string') throw Error('无效的筛选值');
    const node = field === '$query' ? readDocument(value) : filterNode(field, value);
    if (!node) throw Error('无效的筛选条件');
    if (field !== '$query' && (node.field !== field || node.children)) throw Error('列筛选与字段不匹配');
    validateNode(node, contract);
  });
  const wireFilters = transportFilters(view.filters);
  const nodes = Object.entries(wireFilters).map(([field, value]) => filterNode(field, value)).filter(Boolean) as QueryNode[];
  nodes.forEach(node => validateNode(node, contract));
  const count = (n: QueryNode): number => 1 + (n.children?.reduce((sum, c) => sum + count(c), 0) ?? 0);
  if (nodes.reduce((sum, n) => sum + count(n), 0) > contract.maxNodes
    || JSON.stringify(wireFilters).length > 8192) throw Error('筛选条件总量超限');
  // Both generic and personal legacy adapters use GET. Leave room for path/pagination/headers.
  if (encodeURIComponent(JSON.stringify(wireFilters)).length > 6000) throw Error('筛选内容过长，请减少条件或集合值');
  const sort = view.sort ? (Array.isArray(view.sort) ? view.sort : [view.sort]) : [];
  if (sort.length > contract.maxSort || new Set(sort.map(s => s.split(',')[0])).size !== sort.length
    || sort.some(s => !/^[A-Za-z][A-Za-z0-9_]*,(asc|desc)$/.test(s) || !contract.fields[s.split(',')[0]]?.sortable)) throw Error('排序字段已不可用');
}
