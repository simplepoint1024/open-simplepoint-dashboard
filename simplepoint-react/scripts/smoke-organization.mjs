import assert from 'node:assert/strict';

export async function smokeOrganization({clickMenu, clickReady, clickText, fill, poll, evaluate, requests}) {
  await clickMenu('组织树测试');
  const root = '.nb-tab-panel-active';
  const first = `${root} tr[data-row-key="root-0"]`;
  const childCount = () => evaluate(`document.querySelectorAll('${root} tr[data-row-key^="child-"]').length`);
  await poll(() => evaluate(`!!document.querySelector('${first}')`), 'organization roots');
  assert.equal(await evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent`), '共 120 条');
  assert.equal(requests.some(r => r.url.includes('treeParentId=')), false, 'children load only on expansion');
  await clickReady(`${first} td:nth-child(2)`);
  await poll(() => evaluate(`document.querySelector('.organization-members-panel')?.textContent.includes('root-000')
    && !!document.querySelector('.organization-members-panel tr[data-row-key="org-user-0"]')`),
  'organization user panel loads the selected parent');
  assert.equal(await evaluate(`document.querySelector('.organization-members-panel .ant-pagination-total-text')?.textContent`),
    '共 125 条', 'organization users are server paginated');
  await clickReady('.organization-members-panel .ant-pagination-item-2');
  await poll(() => evaluate(`!!document.querySelector('.organization-members-panel tr[data-row-key="org-user-20"]')`),
    'organization user page two is reachable');
  await clickText('.organization-members-panel button', '添加租户用户');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal tr[data-row-key="member-0"]')`),
    'candidate picker searches existing tenant users');
  await clickReady('.ant-modal .ant-modal-close');
  await clickReady('.organization-members-panel tr[data-row-key="org-user-20"] td:nth-child(2)');
  await clickText('.organization-members-panel button', '移出组织');
  await poll(() => evaluate(`document.querySelector('.organization-member-removal .ant-modal')?.textContent.includes('保留租户成员身份')`),
    'organization removal explains its boundary');
  await clickText('.organization-member-removal .ant-modal-footer button', '取消');
  await poll(() => evaluate(`!document.querySelector('.organization-member-removal .ant-modal')`), 'cancelled confirmation is removed');
  assert.equal(requests.some(r => r.method === 'DELETE' && r.url.includes('/tenant-members/by-organization/')), false);
  await clickText('.organization-members-panel button', '移出组织');
  await poll(() => evaluate(`!!document.querySelector('.organization-member-removal .ant-modal .ant-btn-dangerous')`), 'new removal confirmation');
  await clickReady('.organization-member-removal .ant-modal .ant-btn-dangerous');
  await poll(() => evaluate(`!document.querySelector('.organization-members-panel tr[data-row-key="org-user-20"]')
    && document.querySelector('.organization-members-panel .ant-pagination-total-text')?.textContent === '共 124 条'`),
    'confirmed removal refreshes the counted member page');
  assert(requests.some(r => r.method === 'DELETE' && r.url.includes('/tenant-members/by-organization/org-user-20')));
  assert(!requests.some(r => r.method === 'DELETE' && /\/tenant-members\/org-user-/.test(r.url)),
    'organization removal never calls tenant membership deletion');

  await clickReady(`${first} td:nth-child(2)`);
  await poll(() => evaluate(`document.querySelector('.organization-members-panel')?.textContent.includes('请从左侧选择组织')`),
    'clicking the selected organization deselects it');
  await clickReady(`${first} .ant-table-row-expand-icon`);
  await poll(async () => await childCount() === 50, 'first bounded child page');
  await clickReady(`${root} button[aria-label="表格设置"]`);
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-drawer-open label.ant-checkbox-wrapper')].some(e=>e.parentElement.textContent.includes('组织名称'))`), 'organization column settings');
  await evaluate(`[...document.querySelectorAll('.ant-drawer-open label.ant-checkbox-wrapper')].find(e=>e.parentElement.textContent.includes('组织名称')).querySelector('input').click()`);
  await clickText('.ant-drawer-open .ant-drawer-footer button', '保存');
  await poll(() => evaluate(`![...document.querySelectorAll('${root} th')].some(e=>e.textContent.includes('组织名称'))`), 'name column hidden');
  await clickText(`${first} button`, '加载更多');
  await poll(() => evaluate(`document.querySelector('${first}')?.textContent.includes('子节点加载失败')`), 'child page failure is inline');
  assert.equal(await childCount(), 50, 'failure retains previously loaded children');
  await clickText(`${first} button`, '重试');
  await poll(async () => await childCount() === 100, 'same child page retries');
  await clickText(`${first} button`, '加载更多');
  await poll(async () => await childCount() === 125, 'all child pages reachable beyond 100');
  assert.equal(await evaluate(`[...document.querySelectorAll('${first} button')].some(e=>e.textContent.includes('加载更多'))`), false, 'no more control after last child page');
  await clickMenu('套餐包管理');
  await clickMenu('组织树测试');
  await poll(async () => await childCount() === 125, 'expanded children survive inactive tab');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer input.ant-input:not([placeholder*="视图名称"])', 'child-124');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===1 && !!document.querySelector('${root} tr[data-row-key="child-124"]')`), 'advanced search is across hierarchy, not just loaded root page');
  assert.equal(await evaluate(`!!document.querySelector('${root} .ant-table-row-expand-icon')`), false, 'flat search does not append nonmatching children');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', '组织查询视图');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => evaluate(`[...document.querySelectorAll('.sp-query-drawer button')].some(e=>e.textContent==='组织查询视图')`), 'organization view saved');
  await clickReady('.sp-query-drawer .ant-drawer-close');
  await clickReady(`${root} button[aria-label="清除全部筛选"]`);
  await poll(() => evaluate(`!!document.querySelector('${first}')`), 'reset returns to counted root tree');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '组织查询视图');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===1 && !!document.querySelector('${root} tr[data-row-key="child-124"]')`), 'saved view restores actual hierarchy-wide query');
  assert.ok(requests.filter(r => r.url.includes('/organizations/tree?')).every(r => Number(new URL(r.url, 'http://fixture').searchParams.get('size')) <= 100));
  assert.equal(requests.some(r => r.url.includes('/organizations/options')), false, 'management avoids count-free selector hydration');
  console.log('PASS: counted organization roots, lazy child pages beyond 100, failure/retry, hierarchy-wide advanced search and saved view restore');
}
