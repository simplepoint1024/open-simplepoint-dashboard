import assert from 'node:assert/strict';
export const dnaRuntimeRoute = {id: 'dna-runtime-details-test', code: 'dna.runtime.models.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/dna-runtime-details', component: 'dna/platform/RuntimeGovernance', label: '表单数据底座测试', scopeTypes: ['PLATFORM']};
const base = '/dna/platform/dna/runtime';
export function createDnaRuntimeFixture() {
  let enabled = false, holdPlan = false, releasePlan;
  const requests = [];
  const model = {id: 'model-one', displayName: '实际模型详情', appId: 'app-one', formId: 'form-one', status: 'ACTIVE',
    latestVersion: 1, activeVersion: 1, lockVersion: 0, updatedAt: '2026-10-14T00:00:00Z'};
  return {requests, holdPlan() {holdPlan = true;}, pendingPlan() {return !!releasePlan;}, releasePlan() {holdPlan = false; releasePlan?.(); releasePlan = undefined;}, enable() {enabled = true;}, async handle(req, url, respond) {
    if (!enabled || !url.pathname.startsWith(base)) return false;
    requests.push(url.pathname);
    if (req.method !== 'GET') throw Error('Details regression must never write');
    if (url.pathname.endsWith('/plan')) {
      if (holdPlan) await new Promise(resolve => {releasePlan = resolve;});
      respond({physicalModelId:'model-one',targetVersion:1,dialectId:'postgresql',risk:'SAFE',steps:[
        {sequence:1,operation:'CREATE_TABLE',logicalObject:'实际表',risk:'SAFE',status:'SUCCEEDED'}]}); return true;
    }
    if (url.pathname === base + '/models/model-one') {
      await new Promise(resolve => setTimeout(resolve, 150));
      respond({model, schemaJson: JSON.stringify({contractVersion: '1.0', fields: [
        {fieldId: 'name-id', code: 'actual_name', displayName: '实际字段', type: 'STRING', nullable: false, length: 120},
        {fieldId: 'amount-id', code: 'amount', displayName: '金额', type: 'DECIMAL', nullable: true, precision: 18, scale: 2},
      ], indexes: [{code: 'name_amount_idx', fieldCodes: ['actual_name', 'amount'], unique: true}]}) }); return true;
    }
    if (url.pathname.endsWith('/schema')) {
      const fields = {id: {type: 'string', 'x-hidden': true}, displayName: {type: 'string', title: '名称'}, status: {type: 'string', title: '状态'}};
      respond({schema: {type: 'object', properties: fields}, buttons: [{key: 'view', title: '查看详情', text: '查看详情',
        icon: 'EyeOutlined', sort: 0, argumentMinSize: 1, argumentMaxSize: 1},
        {key:'plan',text:'查看计划',icon:'EyeOutlined',sort:1,argumentMinSize:1,argumentMaxSize:1}]}); return true;
    }
    const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
    respond({content: Array.from({length: size}, (_, i) => ({...model, id: page === 0 && i === 0 ? 'model-one' : 'model-' + (page * size + i), displayName: i === 0 ? '实际模型详情' : '模型' + i})),
      page: {number: page, size, totalElements: 120, totalPages: Math.ceil(120 / size)}}); return true;
  }};
}
export async function smokeDnaRuntimeDetails({clickMenu, clickReady, clickText, poll, evaluate, send, fixture, preferences}) {
  await clickMenu('表单数据底座测试');
  const root = '.nb-tab-panel-active .dna-runtime-tabs';
  await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="model-one"]')`), 'model table');
  await clickReady(root + ' tr[data-row-key="model-one"] input[type="checkbox"]');
  await clickText(root + ' button', '查看详情');
  await poll(() => evaluate("document.querySelector('.ant-drawer-open')?.textContent.includes('实际字段')"), 'persisted model detail');
  assert.equal(await evaluate("!!document.querySelector('.ant-drawer-open textarea')"), false);
  await clickReady('.ant-drawer-open .dna-model-definition .ant-tabs-tab[data-node-key="indexes"]');
  await poll(() => evaluate("document.querySelector('.ant-drawer-open')?.textContent.includes('实际字段 (actual_name) → 金额 (amount)')"), 'ordered index fields');
  assert.ok(fixture.requests.includes(base + '/models/model-one'));
  assert.ok(!fixture.requests.some(path => path.endsWith('/plan')));
  await clickReady('.ant-drawer-open .ant-drawer-close');

  // A delayed response belongs to its retained page, never to the foreground tab.
  fixture.holdPlan(); await clickText(root + ' button', '查看计划');
  await poll(() => fixture.pendingPlan(), 'held plan request');
  await clickMenu('首页'); fixture.releasePlan();
  await evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
  assert.equal(await evaluate("!!document.querySelector('.ant-drawer-open')"), false);
  await clickMenu('表单数据底座测试');
  await clickText(root + ' button', '查看计划');
  await poll(() => evaluate("document.querySelector('.ant-drawer-open')?.textContent.includes('实际表')"), 'current plan');
  const plan = '.ant-drawer-open .sp-data-table-root';
  await clickReady(plan + ' [aria-label="切换为卡片"]');
  await poll(() => preferences.has('sp.data-table.dna.runtime.plan.steps'), 'DataTable settings synchronized to account');
  assert.equal(JSON.parse(preferences.get('sp.data-table.dna.runtime.plan.steps')).display.viewMode, 'cards');
  await clickReady('.ant-drawer-open .ant-drawer-close');
  // Remove browser fallback; reopening must obtain the saved account preference.
  await evaluate("Object.keys(localStorage).filter(key=>key.includes('dna.runtime.plan.steps')).forEach(key=>localStorage.removeItem(key))");
  await clickText(root + ' button', '查看计划');
  await poll(() => evaluate("!!document.querySelector('" + plan + " [aria-label=\"切换为列表\"]')"), 'card mode restored from account');
  await clickReady('.ant-drawer-open .ant-drawer-close');
  await clickReady(root + ' [aria-label="表格设置"]');
  await poll(() => evaluate("!!document.querySelector('.sp-table-settings-drawer.ant-drawer-open')"), 'settings opened');
  await clickMenu('首页');
  await poll(() => evaluate("!document.querySelector('.sp-table-settings-drawer.ant-drawer-open')"), 'inactive page hides settings');
  await clickMenu('表单数据底座测试');
  await poll(() => evaluate("!!document.querySelector('.sp-table-settings-drawer.ant-drawer-open')"), 'owning tab restores settings');
  await clickReady('.sp-table-settings-drawer.ant-drawer-open .ant-drawer-close');

  for (const viewport of [{width: 1440, height: 900}, {width: 1024, height: 700}]) {
    await send('Emulation.setDeviceMetricsOverride', {...viewport, deviceScaleFactor: 1, mobile: false});
    for (const key of ['models', 'versions', 'migrations', 'audits']) {
      await clickReady(root + ` > .ant-tabs-nav .ant-tabs-tab[data-node-key="${key}"]`);
      await poll(() => evaluate(`[...document.querySelectorAll('${root} .ant-pagination')].some(e=>e.getClientRects().length)`), 'visible pagination in '+key);
      const bounds = await evaluate(`(() => {const pagination=[...document.querySelectorAll('${root} .ant-pagination')].find(e=>e.getClientRects().length),
        pane=pagination.closest('[role="tabpanel"]'), table=pane.querySelector('.sp-table-root');
        return {pane:pane.getBoundingClientRect().height,table:table.getBoundingClientRect().height,
          paginationBottom:pagination.getBoundingClientRect().bottom,viewport:innerHeight,overflow:pane.scrollHeight-pane.clientHeight};})()`);
      assert.ok(bounds.table > 200 && Math.abs(bounds.pane - bounds.table) < 3, JSON.stringify(bounds));
      assert.ok(bounds.paginationBottom <= bounds.viewport && bounds.overflow < 5, JSON.stringify(bounds));
    }
  }
}
