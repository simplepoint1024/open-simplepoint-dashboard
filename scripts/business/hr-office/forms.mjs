/** Portable business definitions. Environment identities and credentials never belong in this module. */
const value = (kind, value) => ({kind, value: String(value)});
const field = (id, label, type = 'STRING', options = {}) => ({fieldId: id, storageCode: id,
  label, storageLabel: label, type, storageMode: type === 'JSON' ? 'ATTACHMENT' : 'STORED',
  submitRequired: true, storageRequired: false, length: type === 'STRING' ? 200 : null,
  precision: type === 'DECIMAL' ? 18 : null, scale: type === 'DECIMAL' ? 2 : null,
  defaultValue: null, choices: [], writePolicy: 'UNTIL_SUBMITTED', ...options});
const choice = (id, label, choices, options = {}) => field(id, label, 'STRING', {
  choices: choices.map(([value, label]) => ({value, label})), ...options});
const text = (id, label, required = true) => field(id, label, 'TEXT', {submitRequired: required});
const number = (id, label, options = {}) => field(id, label, 'DECIMAL', options);
const boolean = (id, label) => field(id, label, 'BOOLEAN', {defaultValue:value('BOOLEAN', false)});
const node = (id, type, children = [], properties = {}) => ({componentId:id, componentType:type,
  componentVersion:'1', columnSpan:24, readOnly:false, properties, children});
const component = f => ({...node('input_'+f.fieldId, f.type === 'JSON' ? 'field.attachment'
  : f.choices.length ? 'field.select' : ({TEXT:'field.textarea',DECIMAL:'field.number',INT32:'field.number',
    DATE:'field.date',DATETIME:'field.datetime',BOOLEAN:'field.switch'})[f.type] || 'field.text'),
  fieldId:f.fieldId, columnSpan: ['TEXT','JSON'].includes(f.type) ? 24 : 12,
});
const range = (id, min, max, message) => ({constraintId:'range_'+id, kind:'RANGE',fieldId:id,minimum:min,maximum:max,message});
const details = (id, label, fields) => ({detailId:id,storageCode:id,label,fields,indexes:[]});
const detailRules = (id, amount, total, message, max) => [
  {constraintId:id+'_count',kind:'DETAIL_COUNT',detailId:id,minimum:'1',maximum:'40',message:'请至少填写一条'+message},
  {constraintId:id+'_positive',kind:'DETAIL_RANGE',detailId:id,detailFieldId:amount,minimum:'0.01',maximum:max,message:message+'数值必须大于 0'+(max?' 且不超过 '+max:'')},
  {constraintId:id+'_sum',kind:'DETAIL_SUM',detailId:id,detailFieldId:amount,fieldId:total,message:message+'合计与明细不一致'},
];
const groups = (definitions, sections) => sections.map(([id,label,ids]) => ({...node('group_'+id,'layout.group',
  ids.map(id => component(definitions.find(f=>f.fieldId===id)))),label}));
const document = (fields, sections, detailDefs = [], constraints = [], rules = []) => ({contractVersion:'1',fields,
  indexes:[],details:detailDefs,constraints,rules,scenes:[{sceneId:'main',code:'main',name:'业务表单',type:'MAIN',width:null,
    root:node('root','layout.form',[
      ...groups(fields,sections),
      ...detailDefs.map(detail=>({...node('detail_'+detail.detailId,'data.detail',[],{detailId:value('STRING',detail.detailId)}),label:detail.label})),
      node('save','action.save'),
    ])}]});
const departments=[['platform','平台研发部'],['operations','综合运营部'],['sales','销售与客户服务部']];
const applicant = () => [field('applicant_name','申请人姓名'),field('employee_no','员工工号'),
  choice('department','所属部门',departments),field('contact','联系电话/分机')];
const meetingFields=[field('subject','会议主题'),...applicant(),
  choice('room','会议室',[['A301','A 座 301 · 10 人'],['A502','A 座 502 · 20 人'],['B201','B 座 201 · 40 人']]),
  choice('meeting_type','会议类型',[['internal','内部会议'],['client','客户接待'],['training','培训/分享'],['interview','招聘面试']]),
  field('starts_at','开始时间','DATETIME'),field('ends_at','结束时间','DATETIME'),field('attendees','参会人数','INT32'),
  field('host_name','主持人'),text('participants','参会人员/部门'),text('agenda','会议议程'),
  choice('equipment','设备需求',[['standard','标准投影/白板'],['video','视频会议套件'],['audio','扩音/麦克风'],['none','无需额外设备']]),
  boolean('external_visitors','是否有外部来访'),text('visitor_details','来访单位、人员及接待安排',false),
  choice('confidentiality','保密等级',[['normal','普通内部'],['restricted','限参会人员'],['confidential','保密会议']]),
  boolean('refreshments','是否需要茶水'),text('arrangement_note','布场、清洁或其他安排',false)];
const dailyFields=[field('subject','日报标题'),field('work_date','工作日期','DATE'),...applicant(),
  choice('work_location','工作地点',[['office','公司办公'],['remote','远程办公'],['onsite','客户现场'],['travel','出差']]),
  number('total_hours','实际工时合计（小时）',{defaultValue:value('DECIMAL','0')}),
  number('overtime_hours','其中加班工时',{defaultValue:value('DECIMAL','0')}),
  text('summary','今日工作总结'),text('achievements','完成成果/交付物'),text('blockers','风险与阻塞',false),
  text('support_needed','需协调事项',false),text('tomorrow_plan','下一工作日计划')];
