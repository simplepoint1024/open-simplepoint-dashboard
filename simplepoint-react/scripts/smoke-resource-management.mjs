import assert from 'node:assert/strict';

export async function smokeResourceManagement({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.enable(); await clickMenu('资源管理契约测试');
  const root = '.nb-tab-panel-active [data-resource="/common/resources"]';
  const row = id => root + ' tr[data-row-key="' + id + '"]';
  await poll(() => evaluate(`!!document.querySelector('${row('management-000')}')`), 'native management root');
  await clickReady(root + ' .ant-pagination-item-27');
  await poll(() => evaluate(`!!document.querySelector('${row('management-520')}')`), 'native management >500 tail page');
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  await evaluate(`(()=>{const title=window.spI18n.t('resources.title.name');const th=[...document.querySelectorAll('${root} th')].find(element=>element.textContent.includes(title));th.querySelector('.ant-table-filter-trigger').click();})()`);
  await poll(() => evaluate(`!!document.querySelector('${dropdown} input.ant-input')`), 'standard management header filter');
  await fill(dropdown + ' input.ant-input', 'Management520'); await clickText(dropdown + ' button', '应用');
  await poll(() => fixture.reads.some(read => read.params.has('name')) && evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent.includes('1')`), 'native filtered count');
  await clickReady(root + ' button[aria-label="清除全部筛选"]');
  await poll(() => evaluate(`!!document.querySelector('${row('management-000')}')`), 'native management filter reset');
  await clickReady(row('management-000') + ' input[type="checkbox"]'); await clickText(root + ' .sp-table-toolbar-main button', '编辑');
  const drawer = '.sp-simple-table-drawer.ant-drawer-open';
  await poll(() => evaluate(`!!document.querySelector('${drawer} #root_description')`), 'actual Resource production SForm');
  assert.equal(await evaluate(`document.querySelector('${drawer} #root_description').value`), 'Actual description 000');
  assert.equal(await evaluate(`document.querySelector('${drawer} input#root_name').value`), 'Management000');
  await clickReady(drawer + ' .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'management edit cancelled without a write');
  assert.equal(fixture.reads.filter(read => read.path.endsWith('/schema')).length, 1, 'metadata and query transitions retain the same schema');
  console.log('PASS: actual Resource SimpleTable/SForm, native 521-root first/tail/filter contracts and preserved editable metadata');
}
