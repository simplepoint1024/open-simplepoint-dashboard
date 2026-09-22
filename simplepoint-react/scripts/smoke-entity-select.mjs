import assert from 'node:assert/strict';
import {smokeRoleResources} from './smoke-role-resources.mjs';

export async function smokeEntitySelect({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture, common}) {
  fixture.enable();
  const popup = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const choose = async (id, keyword, label) => {
    await evaluate(`document.querySelector('#${id}').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await fill(`#${id}`, keyword);
    await poll(() => evaluate(`[...document.querySelectorAll('${popup} .ant-select-item-option')].some(element => element.textContent === ${JSON.stringify(label)})`), `server selector finds ${label}`);
    await clickText(`${popup} .ant-select-item-option`, label);
  };
  await clickMenu('契约测试 Role');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="Role-000"]')`), 'role selection row');
  await clickReady('.nb-tab-panel-active tr[data-row-key="Role-000"] input[type="checkbox"]');
  await clickText('.nb-tab-panel-active button', '配置资源');
  await smokeRoleResources({clickReady, clickText, fill, poll, evaluate, fixture});
  await clickReady('.role-resource-drawer .ant-drawer-close');
  await clickMenu('契约测试 StorageQuota');
  await clickText('.nb-tab-panel-active button', '新增配额');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal input#tenantId')`), 'quota tenant selector');
  await choose('tenantId', 'Tenant239', 'Tenant239');
  assert.ok(fixture.requests.some(params => params.has('name') && params.get('size') === '20'));
  await clickReady('.ant-modal .ant-modal-close');
  assert.equal(fixture.resourceSaves.length, 4, 'cancelling quota creation does not mutate domain data');
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  await clickMenu('契约测试 Role');
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  console.log('PASS: role resources and quota tenant selector remain functional');
}
