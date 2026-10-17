import assert from 'node:assert/strict';
import {smokeRoleResources} from './smoke-role-resources.mjs';

export async function smokeEntitySelect({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture, common, capturePreview}) {
  fixture.enable();
  // This scenario grants quota creation; discard an earlier query-only schema cache.
  await reload();
  const popup = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const choose = async (id, keyword, label) => {
    await evaluate(`document.querySelector('#${id}').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await fill(`#${id}`, keyword);
    await poll(() => evaluate(`[...document.querySelectorAll('${popup} .sp-tenant-name')].some(element => element.textContent === ${JSON.stringify(label)})`), `server selector finds ${label}`);
    await clickText(`${popup} .sp-tenant-name`, label);
  };
  await clickMenu('契约测试 Role');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="Role-000"]')`), 'role selection row');
  await clickReady('.nb-tab-panel-active tr[data-row-key="Role-000"] input[type="checkbox"]');
  await clickText('.nb-tab-panel-active button', '资源授权');
  await smokeRoleResources({clickReady, clickText, fill, poll, evaluate, fixture, capturePreview});
  await clickReady('.role-resource-drawer .ant-drawer-close');
  await clickMenu('契约测试 StorageQuota');
  await clickText('.nb-tab-panel-active button', '新增配额');
  await poll(() => evaluate(`!!document.querySelector('.sp-simple-table-drawer.ant-drawer-open input#root_tenantId')`), 'quota tenant selector');
  assert.equal(await evaluate(`document.querySelector('#root_tenantId').closest('[data-domain-select="tenant"]').dataset.directory`), 'platform');
  await choose('root_tenantId', 'Tenant239', 'Tenant239');
  assert.ok(fixture.requests.some(params => params.has('name') && params.get('size') === '20'));
  await clickReady('.sp-simple-table-drawer.ant-drawer-open .ant-drawer-close');
  await clickText('.ant-modal-footer button', '放弃修改');
  assert.equal(fixture.resourceSaves.length, 5, 'cancelling quota creation does not mutate domain data');
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  await clickMenu('契约测试 Role');
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  console.log('PASS: role resources and quota tenant selector remain functional');
}
