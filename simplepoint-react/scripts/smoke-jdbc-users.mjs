import assert from 'node:assert/strict';

export async function smokeJdbcUsers({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.enable();
  await clickMenu('JDBC 分配测试');
  const page = '.nb-tab-panel-active', drawer = '.ant-drawer-open', modal = '.ant-modal:not([style*="display: none"])';
  const left = `${drawer} .sp-assignment-panel[data-selected="false"]`;
  const right = `${drawer} .sp-assignment-panel[data-selected="true"]`;
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const filter = async (panel, value) => {
    await poll(() => evaluate(`!document.querySelector('${dropdown}')`), 'previous JDBC filter closed');
    await evaluate(`document.querySelector('${panel} .ant-table-filter-trigger').click()`);
    await poll(() => evaluate(`!!document.querySelector('${dropdown} input.ant-input')`), 'JDBC query header');
    await fill(`${dropdown} input.ant-input`, value);
    await clickText(`${dropdown} button`, '应用');
  };
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="Grant-Source-0"]')`), 'JDBC grants loaded');
  await clickText(`${page} button`, '新增');
  await poll(() => evaluate(`!!document.querySelector('${modal} .ant-select input')`), 'shared prefix user picker');
  await evaluate(`document.querySelector('${modal} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await fill(`${modal} .ant-select input`, 'me');
  await new Promise(resolve => setTimeout(resolve, 350));
  assert.equal(fixture.requests.some(request => request.path.endsWith('/user-options')), false,
    'short searches never enumerate the user directory');
  await fill(`${modal} .ant-select input`, 'member52');
  await poll(() => evaluate(`!!document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title*="member520@"]')`),
    'user beyond first 500 is reachable by bounded prefix search');
  await clickReady('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title*="member520@"]');
  await clickText(`${modal} .ant-modal-footer button`, '取消');
  assert.equal(fixture.writes.length, 0, 'picker cancellation has no mutation');
  await clickReady(`${page} tr[data-row-key="Grant-Source-0"] input[type="checkbox"]`);
  await clickText(`${page} button`, '编辑');
  await poll(() => evaluate(`!!document.querySelector('${left} tr[data-row-key="Source-260"]')`), 'independent available server page');
  await poll(() => evaluate(`!!document.querySelector('${right} tr[data-row-key="Source-0"]')`), 'assigned row key is the source ID, not grant ID');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .ant-card')`), false, 'uses the platform assignment layout');
  await clickReady(`${right} .ant-pagination-item-13`);
  await poll(() => evaluate(`document.querySelector('${right} .ant-pagination-item-active')?.textContent==='13'
    && document.querySelectorAll('${right} tr[data-row-key]').length===20 && !document.querySelector('${right} .ant-spin-spinning')`),
    'assigned records beyond 200 remain reachable');
  await filter(left, 'Source520');
  await poll(() => evaluate(`!!document.querySelector('${left} tr[data-row-key="Source-520"]')`), 'off-page source query beyond 500');
  await clickReady(`${left} tr[data-row-key="Source-520"] input[type="checkbox"]`);
  const authorize = await evaluate(`window.spI18n.t('dna.federation.jdbcUsers.page.button.authorize','授权')`);
  const unauthorize = await evaluate(`window.spI18n.t('dna.federation.jdbcUsers.page.button.unauthorize','取消授权')`);
  await evaluate(`(() => {const button=[...document.querySelectorAll('${left} button')].find(button=>button.textContent.replaceAll(' ','')===${JSON.stringify(authorize.replaceAll(' ', ''))});button.click();button.click();})()`);
  await poll(() => fixture.writes.length === 1, 'authorization starts once');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .ant-drawer-close')`), false, 'write prevents drawer close');
  await poll(() => fixture.grants.has('Source-520'), 'authorization committed');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0 && !document.querySelector('${right} .ant-spin-spinning')`), 'both sides refresh');
  await filter(right, 'Source520');
  await poll(() => evaluate(`!!document.querySelector('${right} tr[data-row-key="Source-520"]')`), 'new grant discoverable with assigned query');
  const permissionCheckboxes = `${right} tr[data-row-key="Source-520"] td:last-child input`;
  await clickReady(`${permissionCheckboxes}:nth-of-type(1)`);
  await poll(() => fixture.writes.length === 2, 'permission update submitted');
  await poll(() => evaluate(`!!document.querySelector('${drawer} .ant-drawer-close') && !document.querySelector('${right} .ant-spin-spinning')`), 'permission write and reconciliation settled');
  assert.deepEqual(fixture.writes[1].data, ['QUERY']);
  fixture.failWrite();
  await clickReady(`${right} tr[data-row-key="Source-520"] td:last-child input`);
  await poll(() => fixture.writes.length === 3, 'failed permission update submitted');
  await poll(() => evaluate(`document.body.textContent.includes('JDBC 写入失败（测试）')`), 'permission failure is visible');
  assert.deepEqual(fixture.grants.get('Source-520').operationPermissions, ['QUERY'], 'failure preserves server permissions');
  await poll(() => evaluate(`!document.querySelector('${right} tr[data-row-key="Source-520"] td:last-child input')?.disabled`), 'permission lock released after failure');
  assert.equal(await evaluate(`document.querySelector('${right} tr[data-row-key="Source-520"] td:last-child input').checked`), false);
  await clickReady(`${right} tr[data-row-key="Source-520"] td.ant-table-selection-column input`);
  await clickText(`${right} button`, unauthorize);
  await poll(() => !fixture.grants.has('Source-520'), 'unassignment committed');
  await poll(() => evaluate(`!!document.querySelector('${left} tr[data-row-key="Source-520"]')`), 'unassignment refreshes available pane');
  assert.equal(fixture.writes.length, 4, 'same-frame double click does not duplicate writes');
  assert.ok(fixture.requests.filter(request => request.path.endsWith('/items') || request.path.endsWith('/assigned')
    || request.path.endsWith('/user-options')).every(request => Number(request.params.get('size')) <= 20));
  assert.equal(fixture.requests.some(request => /\/(authorized|grants|selected-items)$/.test(request.path)), false,
    'does not fetch all assigned IDs, grants or selected details');
  await clickReady(`${drawer} .ant-drawer-close`);
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  console.log('PASS: JDBC bounded user picker, independently paged grants/sources, off-page queries, duplicate lock, permissions failure and two-way assignment refresh');
}
