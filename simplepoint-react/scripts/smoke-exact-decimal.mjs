import assert from 'node:assert/strict';

/** Production RJSF, Table, QueryValue and default CRUD, using actual exported HTTP schemas. */
export async function smokeExactDecimal({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture, ai}) {
  fixture.enable(); ai.enable();
  const modelMax = '99999999999.99999999', packageMax = '99999999999999999.99';
  ai.models[0].inputTokenPrice = modelMax; ai.models[1].inputTokenPrice = '99999999999.99999998';
  ai.remote[0].pricing.inputTokenPrice = modelMax;
  const modelRoot = '.nb-tab-panel-active [data-resource="/ai/workbench/models"]';
  const packageRoot = '.nb-tab-panel-active [data-resource="/common/platform/packages"]';
  const form = '.sp-simple-table-drawer.ant-drawer-open', dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const settled = root => poll(() => evaluate(`!!document.querySelector(${JSON.stringify(root)})&&!document.querySelector(${JSON.stringify(root + ' .ant-spin-spinning')})`), 'decimal table settles');
  const button = async (root, text) => {
    await poll(() => evaluate(`[...document.querySelectorAll(${JSON.stringify(root + ' button')})].some(e=>e.textContent.replaceAll(' ','')===${JSON.stringify(text)}&&!e.disabled)`), 'decimal action ' + text);
    await clickText(root + ' button', text);
  };
  const edit = async (root, id, field) => {
    await settled(root); const selector = `${root} [data-row-key="${id}"] .ant-checkbox-input`;
    await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(selector)})`), 'exact decimal row');
    if (!await evaluate(`document.querySelector(${JSON.stringify(selector)}).checked`)) await clickReady(selector);
    await button(root, '编辑'); await poll(() => evaluate(`!!document.querySelector('${form} #root_${field}')`), 'real decimal form widget');
  };
  const save = async () => {await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'exact form save closes');};
  const invalid = async (field, value, message, writes) => {
    const before = writes.length; await fill(`${form} #root_${field}`, value); await clickReady(form + ' button[type="submit"]');
    await poll(() => evaluate(`document.querySelector('${form}')?.textContent.includes(${JSON.stringify(message)})&&!document.querySelector('${form} button[type="submit"]').disabled`), 'genuine decimal validation ' + value);
    assert.equal(writes.length, before, 'invalid decimal never reaches HTTP writer');
    assert.equal(await evaluate(`document.querySelector('${form} #root_${field}').value`), value, 'validation never clamps or rounds the draft');
  };
  const header = async (root, label, action) => {
    await poll(() => evaluate(`[...document.querySelectorAll(${JSON.stringify(root + ' th')})].some(e=>e.textContent.includes(${JSON.stringify(label)})&&e.querySelector(${JSON.stringify(action)}))`), 'translated decimal query header');
    await evaluate(`[...document.querySelectorAll(${JSON.stringify(root + ' th')})].find(e=>e.textContent.includes(${JSON.stringify(label)})).querySelector(${JSON.stringify(action)}).click()`);
  };
  await clickMenu('模型管理'); await edit(modelRoot, ai.models[0].id, 'inputTokenPrice');
  assert.equal(await evaluate(`document.querySelector('${form} #root_inputTokenPrice').type`), 'text');
  assert.equal(await evaluate(`document.querySelector('${form} #root_inputTokenPrice').value`), modelMax);
  await fill(form + ' #root_description', 'Exact price metadata only'); await save();
  assert.equal(ai.writes.at(-1).data.inputTokenPrice, modelMax); assert.equal(ai.writes.at(-1).data.description, 'Exact price metadata only');
  assert.equal(ai.models[0].pricingAutoDetected, true, 'unrelated edit does not invent manual repricing');
  await edit(modelRoot, ai.models[0].id, 'inputTokenPrice');
  await invalid('inputTokenPrice', '100000000000', modelMax, ai.writes);
  await invalid('inputTokenPrice', '0.000000001', '8 位小数', ai.writes);
  await invalid('inputTokenPrice', '-0.00000001', '大于等于 0', ai.writes);
  await fill(form + ' #root_inputTokenPrice', ''); await fill(form + ' #root_description', 'Explicit nullable price'); await save();
  assert.equal(ai.writes.at(-1).data.inputTokenPrice, null);
  await edit(modelRoot, ai.models[0].id, 'inputTokenPrice'); await fill(form + ' #root_inputTokenPrice', modelMax); await save();
  await header(modelRoot, '输入价格', '.ant-table-filter-trigger');
  await poll(() => evaluate(`!!document.querySelector('${dropdown} .ant-input-number-input')`), 'decimal header remains numeric');
  await fill(dropdown + ' .ant-input-number-input', modelMax); await button(dropdown, '应用');
  await settled(modelRoot); await poll(() => evaluate(`document.querySelectorAll('${modelRoot} tr[data-row-key]').length===1`), 'exact decimal equality differs from adjacent rounded value');
  const filter = JSON.parse(ai.reads.findLast(read => read.kind === 'list' && read.resource === 'models').params.get('inputTokenPrice'));
  assert.deepEqual(filter.where, {field: 'inputTokenPrice', op: 'equals', value: modelMax});
  assert.equal(await evaluate(`document.querySelector('${modelRoot} tr[data-row-key]').getAttribute('data-row-key')`), ai.models[0].id);

  await clickMenu('AI 接入配置测试');
  const providers = '.nb-tab-panel-active [data-resource="/ai/workbench/providers"]', discovery = '.ai-provider-operation .ant-modal';
  await settled(providers); await clickReady(`${providers} [data-row-key="${ai.providers[0].id}"] .ant-checkbox-input`);
  await button(providers, '查看模型');
  await poll(() => evaluate(`document.querySelector('${discovery} [data-row-key="remote-000"]')?.textContent.includes(${JSON.stringify(modelMax)})`), 'discovered decimal renders exact wire string');
  await header(discovery, '输入价格', '.ant-table-filter-trigger');
  await poll(() => evaluate(`!!document.querySelector('${dropdown} .ant-input-number-input')`), 'discovery snapshot keeps numeric decimal filter');
  await fill(dropdown + ' .ant-input-number-input', modelMax); await button(dropdown, '应用');
  await poll(() => evaluate(`document.querySelectorAll('${discovery} tr[data-row-key]').length===1`), 'local discovery decimal equality across full bounded snapshot');
  assert.equal(await evaluate(`document.querySelector('${discovery} tr[data-row-key]').getAttribute('data-row-key')`), 'remote-000');
  await button(discovery, '关闭'); await poll(() => evaluate(`!document.querySelector('${discovery}')`), 'discovery result closes');

  await clickMenu('精确金额测试'); await edit(packageRoot, fixture.rows[0].id, 'price');
  assert.equal(await evaluate(`document.querySelector('${form} #root_price').value`), packageMax);
  await fill(form + ' #root_description', 'Exact package metadata only'); await save();
  assert.equal(fixture.writes.at(-1).data.price, packageMax); assert.equal(fixture.rows[0].price, packageMax);
  await edit(packageRoot, fixture.rows[0].id, 'price'); await invalid('price', '0.001', '2 位小数', fixture.writes);
  await invalid('price', '100000000000000000', packageMax, fixture.writes);
  await fill(form + ' #root_price', '0'); await save(); assert.equal(fixture.writes.at(-1).data.price, '0');
  await edit(packageRoot, fixture.rows[0].id, 'price'); await fill(form + ' #root_price', packageMax); await save();
  await header(packageRoot, '价格', '.ant-table-column-sorters'); await settled(packageRoot);
  await poll(() => fixture.reads.at(-1).params.getAll('sort').includes('price,asc'), 'server decimal sort requested');
  try {await poll(() => evaluate(`(()=>{const keys=[...document.querySelectorAll('${packageRoot} tr[data-row-key]')].map(e=>e.getAttribute('data-row-key'));
    return keys.length===4&&keys.indexOf('exact-package-1')<keys.indexOf('exact-package-0')&&!document.querySelector('${packageRoot} .ant-spin-spinning');})()`), 'sorted decimal response actually rendered');}
  catch (error) {console.error('Exact sort diagnostics', {prices: fixture.rows.map(row => [row.id, row.price]), reads: fixture.reads.slice(-3).map(read => read.params.toString()),
    rendered: await evaluate(`document.querySelector('${packageRoot}')?.innerText`)}); throw error;}
  const sorted = await evaluate(`[...document.querySelectorAll('${packageRoot} tr[data-row-key]')].map(e=>e.getAttribute('data-row-key'))`);
  assert.ok(sorted.indexOf('exact-package-1') < sorted.indexOf('exact-package-0'), 'adjacent high decimal prices sort numerically');
  await clickReady(packageRoot + ' button[aria-label="筛选与视图"]');
  const query = '.sp-query-drawer'; await button(query, '添加条件');
  await evaluate(`document.querySelector('${query} .ant-space-compact .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  const popup = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  await poll(() => evaluate(`[...document.querySelectorAll('${popup} .ant-select-item-option')].some(e=>e.textContent==='价格')`), 'real advanced decimal field');
  await clickText(popup + ' .ant-select-item-option', '价格');
  await poll(() => evaluate(`!!document.querySelector('${query} .ant-input-number-input')`), 'advanced decimal numeric widget');
  await fill(query + ' .ant-input-number-input', packageMax); await button(query + ' .ant-drawer-footer', '应用');
  await poll(() => evaluate(`document.querySelectorAll('${packageRoot} tr[data-row-key]').length===1&&!document.querySelector('${packageRoot} .ant-spin-spinning')`), 'exact advanced decimal equality');
  const advanced = JSON.parse(fixture.reads.at(-1).params.get('$query')); assert.equal(advanced.where.children[0].value, packageMax);
  assert.equal(typeof advanced.where.children[0].value, 'string');
  await clickReady(packageRoot + ' button[aria-label="清除全部筛选"]'); await settled(packageRoot);
  fixture.mode('masked');
  await evaluate(`localStorage.setItem('sp.contextId:__platform__','exact-decimal-masked');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'exact-decimal-masked'}}));`);
  await edit(packageRoot, fixture.rows[0].id, 'price');
  assert.equal(await evaluate(`document.querySelector('${form} #root_price').value`), '***');
  await fill(form + ' #root_description', 'Masked exact metadata'); await save();
  assert.equal('price' in fixture.writes.at(-1).data, false, 'genuine default writer omits masked decimal before HTTP decoding');
  assert.equal(fixture.rows[0].price, packageMax);
  console.log('PASS: exact model/package metadata saves, native nullable decimal widget, unclamped engine validation, header/advanced exact numeric queries, adjacent sorting and protected default CRUD');
}
