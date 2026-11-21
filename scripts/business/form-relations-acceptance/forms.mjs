/** Synthetic single-value relation businesses. All identities come from persisted API receipts. */
const text = (defaultValue, english) => ({defaultValue, defaultLocale: 'zh-CN', translations: {'en-US': english}});
const field = (id, label, type = 'STRING', extra = {}) => { const transient = extra.storageMode === 'TRANSIENT'; return {fieldId: id, storageCode: transient ? null : id, label, storageLabel: transient ? null : label, type, storageMode: transient ? 'TRANSIENT' : 'STORED', storageRequired: false, submitRequired: false, choices: [], ...(type === 'STRING' ? {length: 240} : {}), ...(type === 'DECIMAL' ? {precision: 24, scale: 2} : {}), ...extra}; };
const node = (id, componentType, extra = {}) => ({componentId: id, componentType, componentVersion: '1',
  columnSpan: 12, readOnly: false, properties: {}, children: [], ...extra});
const input = f => node('input_' + f.fieldId, f.storageMode === 'REFERENCE' ? 'field.reference'
  : f.type === 'DECIMAL' ? 'field.number' : 'field.text', {fieldId: f.fieldId});
const document = (fields, details = [], relations = []) => ({contractVersion: '1', fields, details, relations,
  indexes: [], rules: [], constraints: [], scenes: [{sceneId: 'main', code: 'main', name: '业务表单', type: 'MAIN',
    root: node('root', 'layout.form', {columnSpan: 24, children: [...fields.map(input), ...details.map(d => node('detail_' + d.detailId,
      'data.detail', {columnSpan: 24, label: d.label, properties: {detailId: {kind: 'STRING', value: d.detailId}}})),
      node('save', 'action.save')]})}]});
const target = (state, key) => ({applicationId: state.appId, formId: state.forms[key].formId,
  accessResourceId: state.forms[key].resourceId});
const relation = (state, id, source, key, cardinality = 'MANY_TO_ONE', mappings = []) => ({contractVersion: '1', relationId: id,
  cardinality, sourceField: typeof source === 'string' ? {fieldId: source} : source, target: target(state, key),
  referenceView: {labelFieldId: 'name', searchFieldIds: ['name'], displayFieldIds: ['name'], allowDrillThrough: true}, mappings});
const mapping = (id, mode) => ({mappingId: id, targetFieldId: 'address', destinationFieldId: id, mode,
  conversion: 'IDENTITY', nullPolicy: 'CLEAR'});
const spec = (code, name, english, doc) => ({code, name, nameLocalization: text(name, english), document: doc});
export function relationBusinesses(state) {
  const reference = (id, label) => field(id, label, 'UUID', {storageMode: 'REFERENCE'});
  return [
    () => spec('rel_customer', '关联验收客户', 'Relation customers', document([field('name', '客户名称'), field('address', '客户地址'), field('department', '部门')])),
    () => spec('rel_product', '关联验收商品', 'Relation products', document([field('name', '商品名称'), field('price', '单价', 'DECIMAL')])),
    () => spec('rel_employee', '关联验收员工', 'Relation employees', document([field('name', '员工名称'), field('department', '部门')])),
    () => spec('rel_trade', '关联验收交易', 'Relation trades', document([field('name', '交易名称'), reference('customer', '所属客户'),
      field('snapshot_address', '客户地址快照'), field('default_address', '可编辑地址'), field('live_address', '客户当前地址', 'STRING', {storageMode: 'TRANSIENT'})],
      [{detailId: 'items', storageCode: 'items', label: '商品明细', indexes: [], fields: [reference('product', '商品'), field('quantity', '数量', 'DECIMAL')]}],
      [relation(state, 'customer', 'customer', 'rel_customer', 'MANY_TO_ONE', [mapping('snapshot_address', 'SERVER_SNAPSHOT'),
        mapping('default_address', 'EDITABLE_DEFAULT'), mapping('live_address', 'LIVE')]),
      relation(state, 'product', {detailId: 'items', fieldId: 'product'}, 'rel_product')])),
    () => spec('rel_profile', '关联验收员工档案', 'Relation employee profiles', document([field('name', '档案名称'), reference('employee', '对应员工')], [],
      [relation(state, 'employee', 'employee', 'rel_employee', 'ONE_TO_ONE')])),
  ];
}
export function customerWithInverse(state, original) {
  return {...original, presentation: {listFieldOrder: ['name', 'address', 'department'], inverseViews: [{viewId: 'trades', label: '客户交易',
    labelLocalization: text('客户交易', 'Customer trades'), source: target(state, 'rel_trade'), sourceRelationId: 'customer',
    allowCreate: true, allowEdit: true, allowDelete: true, allowUnlink: true}]}};
}
