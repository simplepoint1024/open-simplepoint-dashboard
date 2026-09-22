import assert from 'node:assert/strict';
import {commonMainPages} from './common-main-fixture.mjs';

export async function smokeCommonMain({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  const root = '.nb-tab-panel-active';
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const queryDrawer = '.sp-query-drawer';
  const hasRow = (panel, name, index) => evaluate(`!!document.querySelector('${panel} tr[data-row-key="${name}-${String(index).padStart(3, '0')}"]')`);
  const headerAction = async (panel, dataset, selector) => {
    const title = dataset.schema.schema.properties[dataset.field].title;
    await evaluate(`(() => {const key=${JSON.stringify(title)}; const title=key.startsWith('i18n:')?window.spI18n.t(key.slice(5)):key;
      const th=[...document.querySelectorAll('${panel} th')].find(e=>e.textContent.includes(title));
      if (!th?.querySelector('${selector}')) throw Error('Missing query control for '+title); th.querySelector('${selector}').click();})()`);
  };
  const filter = async (panel, dataset, value) => {
    await poll(() => evaluate(`!document.querySelector('${dropdown}')`), 'old common main filter closed');
    await headerAction(panel, dataset, '.ant-table-filter-trigger');
    await poll(() => evaluate(`!!document.querySelector('${dropdown} input.ant-input')`), 'typed Common main header filter');
    await fill(`${dropdown} input.ant-input`, value);
    await clickText(`${dropdown} button`, '应用');
  };
  const exercise = async (panel, dataset, first, target) => {
    await poll(() => hasRow(panel, dataset.name, first), `${dataset.name} actual schema and first SQL page`);
    if (['Client', 'SystemNotification'].includes(dataset.name)) {
      const geometry = await evaluate(`(() => {const p=document.querySelector('${panel} .ant-pagination').getBoundingClientRect();const r=document.querySelector('${panel}').getBoundingClientRect();return {bottom:p.bottom, limit:r.bottom};})()`);
      assert.ok(geometry.bottom <= geometry.limit + 2, `${dataset.name} pagination stays inside the page: ${JSON.stringify(geometry)}`);
    }
    assert.equal(await evaluate(`document.querySelector('${panel} button[aria-label="筛选与视图"]')?.textContent.trim()`), '', 'query entry is icon-only');
    const lastPage = dataset.name === 'DictionaryItem' ? 6 : 12;
    await clickReady(`${panel} .ant-pagination-item-${lastPage}`);
    await poll(() => hasRow(panel, dataset.name, first + lastPage * 20 - 1), `${dataset.name} records beyond first 100`);
    for (let i=0; i<2; i++) await headerAction(panel, dataset, '.ant-table-column-sorters');
    await poll(() => evaluate(`document.querySelector('${panel} .ant-pagination-item-active')?.textContent==='1'`), `${dataset.name} sort resets pagination`);
    await filter(panel, dataset, `${dataset.field}-${String(target).padStart(3, '0')}`);
    await poll(() => hasRow(panel, dataset.name, target), `${dataset.name} off-page header search`);
    await clickReady(`${panel} button[aria-label="筛选与视图"]`);
    await clickText(`${queryDrawer} button`, '添加条件');
    const firstField = Object.keys(dataset.schema.schema['x-query'].fields)[0];
    await fill(`${queryDrawer} input.ant-input:not([placeholder*="视图名称"])`, `${firstField}-not-matching`);
    await clickText(`${queryDrawer} .ant-drawer-footer button`, '应用');
    await poll(() => evaluate(`document.querySelectorAll('${panel} tr[data-row-key]').length===0 && !document.querySelector('${panel} .ant-spin-spinning')`), `${dataset.name} advanced intersects header`);
    assert.ok(fixture.requests.some(request => request.name === dataset.name && request.params.has('$query')), 'actual advanced query sent');
    await clickReady(`${panel} button[aria-label="筛选与视图"]`);
    await clickText(`${queryDrawer} .ant-drawer-footer button`, '清空高级条件');
    await clickText(`${queryDrawer} .ant-drawer-footer button`, '应用');
    await poll(() => hasRow(panel, dataset.name, target), `${dataset.name} header retained`);
    await clickReady(`${panel} button[aria-label="筛选与视图"]`);
    await fill(`${queryDrawer} input[placeholder*="视图名称"]`, `${dataset.name} 视图`);
    await clickText(`${queryDrawer} button`, '保存当前视图');
    await poll(() => evaluate(`[...document.querySelectorAll('${queryDrawer} button')].some(e=>e.textContent==='${dataset.name} 视图')`), `${dataset.name} view saved`);
    await clickReady(`${queryDrawer} .ant-drawer-close`);
    await clickReady(`${panel} button[aria-label="清除全部筛选"]`);
    await poll(() => evaluate(`document.querySelectorAll('${panel} tr[data-row-key]').length===20 && !document.querySelector('${panel} .ant-spin-spinning')`), `${dataset.name} reset filters keeps scope`);
    await clickReady(`${panel} button[aria-label="筛选与视图"]`);
    await clickText(`${queryDrawer} button`, `${dataset.name} 视图`);
    await poll(() => hasRow(panel, dataset.name, target), `${dataset.name} restored view executes query`);
    assert.equal(await evaluate(`document.querySelectorAll('${panel} tr[data-row-key]').length`), 1);
    if (dataset.name.startsWith('PlatformAccount')) {
      await evaluate(`document.querySelector('${panel} .ant-pagination-options .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
      await poll(() => evaluate(`!!document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')`), 'bounded page-size selector');
      const choices = await evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].map(e=>parseInt(e.textContent,10))`);
      assert.deepEqual(choices, [10, 20, 50], 'platform account page sizes cannot exceed the server limit');
      await evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].find(e=>parseInt(e.textContent,10)===50).click()`);
      await poll(() => fixture.requests.some(request => request.name === dataset.name && request.params.get('size') === '50'), 'bounded page size sent to server');
    }
  };
  fixture.enable();
  // Reset query/schema caches: earlier tests deliberately supply different permissions for DataScope.
  await evaluate(`location.hash='#/smoke/common-main/Role'; location.reload()`);
  for (const spec of commonMainPages) {
    await clickMenu(`契约测试 ${spec.name}`);
    const dataset = fixture.datasets.find(value => value.name === spec.name);
    await exercise(root, dataset, 0, 239);
    if (spec.name.startsWith('Storage')) {
      fixture.failNext(dataset.base);
      await filter(root, dataset, `${dataset.field}-238`);
      await poll(() => evaluate(`!!document.querySelector('${root} .ant-alert-error')`), `${spec.name} failed query retains editor`);
      await clickText(`${root} .ant-alert button`, '重置筛选与排序');
      await poll(() => hasRow(root, dataset.name, 0), `${spec.name} reset recovers without stale filtered rows`);
      if (spec.name === 'StorageQuota') {
        Object.assign(dataset.rows[0], {usedBytes: '***', remainingBytes: '***'});
        await clickReady(`${root} button[aria-label="刷新"]`);
        await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key="StorageQuota-000"]')?.textContent.includes('***')`), 'masked storage byte counts render safely');
      }
    }
    if (spec.name === 'Client') {
      assert.equal(dataset.schema.schema.properties.clientSecret.writeOnly, true);
      await clickText(`${root} button`, '新增');
      const form = '.sp-simple-table-drawer.ant-drawer-open';
      await poll(() => evaluate(`!!document.querySelector('${form} input#root_clientSecret')`), 'write-only OAuth secret remains editable');
      assert.equal(await evaluate(`document.querySelector('${form} input#root_clientSecret').type`), 'password');
      assert.equal(await evaluate(`document.querySelector('${form} input#root_clientSecret').value`), '');
      await clickReady(`${form} .ant-drawer-close`);
      await poll(() => evaluate(`!document.querySelector('${form}')`), 'OAuth creation cancelled without domain writes');
    }
    if (spec.name === 'SystemNotification') {
      await clickReady(`${root} button[aria-label="清除全部筛选"]`);
      await poll(() => evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent.includes('240')`), 'notification filters reset');
      await headerAction(root, {...dataset, field: 'status'}, '.ant-table-filter-trigger');
      await poll(() => evaluate(`document.querySelectorAll('${dropdown} .ant-select').length===2`), 'notification lifecycle uses enum selector');
      await evaluate(`document.querySelectorAll('${dropdown} .ant-select')[1].dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
      await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '草稿');
      await clickText(`${dropdown} button`, '应用');
      await poll(() => evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent.includes('120')`), 'notification enum filter counts all pages');
      const statusRequest = fixture.requests.findLast(request => request.name === spec.name && request.params.has('status'));
      assert.deepEqual(JSON.parse(statusRequest.params.get('status')).where,
        {field: 'status', op: 'in', value: ['DRAFT']}, 'enum values are transported separately from their labels');
    }
    if (spec.name === 'PlatformAccount') {
      await poll(() => evaluate(`!document.querySelector('${root} .ant-spin-spinning')`), 'account page-size change settled');
      await clickReady(`${root} tr[data-row-key="PlatformAccount-239"] input[type="checkbox"]`);
      await poll(() => evaluate(`document.querySelectorAll('${root} .sp-table-button-group button')[2]?.disabled===false`), 'complete row permits authorized identity editor');
      const original = {...dataset.rows[239]};
      Object.assign(dataset.rows[239], {roles: '***', superAdmin: '***', revision: '***'});
      await clickReady(`${root} button[aria-label="刷新"]`);
      await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key="PlatformAccount-239"]')?.textContent.includes('***')`), 'masked roles render without crashing or inventing super-admin status');
      assert.equal(await evaluate(`!!document.querySelector('${root} tr[data-row-key="PlatformAccount-239"] .ant-tag-red')`), false);
      for (const position of [2, 3]) {
        assert.equal(await evaluate(`document.querySelectorAll('${root} .sp-table-button-group button')[${position - 1}]?.disabled`), true);
      }
      Object.assign(dataset.rows[239], original);
      fixture.failNext(dataset.base);
      await filter(root, dataset, 'name-238');
      await poll(() => evaluate(`!!document.querySelector('${root} .ant-alert-error')`), 'account failure retains query controls');
      await clickText(`${root} .ant-alert button`, '重置筛选与排序');
      await poll(() => hasRow(root, 'PlatformAccount', 0), 'account failed query reset recovers');
    }
    if (spec.name === 'Dictionary') {
      await clickReady(`${root} button[aria-label="清除全部筛选"]`);
      // Reverse the active descending order to default, then explicitly select parent A.
      await headerAction(root, dataset, '.ant-table-column-sorters');
      await poll(() => hasRow(root, 'Dictionary', 0), 'dictionary parent A visible');
      await clickReady(`${root} tr[data-row-key="Dictionary-000"] input[type="checkbox"]`);
      await clickText(`${root} button`, '配置字典项');
      const panel = '.ant-drawer-open:not(.sp-query-drawer)';
      const items = fixture.datasets.find(value => value.name === 'DictionaryItem');
      await exercise(panel, items, 0, 119);
      fixture.failNext(items.base);
      await filter(panel, items, 'name-118');
      await poll(() => evaluate(`!!document.querySelector('${panel} .ant-alert-error')`), 'scoped item query failure remains recoverable');
      await clickText(`${panel} .ant-alert button`, '重置筛选与排序');
      await poll(() => hasRow(panel, 'DictionaryItem', 0), 'error reset retains parent A');
      assert.ok(fixture.requests.filter(request => request.name === 'DictionaryItem').every(request => request.params.get('parentDictionaryCode') === 'code-000'));
      await clickReady(`${panel} .ant-drawer-close`);
      await clickReady(`${root} tr[data-row-key="Dictionary-000"] input[type="checkbox"]`);
      await clickReady(`${root} tr[data-row-key="Dictionary-001"] input[type="checkbox"]`);
      await clickText(`${root} button`, '配置字典项');
      await poll(() => hasRow(panel, 'DictionaryItem', 120), 'switching parent does not reuse other dictionary cached page');
      await clickReady(`${panel} button[aria-label="筛选与视图"]`);
      await clickText(`${queryDrawer} button`, 'DictionaryItem 视图');
      await poll(() => evaluate(`document.querySelectorAll('${panel} tr[data-row-key]').length===0 && !document.querySelector('${panel} .ant-spin-spinning')`), 'view from parent A cannot expose its records in parent B');
      await clickReady(`${panel} .ant-drawer-close`);
    }
    await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  }
  console.log('PASS: fourteen Common lists use real backend schemas; off-page header/advanced queries, sorting, views, bounded account pagination, storage SQL projections and fixed dictionary parent scopes');
}