const dailyDetails=details('work_items','工作明细',[
  field('project_code','项目/业务编号'),field('project_name','项目/业务名称'),
  choice('category','工作类型',[['development','研发'],['testing','测试'],['operations','运营'],['meeting','会议'],['support','支持'],['other','其他']]),
  field('task_title','任务名称'),text('work_content','工作内容与结果'),number('hours','实际工时（小时）'),
  field('progress','完成进度（%）','INT32'),choice('status','任务状态',[['done','已完成'],['ongoing','进行中'],['blocked','受阻']]),
  field('deliverable','交付物/关联单号','STRING',{submitRequired:false})]);
const expenseFields=[field('subject','报销事由'),field('application_date','申请日期','DATE'),...applicant(),
  choice('expense_type','报销类别',[['travel','差旅费'],['office','办公采购'],['hospitality','业务招待'],['training','培训费'],['mixed','综合费用']]),
  field('cost_center','成本中心/部门编号'),field('project_code','归属项目/合同编号'),
  choice('currency','币种',[['CNY','人民币 CNY']],{defaultValue:value('STRING','CNY')}),
  number('total_amount','报销总额（元）',{defaultValue:value('DECIMAL','0')}),
  text('business_purpose','费用发生的业务背景'),
  choice('payment_method','收款方式',[['personal_bank','个人银行卡'],['corporate','对公支付']]),
  field('payee','收款户名'),field('bank_name','开户银行及支行'),field('bank_account','收款账号','STRING',{length:40}),
  field('invoice_count','发票张数','INT32'),field('attachments','发票及证明附件','JSON'),
  text('attachment_note','附件对应明细说明'),text('notes','其他说明',false)];
const expenseDetails=details('expense_items','费用明细',[
  field('expense_date','费用日期','DATE'),choice('category','费用科目',[['transport','交通费'],['hotel','住宿费'],['meals','餐费'],['office','办公费'],['training','培训费'],['other','其他']]),
  field('merchant','商户/收款单位'),field('description','用途/行程/服务内容'),number('amount','含税金额（元）'),
  number('tax_amount','其中税额（元）',{defaultValue:value('DECIMAL','0')}),
  choice('invoice_type','凭证类型',[['vat_electronic','增值税电子发票'],['vat_paper','增值税纸质发票'],['ticket','交通票据'],['receipt','其他合规凭证']]),
  field('invoice_no','发票/凭证号码'),field('attachment_ref','对应附件文件名/页码')]);
export const officeForms=[
 {code:'office_meeting_room',name:'会议室申请',description:'会议室、时间、参会人员及接待需求；行政核实容量和场地冲突后确认。',
  document:document(meetingFields,[['request','申请信息',['subject','applicant_name','employee_no','department','contact']],
   ['schedule','会议安排',['room','meeting_type','starts_at','ends_at','attendees','host_name','participants','agenda']],
   ['service','会务需求',['equipment','confidentiality','external_visitors','refreshments','visitor_details','arrangement_note']]],[],
  [range('attendees','1','40','参会人数须为 1 至 40 人'),{constraintId:'meeting_order',kind:'AFTER',fieldId:'ends_at',otherFieldId:'starts_at',message:'会议结束时间必须晚于开始时间'}],
  [{ruleId:'visitors_required',targetComponentId:'input_visitor_details',effect:'REQUIRE',condition:{operator:'EQUALS',fieldId:'external_visitors',values:[value('BOOLEAN',true)],children:[]}}])},
 {code:'office_daily_report',name:'日报填写',description:'按工作日记录成果、任务工时及协作事项，由部门负责人确认。',
  document:document(dailyFields,[['report','日报信息',['subject','work_date','applicant_name','employee_no','department','contact','work_location','total_hours','overtime_hours']],
   ['summary','总结与计划',['summary','achievements','blockers','support_needed','tomorrow_plan']]], [dailyDetails],
   [...detailRules('work_items','hours','total_hours','工作明细工时','24'),range('total_hours','0.01','24','单日工时合计须大于 0 且不超过 24 小时'),
    range('overtime_hours','0','16','加班工时须在 0 至 16 小时之间'),
    {constraintId:'progress_bounds',kind:'DETAIL_RANGE',detailId:'work_items',detailFieldId:'progress',minimum:'0',maximum:'100',message:'完成进度须在 0 至 100 之间'}])},
 {code:'office_expense_claim',name:'财务报销',description:'费用明细与发票附件留档；负责人审核，超过 5,000 元增加分管负责人审批，再由财务复核。',
  document:document(expenseFields,[['request','申请信息',['subject','application_date','applicant_name','employee_no','department','contact']],
   ['cost','费用归属',['expense_type','cost_center','project_code','currency','total_amount','business_purpose']],
   ['payment','收款信息',['payment_method','payee','bank_name','bank_account']],
   ['evidence','凭证附件',['invoice_count','attachments','attachment_note','notes']]], [expenseDetails],
   [...detailRules('expense_items','amount','total_amount','费用明细'),range('total_amount','0.01','99999999.99','报销总额须大于 0 且不超过 99,999,999.99 元'),
    range('invoice_count','1','1000','发票张数须为 1 至 1,000 张'),
    {constraintId:'tax_bounds',kind:'DETAIL_RANGE',detailId:'expense_items',detailFieldId:'tax_amount',minimum:'0',maximum:null,message:'税额不能为负数'}])},
];
