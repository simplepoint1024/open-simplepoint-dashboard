const applicant = {applicant_name: '验收员工·漢字😀', employee_no: 'CAL-TEST-001', department: 'platform', contact: '测试分机 8601'};

/** Synthetic values only. Callers provide an authorized uploaded file, never a fabricated storage ID. */
export function expenseSample(file, amount = '4800', overrides = {}) {
  return {...applicant, subject: '【计算验收】客户交付差旅 ' + amount, application_date: '2026-10-19', expense_type: 'travel',
    cost_center: 'RD-PLATFORM', project_code: 'CAL-DELIVERY', currency: 'CNY', business_purpose: '赴合成客户现场完成部署、培训和验收；仅用于引擎验证，不实际付款。',
    payment_method: 'personal_bank', payee: '合成测试收款人', bank_name: '仿真银行测试支行', bank_account: 'TEST-NOT-PAYABLE',
    invoice_count: 1, attachments: JSON.stringify([file]), attachment_note: '所附文件为合成验收凭证，不用于真实财务结算。', notes: '验证计算、审批和不可变快照。',
    exchange_rate: '1', discount_rate: '1', advance_amount: '0', destination: '上海测试办公区', departure_date: '2026-10-17', return_date: '2026-10-19',
    expense_items: [{values: {expense_date: '2026-10-18', category: 'hotel', merchant: '合成商务酒店', description: '交付实施住宿及服务',
      invoice_type: 'vat_electronic', invoice_no: 'SIM-CAL-' + amount.replace('.', '-'), attachment_ref: file.name ?? '验收凭证',
      quantity: '1', unit_price: amount, tax_rate: '0.06'}}], ...overrides};
}

export function dailySample() {
  return {...applicant, subject: '【计算验收】项目研发日报・日報', work_date: '2026-10-19', work_location: 'office',
    summary: '完成多场景计算和流程集成验证，记录并修复边界问题。', achievements: '提交公式契约、并发验证数据和审批结果。',
    blockers: '有一项需求待确认，已记录负责人。', support_needed: '协调客户验收时间。', tomorrow_plan: '完成权限矩阵及发布恢复演练。',
    work_items: [{values: {project_code: 'CAL-ENGINE', project_name: '计算引擎建设', category: 'development', task_title: '主从计算集成',
      work_content: '实现字段依赖、完整快照和服务端约束。', hours: '5.5', planned_hours: '5', progress: 100, status: 'done', deliverable: 'CAL-101'}},
    {values: {project_code: 'CAL-ENGINE', project_name: '计算引擎建设', category: 'testing', task_title: '发布与流程测试',
      work_content: '验证发布、审批、权限及多语种字段。', hours: '3.25', planned_hours: '3', progress: 80, status: 'ongoing', deliverable: 'CAL-102'}}]};
}

export function deliverySample(file) {
  return {project_code: 'CAL-DELIVERY', customer: '合成客户华东实验室', release_version: '2026.10.1', owner: '测试交付负责人', reviewer: '测试验收人',
    delivery_date: '2026-10-19', review_time: '14:30:00', environment: '隔离验收环境', conclusion: '核心场景满足验收要求；其余整改项已明确负责人和期限。',
    risk: false, service_rating: 4, evidence: JSON.stringify([file]), evidence_note: '附件为合成场景说明，明细记录实际测试结果。',
    milestones: [{values: {milestone: '核心功能', weight: '60', score: '80', criteria: '全部关键链路可执行', actual: '关键链路通过，非关键问题已登记', remediation_owner: '研发测试负责人', due_date: '2026-10-23'}},
      {values: {milestone: '质量与文档', weight: '40', score: '95', criteria: '覆盖率及操作说明齐全', actual: '验收证据完整', remediation_owner: '质量测试负责人', due_date: '2026-10-22'}}]};
}

export function assetsSample(suffix) {
  return {request_no: 'AST-' + suffix.toUpperCase().replace(/[^A-Z0-9-]/g, ''), request_type: '采购与领用', applicant: '合成测试员工',
    department: '平台研发部', supplier: '合成设备供应商', purpose: '建设隔离测试工位，用于交付质量验证。', budget: '5000',
    delivery_date: '2026-10-22', return_date: '2027-01-22', location: JSON.stringify(['shanghai', 'lab']), custodians: JSON.stringify(['qa', 'developer']),
    items: [{values: {asset_code: 'TEST-MONITOR', category: '电子设备', specification: '测试显示器 27 寸', quantity: '2', unit_price: '1250.25', custodian: '测试保管人', serial_no: 'SIM-M-001', notes: '合成设备，非真实采购'}},
      {values: {asset_code: 'TEST-DOCK', category: '配件', specification: '测试扩展坞', quantity: '1', unit_price: '399.50', custodian: '研发保管人', serial_no: 'SIM-D-001', notes: '与测试工位配套'}}]};
}
