import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {formReferenceLocation} from '../../../simplepoint-react/libs/components/FormRuntime/referenceLocation.ts';

/** Enters the published page through normal navigation and saves using the inverse editor. */
export async function verifyRelationBrowser({browser, call, request, state, topology}) {
  const customer = state.forms.rel_customer;
  const trade = state.forms.rel_trade;
  const alpha = state.samples.customer_alpha;
  const route = '#/app/runtime/' + state.appId + '/' + customer.pageId;
  const root = '.nb-tab-panel-active';
  const detail = '.app-record-detail.ant-drawer-open';
  const editor = '.sp-simple-table-drawer.ant-drawer-open';
  const dialog = '.sp-form-runtime-mapping .ant-modal';
  const title = `浏览器关联验收 ${topology} ${randomUUID().slice(0, 8)}`;
  const evidence = {viewports: [], editorViewports: []};
  let browserRecordId;
  try {
    await browser(async ({send, evaluate, poll, click, fill, screenshot, writes}) => {
      await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
      await evaluate(`location.hash = ${JSON.stringify(route)}`);
      const search = `${root} .ant-pagination-next`;
      const row = `${root} [data-row-key="${alpha.id}"]`;
      await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(search)})`), 'published customer list');
      const parentName = editor + ' #form_main_name';
      const closeEditor = async () => {
        await evaluate(`document.querySelector('${editor} .ant-drawer-close').click()`);
        await click('.sp-simple-table-discard .ant-modal button', '放弃修改');
        await poll(() => evaluate(`!document.querySelector('${editor}')`), 'parent draft discarded');
      };
      await click(root + ' button', '新增');
      await fill(parentName, '未保存的关联父记录');
      assert.equal(await evaluate(`!!document.querySelector('${editor} .sp-table-root')`), false);
      await closeEditor();
      evidence.unsavedParentHasNoRelatedActions = true;
      if (!await evaluate(`!!document.querySelector(${JSON.stringify(row)})`)) {
        await evaluate(`document.querySelector(${JSON.stringify(search)}).click()`);
      }
      const selection = `${row} input[type="checkbox"]`;
      await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(selection)})`), 'synthetic customer');
      await evaluate(`document.querySelector(${JSON.stringify(selection)}).click()`);
      await click(root + ' button', '编辑');
      await fill(parentName, '尚未提交的客户改动');
      assert.equal(await evaluate(`!!document.querySelector('${editor} .sp-table-root') || !!document.querySelector('${detail}')`), false);
      await closeEditor();
      assert.equal((await call(customer.runtime + '/records/' + alpha.id)).values.name, alpha.values.name);
      assert(!writes.some(item => item.path === customer.runtime + '/records' && item.method === 'POST'
        || item.path === customer.runtime + '/records/' + alpha.id && item.method === 'PUT'));
      evidence.dirtyParentHadNoRelatedWriteAndWasNotAutosaved = true;
      await click(root + ' button', '详情');
      await poll(() => evaluate(`document.querySelector('${detail}')?.textContent.includes('客户交易')`), 'authorized inverse table');
      await click(detail + ' button', '新增');
      await poll(() => evaluate(`!!document.querySelector('${editor} #form_main_name')`), 'standard related create drawer');
      await poll(() => evaluate(`document.querySelector('${editor}')?.textContent.includes(${JSON.stringify(alpha.values.name)})`), 'fixed customer label resolved');
      await fill(editor + ' #form_main_name', title);
      await click(editor + ' button', '确认关联带出值');
      await poll(() => evaluate(`document.querySelector('${dialog}')?.textContent.includes('客户地址快照')`), 'shared mapping review');
      for (const width of [1440, 768, 390]) {
        await send('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: false});
        await poll(() => evaluate(`(() => { const box = document.querySelector('${dialog}')?.getBoundingClientRect(); return box?.width > 0 && box.left >= -1 && box.right <= innerWidth + 1; })()`), `mapping review at ${width}px`);
        evidence.viewports.push(width);
        await screenshot(`relation-mapping-${topology}-${width}`);
      }
      await click(dialog + ' button', '确认带入');
      await poll(() => evaluate(`document.querySelector('${editor} input#form_main_default_address')?.value === ${JSON.stringify(alpha.values.address)}`), 'confirmed default reflected in SForm');
      await click(editor + ' button', '保存草稿');
      const endpoint = `${customer.runtime}/records/${alpha.id}/relations/trades/records`;
      await poll(() => evaluate(`!document.querySelector('${editor}')`), 'related create saved');
      assert(writes.some(item => item.path === endpoint && item.method === 'POST' && item.body?.referenceMappings?.confirmations?.[0]?.relationId === 'customer'));
      const parent = new URLSearchParams({parentFormId: customer.formId, parentFormVersion: customer.version});
      const list = await call(`${endpoint}/query?${parent}`, 'POST', {page: 0, size: 50});
      browserRecordId = list.records.find(item => item.values.name === title)?.id;
      assert(browserRecordId);
      const added = await call(`${endpoint}/${browserRecordId}?${parent}`);
      assert.equal(added.values.customer, alpha.id);
      assert.equal(added.values.snapshot_address, alpha.values.address);
      assert.equal(added.values.default_address, alpha.values.address);
      await evaluate(`document.querySelector('${detail} .ant-drawer-close').click()`);
      await poll(() => evaluate(`!document.querySelector('${detail}')`), 'parent detail closed');
      assert.equal((await call(`${endpoint}/${browserRecordId}?${parent}`)).id, browserRecordId);
      evidence.relatedSaveSurvivesParentClose = true;

      await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
      const tradeRoute = `#/app/runtime/${state.appId}/${trade.pageId}`;
      await evaluate(`location.hash = ${JSON.stringify(tradeRoute)}`);
      const tradeRow = `${root} [data-row-key="${state.samples[topology + '_trade'].id}"]`;
      const tradeSelection = `${tradeRow} input[type="checkbox"]`;
      await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(tradeSelection)})`), 'trade row selection');
      await evaluate(`document.querySelector(${JSON.stringify(tradeSelection)}).click()`);
      await click(root + ' button', '编辑');
      const selector = editor + ' #form_main_customer[role="combobox"]';
      const detailTable = editor + ' [data-detail-id="items"]';
      for (const width of [1440, 768, 390]) {
        await send('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: false});
        await poll(() => evaluate(`(() => {
          const input = document.querySelector(${JSON.stringify(selector)});
          const detail = document.querySelector(${JSON.stringify(detailTable)});
          const box = detail?.getBoundingClientRect();
          const body = detail?.querySelector('.ant-table-body');
          return input && !input.disabled && box?.width > 0 && box.left >= -1 && box.right <= innerWidth + 1
            && body?.scrollHeight > body?.clientHeight && detail.querySelectorAll('tr[data-row-key]').length === 40;
        })()`), `reference selector and internally scrolling detail at ${width}px`);
        await evaluate(`document.querySelector(${JSON.stringify(selector)}).focus()`);
        await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
        await poll(() => evaluate(`document.querySelector(${JSON.stringify(selector)}).getAttribute('aria-expanded') === 'true'`), `keyboard opens reference selector at ${width}px`);
        await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await poll(() => evaluate(`document.querySelector(${JSON.stringify(selector)}).getAttribute('aria-expanded') === 'false'`), `keyboard closes reference selector at ${width}px`);
        evidence.editorViewports.push(width);
      }
      await evaluate(`document.querySelector('${editor} .ant-drawer-close').click()`);
      await poll(() => evaluate(`!document.querySelector('${editor}')`), 'standard trade editor closed');
      await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
      const relationAction = `(() => {
        const row = document.querySelector(${JSON.stringify(tradeRow)});
        const cell = [...(row?.cells ?? [])].find(item => item.textContent.includes(${JSON.stringify(alpha.values.name)}));
        return [...(cell?.querySelectorAll('button') ?? [])].find(button => button.textContent.trim() === '详情');
      })()`;
      await poll(() => evaluate(`!!${relationAction}`), 'record reference action');
      await evaluate(`${relationAction}.click()`);
      const targetDrawer = '.sp-reference-inspector.ant-drawer-open';
      await poll(() => evaluate(`document.querySelector('${targetDrawer}')?.textContent.includes(${JSON.stringify(alpha.values.name)})`), 'real target drawer');
      await evaluate(`document.querySelector('${targetDrawer} .ant-drawer-close').click()`);
      await poll(() => evaluate(`!document.querySelector('${targetDrawer}')`), 'return to source list');
      const deepLink = '#' + formReferenceLocation({applicationId: state.appId, pageId: trade.pageId,
        formId: trade.formId, formVersion: trade.version}, 'customer', alpha.id);
      await evaluate(`location.hash = ${JSON.stringify(deepLink)}`);
      const targetVisible = `location.hash === ${JSON.stringify(deepLink)} && !!document.querySelector('${root} #form_main_address') && document.querySelector('${root}')?.textContent.includes(${JSON.stringify(alpha.values.name)})`;
      await poll(() => evaluate(targetVisible), 'published reference deep link');
      await evaluate(`document.documentElement.setAttribute('data-relation-before-reload', 'true')`);
      await send('Page.reload', {ignoreCache: true});
      await poll(async () => {
        try {return await evaluate(`!document.documentElement.hasAttribute('data-relation-before-reload') && (${targetVisible})`);}
        catch {return false;}
      }, 'restored reference deep link');
      evidence.drillThrough = true;
      evidence.passed = true;
    });
  } finally {
    const tradeRecords = trade.runtime + '/records';
    if (!browserRecordId) {
      const result = await call(tradeRecords + '/query', 'POST', {page: 0, size: 50});
      browserRecordId = result.records.find(record => record.values.name === title)?.id;
    }
    if (browserRecordId) {
      const saved = await call(`${tradeRecords}/${browserRecordId}`);
      const query = new URLSearchParams({expectedVersion: saved.version, formId: trade.formId, formVersion: trade.version, idempotencyKey: randomUUID()});
      const deleted = await request(`${tradeRecords}/${saved.id}?${query}`, 'DELETE');
      assert([200, 204].includes(deleted.status), 'Expected browser sample cleanup');
      evidence.cleanedUp = true;
    }
  }
  return evidence;
}
