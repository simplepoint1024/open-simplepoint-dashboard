import {officeForms} from '../hr-office/forms.mjs';

/** Portable acceptance businesses. Identities, grants and uploads are supplied by the environment. */
const literal = (kind, value) => ({kind, value: String(value)});
const field = (id, label, type = 'STRING', extra = {}) => ({fieldId: id, storageCode: id,
  label, storageLabel: label, type, storageMode: 'STORED', submitRequired: true, storageRequired: false,
  length: type === 'STRING' ? 240 : null, precision: type === 'DECIMAL' ? 24 : null,
  scale: type === 'DECIMAL' ? 2 : null, choices: [], ...extra});
const computed = (id, label, expression, type = 'DECIMAL', extra = {}) => field(id, label, type, {
  storageMode: 'COMPUTED', calculation: {languageVersion: '1', expression}, ...extra});
const node = (id, type, extra = {}) => ({componentId: id, componentType: type, componentVersion: '1',
  columnSpan: 24, readOnly: false, properties: {}, children: [], ...extra});
const input = (f, extra = {}) => node('input_' + f.fieldId, f.choices.length ? 'field.select' : ({TEXT: 'field.textarea', DECIMAL: 'field.number',
  INT32: 'field.number', INT64: 'field.number', BOOLEAN: 'field.switch', DATE: 'field.date', TIME: 'field.time',
  DATETIME: 'field.datetime', JSON: 'field.json'})[f.type] ?? 'field.text', {fieldId: f.fieldId, columnSpan: 12, ...extra});
const detail = (id, label, fields) => ({detailId: id, storageCode: id, label, fields, indexes: []});
const section = (id, label, fields) => node('section_' + id, 'layout.group', {label, children: fields.map(f => input(f))});
const document = (fields, children, details = [], constraints = []) => ({contractVersion: '1', fields,
  details, indexes: [], rules: [], constraints, scenes: [{sceneId: 'main', code: 'main', name: '业务表单', type: 'MAIN',
    root: node('root', 'layout.form', {children: [...children, ...details.map(d => node('detail_' + d.detailId, 'data.detail',
      {label: d.label, properties: {detailId: literal('STRING', d.detailId)}})), node('save', 'action.save')]})}]});
const minimumRows = id => ({constraintId: id + '_required', kind: 'DETAIL_COUNT', detailId: id,
  minimum: '1', maximum: '40', message: '至少填写一条明细，最多四十条'});
const replace = (fields, id, expression) => {
  const index = fields.findIndex(f => f.fieldId === id), prior = fields[index];
  const {defaultValue, writePolicy, ...base} = prior;
  fields[index] = {...base, storageMode: 'COMPUTED', calculation: {languageVersion: '1', expression}};
};

function expense() {
  const result = structuredClone(officeForms.find(f => f.code === 'office_expense_claim'));
  result.code = 'cal_expense'; result.name = '差旅及费用报销验收';
  const d = result.document, lines = d.details[0];
  lines.fields.push(field('quantity', '数量', 'DECIMAL', {defaultValue: literal('DECIMAL', '1')}),
    field('unit_price', '单价', 'DECIMAL'), field('tax_rate', '税率', 'DECIMAL', {scale: 4, defaultValue: literal('DECIMAL', '0.06')}));
  replace(lines.fields, 'amount', 'ROUND([quantity]*[unit_price]*[parent.discount_rate],2)');
  replace(lines.fields, 'tax_amount', 'ROUND([amount]-[amount]/(1+[tax_rate]),2)');
  lines.fields.push(computed('net_amount', '未税金额', '[amount]-[tax_amount]'));
  replace(d.fields, 'total_amount', 'SUM([expense_items.amount])');
  d.fields.find(f => f.fieldId === 'currency').choices.push({value: 'JPY', label: '日元 JPY'}, {value: 'USD', label: '美元 USD'});
  const additions = [field('exchange_rate', '本位币汇率', 'DECIMAL', {scale: 6, defaultValue: literal('DECIMAL', '1')}),
    field('discount_rate', '结算折扣', 'DECIMAL', {scale: 4, defaultValue: literal('DECIMAL', '1')}),
    field('advance_amount', '已借款金额', 'DECIMAL', {defaultValue: literal('DECIMAL', '0')}),
    computed('tax_total', '税额合计', 'SUM([expense_items.tax_amount])'),
    computed('base_amount', '本位币总额', 'ROUND([total_amount]*[exchange_rate],2)'),
    computed('payable_amount', '本次应付', 'MAX(0,[base_amount]-[advance_amount])'),
    computed('risk_level', '审批级别', 'IF([base_amount]>5000,"加签总监","标准审批")', 'STRING'),
    field('destination', '出差地点'), field('departure_date', '出发日期', 'DATE'), field('return_date', '返程日期', 'DATE')];
  d.fields.push(...additions); d.scenes[0].root.children.splice(-1, 0, section('travel', '差旅及折算', additions));
  d.constraints.push({constraintId: 'discount_range', kind: 'RANGE', fieldId: 'discount_rate', minimum: '0.0001', maximum: '1', message: '折扣须大于 0 且不超过 1'},
    {constraintId: 'exchange_range', kind: 'RANGE', fieldId: 'exchange_rate', minimum: '0.000001', maximum: '100000', message: '汇率须为有效正数'});
  return result;
}

