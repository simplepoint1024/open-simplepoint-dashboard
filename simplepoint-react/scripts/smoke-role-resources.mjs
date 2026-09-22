import assert from 'node:assert/strict';

export async function smokeRoleResources({clickReady, clickText, fill, poll, evaluate, fixture}) {
  const list = '.role-resource-list';
  const detailGrant = '.role-resource-detail .role-resource-detail-title input[type="checkbox"]';
  await poll(() => evaluate(`!!document.querySelector('${list} [data-resource-id="resource-root"]')`), 'root resource loads');
  assert.equal(await evaluate(`(() => {const left=document.querySelector('.role-resource-list')?.getBoundingClientRect();
    const right=document.querySelector('.role-resource-detail')?.getBoundingClientRect();
    return !!left && !!right && left.height > 300 && Math.abs(left.height-right.height) < 2;})()`), true,
    'empty right panel fills the same available height as the resource tree');
  assert.equal(await evaluate(`(() => {const left=document.querySelector('.role-resource-list').getBoundingClientRect();
    const right=document.querySelector('.role-resource-detail').getBoundingClientRect(); return left.width/right.width < .42;})()`), true,
    'resource directory uses the narrower left column');
  const panelHeight = await evaluate(`document.querySelector('.role-resource-list').getBoundingClientRect().height`);
  await evaluate(`(() => {const handle=document.querySelector('.role-resource-drawer .ant-drawer-body > div');
    handle.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, clientY:500}));
    window.dispatchEvent(new MouseEvent('mousemove', {bubbles:true, clientY:440}));
    window.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, clientY:440})); return true;})()`);
  await poll(() => evaluate(`document.querySelector('.role-resource-list').getBoundingClientRect().height > ${panelHeight + 40}`),
    'both panels grow when the drawer is resized');
  await evaluate(`(() => {const handle=document.querySelector('.role-resource-drawer .ant-drawer-body > div');
    handle.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, clientY:440}));
    window.dispatchEvent(new MouseEvent('mousemove', {bubbles:true, clientY:600}));
    window.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, clientY:600})); return true;})()`);
  await poll(() => evaluate(`(() => {const left=document.querySelector('.role-resource-list').getBoundingClientRect();
    const right=document.querySelector('.role-resource-detail').getBoundingClientRect();
    return left.height < ${panelHeight - 60} && Math.abs(left.height-right.height) < 2;})()`),
    'both panels shrink together with the drawer');
  assert.equal(await evaluate(`document.querySelector('.role-resource-detail')?.textContent.includes('功能')`), false,
    'resource editor terminology must not show legacy function wording');
  assert.equal(await evaluate(`!!document.querySelector('.role-resource-config .role-resource-scope')`), false,
    'removed role-wide scope editor must not render');
  await clickReady(`${list} [data-resource-id="resource-root"] .role-resource-node-label`);
  await poll(() => evaluate(`document.querySelectorAll('.role-resource-detail .role-resource-child-row').length === 240`),
    'right panel renders the complete first level without pagination');
  assert.equal(await evaluate(`document.querySelector('.role-resource-detail')?.textContent.includes('直属子资源')`), false);
  assert.equal(await evaluate(`document.querySelector('.role-resource-detail-title')?.textContent.includes('全部')`), true);
  assert.equal(await evaluate(`!!document.querySelector('.role-resource-children .ant-pagination')`), false);
  await clickReady('.role-resource-detail .role-resource-child-row[data-resource-id="resource-001"] .role-resource-expander');
  await poll(() => evaluate(`document.querySelectorAll('.role-resource-detail .role-resource-child-row').length === 242`),
    'nested operations expand locally from the complete subtree');
  const cardRects = await evaluate(`[...document.querySelectorAll('.role-resource-detail .role-resource-setting')]
    .map(node=>{const rect=node.getBoundingClientRect(); return {top:rect.top,bottom:rect.bottom,height:rect.height,width:rect.width};})`);
  assert.equal(cardRects.length, 3, 'three permission settings render');
  assert.equal(cardRects.every(rect => Math.abs(rect.top-cardRects[0].top)<2
    && Math.abs(rect.height-cardRects[0].height)<2), true,
    'resource, data and field settings share a horizontal row and equal height when space allows');
  assert.equal(await evaluate(`(() => {const layout=document.querySelector('.role-resource-settings');
    layout.style.width='480px'; const cards=[...layout.querySelectorAll('.role-resource-setting')]
      .map(node=>node.getBoundingClientRect()); layout.style.width='';
    return Math.abs(cards[0].top-cards[1].top)<2 && Math.abs(cards[0].height-cards[1].height)<2
      && cards[2].top>=cards[0].bottom;})()`), true,
    'settings wrap at narrow width while cards in the same row remain aligned');
  assert.equal(await evaluate(`(() => {const panel=document.querySelector('.role-resource-detail');
    panel.scrollTop=200; return panel.scrollTop > 0 && document.querySelector('.role-resource-drawer .ant-drawer-body').scrollTop === 0;})()`), true,
    'long right content scrolls inside its panel');
  await clickReady(`${list} button[aria-label="展开 测试资源目录"]`);
  await poll(() => evaluate(`!!document.querySelector('${list} [data-resource-id="resource-001"] .role-resource-node-label')`), 'lazy child page');
  assert.equal(await evaluate(`(() => {const panel=document.querySelector('.role-resource-list');
    panel.scrollTop=200; const internal=panel.scrollTop > 0;
    panel.scrollTop=0; return internal && document.querySelector('.role-resource-drawer .ant-drawer-body').scrollTop === 0;})()`), true,
    'long tree content scrolls inside its panel');
  assert.equal(await evaluate(`!!document.querySelector('${list} [data-resource-id="resource-001"] .role-resource-expander:is(button)')`), false,
    'operation-only children do not create a tree expander');
  assert.equal(await evaluate(`!!document.querySelector('${list} [data-resource-id="resource-operation-0"]')`), false,
    'operation resources are absent from the navigation tree');
  await clickReady(`${list} [data-resource-id="resource-001"] .role-resource-node-label`);
  await poll(() => evaluate(`document.querySelector('.role-resource-detail')?.textContent.includes('测试操作 0')
    && document.querySelector('.role-resource-detail')?.textContent.includes('测试操作 1')`),
    'operations remain available in the right grant panel');
  await clickReady('.role-resource-detail input[aria-label$="resource.operation.0"]');
  assert.equal(await evaluate(`document.querySelector('.role-resource-detail input[aria-label$="resource.operation.0"]').checked`), true);
  await clickReady('.role-resource-detail input[aria-label$="resource.operation.0"]');
  await clickReady(detailGrant);
  await clickReady(`${list} .role-resource-subtree .ant-pagination-item-12`);
  await poll(() => evaluate(`!!document.querySelector('${list} [data-resource-id="resource-239"] .role-resource-node-label')`), 'last child page beyond 200');
  await clickReady(`${list} [data-resource-id="resource-239"] .role-resource-node-label`);
  await clickReady(detailGrant);
  await clickReady('.role-resource-list button[aria-label="搜索资源"]');
  await fill('.role-resource-list .ant-input-search .ant-input', 'resource.001');
  await clickReady('.role-resource-list .ant-input-search-btn');
  await poll(() => evaluate(`document.querySelectorAll('${list} .role-resource-result').length===1`), 'server resource search');
  await clickReady(`${list} .role-resource-result[data-resource-id="resource-001"]`);
  assert.equal(await evaluate(`document.querySelector('${detailGrant}').checked`), true);
  assert.equal(await evaluate(`document.querySelector('.role-resource-detail')?.textContent.includes('数据权限')
    && document.querySelector('.role-resource-detail')?.textContent.includes('字段权限')`), true);
  await fill('.role-resource-list .ant-input-search .ant-input', '');
  await poll(() => evaluate(`!!document.querySelector('${list} [data-resource-id="resource-239"] .role-resource-node-label')`),
    'expanded tree and child page survive search');
  assert.equal(fixture.resourceSaves.length, 0, 'selection and reserved slots do not save');
  await clickReady('.role-resource-drawer .ant-drawer-close');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm')`), 'resource draft close confirmation');
  await clickReady('.ant-modal-confirm .ant-btn-default');
  fixture.conflictResources();
  await clickText('.role-resource-footer button', '预览保存');
  await poll(() => evaluate(`document.querySelector('.role-resource-preview')?.textContent.includes('资源授权预览')`), 'flat save preview');
  await clickText('.role-resource-preview button', '确认提交');
  await poll(() => evaluate(`document.querySelector('.role-resource-config .ant-alert-error')?.textContent.includes('所有修改仍保留')`), 'conflict preserves draft');
  assert.equal(await evaluate(`document.querySelector('${detailGrant}').checked`), true);
  assert.deepEqual(fixture.resourceSaves[0].changes.map(change => change.resourceCode).sort(),
    ['resource.001', 'resource.239', 'resource.operation.0', 'resource.operation.1']);
  assert.ok(fixture.resourceSaves[0].changes.every(change => change.revision === '1' && change.granted));
  await clickText('.role-resource-preview button', '取消');
  await clickText('.role-resource-footer button', '预览保存');
  await clickText('.role-resource-preview button', '确认提交');
  await poll(() => evaluate(`document.querySelector('.role-resource-footer')?.textContent.includes('待保存资源变更：0')`), 'delta save settled');
  assert.equal(fixture.resourceSaves.length, 2);
  assert.ok(fixture.resourceRequests.some(params => params.get('parentId') === 'resource-root' && params.get('page') === '11'));
  assert.ok(fixture.resourceRequests.some(params => params.get('rootsOnly') === 'true'));
  const remaining = fixture.resourceRows.filter(row => row.grantable && !row.granted).length;
  await clickReady(`${list} [data-resource-id="resource-root"] .role-resource-node-label`);
  await clickReady(detailGrant);
  assert.equal(fixture.resourceSaves.length, 2, 'directory checkbox stages changes without saving');
  await poll(() => evaluate(`document.querySelector('.role-resource-footer')?.textContent.includes('待保存资源变更：${remaining}')`),
    'all unloaded descendants staged');
  await clickText('.role-resource-footer button', '预览保存');
  await poll(() => evaluate(`document.querySelector('.role-resource-preview')?.textContent.includes('共 242 项授权')`),
    'flat preview includes all projected grants');
  await clickText('.role-resource-preview button', '确认提交');
  await poll(() => fixture.resourceSaves.length === 3, 'directory grant saved after preview');
  await poll(() => evaluate(`!!document.querySelector('.role-resource-detail .ant-empty')`), 'directory save refreshed the editor');
  assert.equal(fixture.resourceRows.filter(row => row.grantable && !row.granted).length, 0,
    'directory checkbox includes distant child pages and operation resources');
  await clickReady(`${list} [data-resource-id="resource-root"] .role-resource-node-label`);
  await clickReady(detailGrant);
  await poll(() => evaluate(`document.querySelector('.role-resource-footer')?.textContent.includes('待保存资源变更：242')`),
    'directory revoke stages every descendant');
  await clickText('.role-resource-footer button', '预览保存');
  await poll(() => evaluate(`document.querySelector('.role-resource-preview')?.textContent.includes('共 0 项授权')`),
    'revoke preview shows empty final grants');
  await clickText('.role-resource-preview button', '确认提交');
  await poll(() => fixture.resourceSaves.length === 4, 'directory revoke saved');
  assert.equal(fixture.resourceRows.filter(row => row.grantable && row.granted).length, 0);
  console.log('PASS: paged resource hierarchy, narrowed directory, staged full-subtree checkbox and flat confirmation preview');
}