function daily() {
  const result = structuredClone(officeForms.find(f => f.code === 'office_daily_report'));
  result.code = 'cal_daily'; result.name = '项目工作日报验收';
  const d = result.document;
  replace(d.fields, 'total_hours', 'SUM([work_items.hours])');
  replace(d.fields, 'overtime_hours', 'MAX(0,[total_hours]-8)');
  const progress = computed('completion_rate', '平均完成率', 'IF(COUNT([work_items.progress])=0,0,AVERAGE([work_items.progress]))');
  d.fields.push(progress); d.scenes[0].root.children.splice(-1, 0, input(progress));
  d.details[0].fields.push(field('planned_hours', '计划工时', 'DECIMAL', {defaultValue: literal('DECIMAL', '8')}),
    computed('variance_hours', '工时偏差', '[hours]-[planned_hours]'));
  return result;
}

function delivery() {
  const fields = [field('project_code', '项目编号'), field('customer', '客户名称（合成）'), field('release_version', '交付版本'),
    field('owner', '交付负责人'), field('reviewer', '验收联系人'), field('delivery_date', '交付日期', 'DATE'),
    field('review_time', '约定验收时间', 'TIME'), field('environment', '验收环境'), field('conclusion', '验收结论', 'TEXT'),
    field('risk', '存在未关闭风险', 'BOOLEAN', {defaultValue: literal('BOOLEAN', 'false')}),
    field('service_rating', '总体服务评价', 'INT32'), computed('weighted_score', '加权评分',
      'IF(SUM([milestones.weight])=0,0,ROUND(SUM([milestones.weighted])/SUM([milestones.weight]),2))'),
    field('evidence', '证据附件', 'JSON', {storageMode: 'ATTACHMENT'}), field('evidence_note', '证据说明', 'TEXT')];
  const lines = detail('milestones', '里程碑验收', [field('milestone', '里程碑'), field('weight', '权重', 'DECIMAL'),
    field('score', '评分', 'DECIMAL'), computed('weighted', '加权得分', '[weight]*[score]'), field('criteria', '验收标准', 'TEXT'),
    field('actual', '实际结果', 'TEXT'), field('remediation_owner', '整改负责人'), field('due_date', '整改期限', 'DATE')]);
  const tabs = node('delivery_tabs', 'layout.tabs', {children: [section('delivery', '交付与联系人', fields.slice(0, 8)),
    section('result', '结论与证据', fields.slice(8))]});
  tabs.children[1].children.find(n => n.fieldId === 'service_rating').componentType = 'field.rating';
  tabs.children[1].children.find(n => n.fieldId === 'service_rating').properties = {count: literal('INTEGER', 5)};
  tabs.children[1].children.find(n => n.fieldId === 'evidence').componentType = 'field.attachment';
  return {code: 'cal_delivery', name: '项目交付验收', description: '交付与质量并行评审；合成业务验收资料。',
    document: document(fields, [tabs, node('review_divider', 'display.divider', {label: '验收说明'}),
      node('review_guidance', 'display.markdown', {properties: {text: literal('STRING', '### 验收规范\n核对每个里程碑的标准、结果和证据；评分为 0–100，权重为正数。')}}),
      node('risk_guidance', 'display.alert', {properties: {text: literal('STRING', '未解决的质量风险须记录整改负责人和期限。'), severity: literal('STRING', 'warning')}}),
      node('quality_icon', 'display.icon', {properties: {icon: literal('STRING', 'SafetyCertificateOutlined'), size: literal('INTEGER', 24)}}),
      node('review_progress', 'display.progress', {properties: {percent: literal('INTEGER', 100)}})], [lines], [minimumRows('milestones'),
      {constraintId: 'score_range', kind: 'DETAIL_RANGE', detailId: 'milestones', detailFieldId: 'score', minimum: '0', maximum: '100', message: '评分应为 0 至 100'},
      {constraintId: 'weight_range', kind: 'DETAIL_RANGE', detailId: 'milestones', detailFieldId: 'weight', minimum: '0.01', maximum: '100', message: '权重应大于 0 且不超过 100'}])};
}

function assets() {
  const fields = [field('request_no', '申请编号', 'STRING', {writePolicy: 'CREATE_ONLY', validation: {minLength: 6, maxLength: 40, pattern: '^AST-[A-Z0-9-]+$', patternMessage: '请输入 AST- 开头的大写申请编号'}}),
    field('request_type', '采购或领用'), field('applicant', '申请人'), field('department', '部门'), field('supplier', '供应商（合成）'),
    field('purpose', '用途', 'TEXT'), field('budget', '预算', 'DECIMAL'), field('delivery_date', '交付日期', 'DATE'),
    field('return_date', '预计归还日期', 'DATE'), field('location', '资产存放地点', 'JSON'), field('custodians', '领用人员', 'JSON'),
    computed('total_quantity', '总数量', 'SUM([items.quantity])'), computed('total_amount', '预算占用', 'SUM([items.amount])'),
    computed('remaining_budget', '预算余额', '[budget]-[total_amount]')];
  const lines = detail('items', '采购领用明细', [field('asset_code', '物料或资产编号'), field('category', '资产分类'),
    field('specification', '规格型号'), field('quantity', '数量', 'DECIMAL'), field('unit_price', '单价', 'DECIMAL'),
    computed('amount', '金额', 'ROUND([quantity]*[unit_price],2)'), field('custodian', '保管人'), field('serial_no', '序列号'),
    field('notes', '备注', 'TEXT', {submitRequired: false})]);
  const children = [section('application', '申请与交付', fields)];
  const location = children[0].children.find(n => n.fieldId === 'location');
  location.componentType = 'field.cascader'; location.properties = {options: literal('JSON', JSON.stringify([
    {value: 'shanghai', label: '上海办公区', children: [{value: 'lab', label: '研发实验室'}, {value: 'office', label: '综合办公室'}]},
  ]))};
  const people = children[0].children.find(n => n.fieldId === 'custodians');
  people.componentType = 'field.transfer'; people.properties = {options: literal('JSON', JSON.stringify([
    {value: 'qa', label: '测试保管人'}, {value: 'developer', label: '研发保管人'},
  ]))};
  const d = document(fields, children, [lines], [minimumRows('items'),
    {constraintId: 'budget_range', kind: 'RANGE', fieldId: 'budget', minimum: '0', maximum: '99999999', message: '预算须非负且不超过核定范围'},
    {constraintId: 'quantity_range', kind: 'DETAIL_RANGE', detailId: 'items', detailFieldId: 'quantity', minimum: '0.01', maximum: '10000', message: '数量应大于 0 且不超过 10000'},
    {constraintId: 'price_range', kind: 'DETAIL_RANGE', detailId: 'items', detailFieldId: 'unit_price', minimum: '0', maximum: '99999999', message: '单价不可为负数'}]);
  d.indexes = [{indexId: 'request_unique', storageCode: 'request_unique', unique: true, fieldIds: ['request_no']}];
  return {code: 'cal_assets', name: '采购与资产领用验收', description: '预算及资产明细核算，部门、采购和资产管理员依次确认。', document: d};
}

/** Cases come from the independent engine's named conformance corpus, never from evaluated client code. */
export function calculationBusinesses(cases) {
  const inputs = [field('function_name', '函数'), field('expression', '输入表达式', 'TEXT'), field('expected', '预期结果', 'TEXT'),
    field('result_type', '结果类型'), field('reviewer', '核验人'), field('conclusion', '核验说明', 'TEXT')];
  inputs[0].choices = cases.map(item => ({value: item.name, label: item.name}));
  const groups = [];
  for (let offset = 0; offset < cases.length; offset += 9) {
    const chunk = cases.slice(offset, offset + 9);
    const expression = chunk.reduceRight((next, item) => `IF([function_name]="${item.name}",STRING(${item.expression}),${next})`, 'NULL');
    groups.push(computed('result_' + groups.length, '验算组 ' + (groups.length + 1), expression, 'TEXT', {submitRequired: false}));
  }
  const actual = computed('actual', '服务端计算结果', `COALESCE(${groups.map(f => '[' + f.fieldId + ']').join(',')})`, 'TEXT');
  const ledger = {code: 'cal_ledger', name: '公式与发布运维验收台账', description: '逐函数实际入库验算；表达式列仅供核对，执行已发布定义。',
    document: document([...inputs, ...groups, actual], [section('case', '验算样例', inputs), input(actual)])};
  return [expense(), daily(), delivery(), assets(), ledger];
}
