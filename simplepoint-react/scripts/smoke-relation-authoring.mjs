import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {createRsbuild} from '@rsbuild/core';
import {pluginReact} from '@rsbuild/plugin-react';

const root = fileURLToPath(new URL('..', import.meta.url));
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-relation-authoring-'));
let server, browser, socket;
const errors = [];
let diagnostic = async () => ({errors});
const poll = async (read, description) => {
  const until = Date.now() + 30000;
  while (Date.now() < until) {const result = await read(); if (result) return result; await delay(30);}
  console.error(JSON.stringify(await diagnostic()));
  throw Error(`Timed out: ${description}`);
};
try {
  const rsbuild = await createRsbuild({cwd: root, rsbuildConfig: {plugins: [pluginReact()],
    source: {entry: {index: path.join(root, 'scripts/relation-authoring-harness.tsx')}},
    output: {distPath: {root: path.join(temporary, 'dist')}},
    dev: {hmr: false, liveReload: false}, server: {host: '127.0.0.1', port: 0, open: false}}});
  server = await rsbuild.startDevServer();
  browser = spawn(process.env.CHROME_BIN ?? '/usr/bin/google-chrome-stable', [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-extensions', '--disable-background-networking',
    '--no-first-run', `--user-data-dir=${temporary}/profile`, '--remote-debugging-port=0', 'about:blank',
  ], {stdio: 'ignore'});
  const port = await poll(async () => {
    try {return (await readFile(`${temporary}/profile/DevToolsActivePort`, 'utf8')).split('\n')[0];} catch {return undefined;}
  }, 'browser startup');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', ({data}) => {
    const message = JSON.parse(data);
    if (message.id) {
      const waiter = pending.get(message.id); pending.delete(message.id);
      if (message.error) waiter.reject(Error(JSON.stringify(message.error))); else waiter.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, {resolve, reject}); socket.send(JSON.stringify({id, method, params}));
  });
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
  await send('Page.navigate', {url: `http://127.0.0.1:${server.server.httpServer.address().port}/`});

  diagnostic = async () => {
    const result = await evaluate(`({text:document.body.innerText, selects:Array.from(document.querySelectorAll('.sp-form-relation-modal .ant-select')).map(node=>({text:node.innerText, rect:node.getBoundingClientRect().toJSON()})), errors:[]})`);
    if (process.env.RELATION_MAPPING_SCREENSHOT) {
      const screenshot = await send('Page.captureScreenshot', {format:'png'});
      await writeFile(process.env.RELATION_MAPPING_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
    }
    return {...result, errors};
  };
  const fixture = expression => evaluate(`window.relationAuthoring.${expression}`);
  const visible = selector => evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(selector)})).filter(node => node.getClientRects().length).length`);
  const click = async (selector, text) => {
    await poll(() => evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
      .some(node => node.getClientRects().length && ${text ? `node.textContent.trim() === ${JSON.stringify(text)}` : 'true'})`), `visible ${selector}: ${text ?? ''}`);
    return evaluate(`(() => {
    const node = Array.from(document.querySelectorAll(${JSON.stringify(selector)})).find(node => node.getClientRects().length && ${text ? `node.textContent.trim() === ${JSON.stringify(text)}` : 'true'});
    if (!node) throw Error('Missing visible target: ' + ${JSON.stringify(selector)} + ' ' + ${JSON.stringify(text)});
    node.click();
  })()`);
  };
  const modalText = () => evaluate(`document.querySelector('.sp-form-relation-modal .ant-modal-body')?.innerText ?? ''`);
  const openSelect = async selector => {
    await poll(() => visible(selector), `visible selector ${selector}`);
    await poll(() => evaluate(`Array.from(document.querySelectorAll('.ant-modal, .ant-drawer, .ant-select-dropdown'))
      .every(node => node.getAnimations({subtree:true}).every(animation => animation.playState !== 'running'))`), 'modal transition completed');
    await evaluate(`(() => {
      const element = Array.from(document.querySelectorAll(${JSON.stringify(selector)})).find(node => node.getClientRects().length).closest('.ant-select');
      element.scrollIntoView({block:'center', inline:'center'});
    })()`);
    const point = await poll(() => evaluate(`(() => {
      const element = Array.from(document.querySelectorAll(${JSON.stringify(selector)})).find(node => node.getClientRects().length)?.closest('.ant-select');
      if (!element) return null;
      const rect = element.getBoundingClientRect();
      const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
      return element.contains(document.elementFromPoint(x, y)) ? {x, y} : null;
    })()`), 'select hit target is reachable');
    await send('Input.dispatchMouseEvent', {type:'mouseMoved', ...point});
    await send('Input.dispatchMouseEvent', {type:'mousePressed', ...point, button:'left', clickCount:1});
    await send('Input.dispatchMouseEvent', {type:'mouseReleased', ...point, button:'left', clickCount:1});
  };
  const openRoot = async () => {
    await click('[data-design-node="customer-input"]');
    await poll(() => evaluate(`Array.from(document.querySelectorAll('.sp-form-designer-properties [role="tab"]')).some(node => node.textContent.trim() === '关联配置')`), 'reference property tab');
    await click('.sp-form-designer-properties [role="tab"]', '关联配置');
    await poll(() => evaluate(`Array.from(document.querySelectorAll('.sp-form-designer-properties button')).some(node => node.getClientRects().length && node.textContent.trim() === '配置关联')`), 'reference property command');
    await click('.sp-form-designer-properties button', '配置关联');
    await poll(() => visible('.sp-form-relation-modal .ant-modal'), 'relation editor');
  };
  const ready = async suffix => {
    await poll(async () => (await fixture('reads()')).length > completed, 'target metadata request');
    const index = (await fixture('reads()')).length - 1;
    await fixture(`finish(${index}, ${JSON.stringify(suffix ?? '')})`);
    completed = index + 1;
    await poll(() => evaluate(`Array.from(document.querySelectorAll('.sp-form-relation-modal .ant-modal-footer button')).some(node => node.textContent.trim() === '应用修改' && !node.disabled)`), 'metadata enables commit');
    return index;
  };
  let completed = 0;
  await poll(() => visible('[data-design-node="customer-input"]'), 'real designer canvas');
  await openRoot();
  await ready();
  if (process.env.RELATION_AUTHORING_SCREENSHOT) {
    await poll(() => evaluate(`document.getAnimations().every(animation => animation.playState !== 'running')`), 'stable screenshot');
    const screenshot = await send('Page.captureScreenshot', {format:'png'});
    await writeFile(process.env.RELATION_AUTHORING_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
  }
  assert.equal(await fixture('commits()'), 0, 'loading config must never dirty the document');
  await click('.sp-form-relation-modal [role="tab"]', '候选筛选');
  await poll(() => visible('#relation_candidate_enabled'), 'candidate filter switch');
  await click('#relation_candidate_enabled');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await modalText()).includes('请检查目标资源'), 'empty candidate condition rejected');
  assert.equal(await fixture('commits()'), 0);
  await click('#relation_candidate_enabled');
  await click('.sp-form-relation-modal [role="tab"]', '关联校验');
  await poll(() => visible('#relation_qualification_enabled'), 'qualification switch');
  await click('#relation_qualification_enabled');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await modalText()).includes('请检查目标资源'), 'empty qualification rejected independently');
  await click('#relation_qualification_enabled');
  await click('.sp-form-relation-modal [role="tab"]', '关联配置');
  await click('#relation_settings_allowDrillThrough');
  await click('.sp-form-relation-modal .ant-modal-footer button', '取消');
  await poll(() => visible('.sp-form-data-discard .ant-modal'), 'dirty relation asks before discard');
  await click('.sp-form-data-discard button', '放弃修改');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'cancel closes editor');
  assert.equal(await fixture('commits()'), 0);
  assert.equal((await fixture('document()')).relations[0].referenceView.allowDrillThrough, false);

  await openRoot(); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '候选筛选');
  await poll(() => visible('#relation_candidate_enabled'), 'candidate configuration');
  await click('#relation_candidate_enabled');
  await click('.sp-form-relation-modal .ant-tabs-content-active button', '添加条件');
  await poll(() => visible('.sp-form-relation-modal .ant-tabs-content-active textarea'), 'candidate literal editor');
  await evaluate(`document.querySelector('.sp-form-relation-modal .ant-tabs-content-active textarea').focus()`);
  await send('Input.insertText', {text:'可用客户・日本語😀'});
  await click('.sp-form-relation-modal [role="tab"]', '关联校验');
  await poll(() => visible('#relation_qualification_enabled'), 'eligibility configuration');
  await click('#relation_qualification_enabled');
  await click('.sp-form-relation-modal .ant-tabs-content-active button', '添加条件');
  await openSelect('.sp-form-relation-modal .ant-tabs-content-active [data-condition-field] .ant-select');
  await poll(() => visible('.ant-select-item-option'), 'target eligibility fields');
  await click('.ant-select-item-option', '启用');
  await poll(() => visible('.sp-form-relation-modal [data-condition-field="active"] > .ant-select'), 'typed boolean eligibility');
  await openSelect('.sp-form-relation-modal [data-condition-field="active"] > .ant-select');
  await click('.ant-select-item-option', '是');
  await openSelect('#relation_qualification_recheck');
  await click('.ant-select-item-option', '每次保存或提交时');
  await click('.sp-form-relation-modal [role="tab"]', '关联配置');
  await click('#relation_settings_allowDrillThrough');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => await fixture('commits()') === 1, 'one immutable command commits relation');
  assert.equal((await fixture('document()')).relations[0].referenceView.allowDrillThrough, true);
  assert.deepEqual((await fixture('document()')).relations[0].qualification, {recheck:'ALWAYS', condition:{operator:'AND',values:[],children:[
    {operator:'EQUALS',fieldId:'active',values:[{kind:'BOOLEAN',value:'true'}],children:[]},
  ]}});
  assert.equal((await fixture('document()')).relations[0].referenceView.candidateFilter.children[0].values[0].value, '可用客户・日本語😀');
  await evaluate(`document.querySelector('[data-design-node="customer-input"]').dispatchEvent(new KeyboardEvent('keydown', {key:'z',ctrlKey:true,bubbles:true}))`);
  await poll(async () => (await fixture('document()')).relations[0].referenceView.allowDrillThrough === false, 'designer undo restores relation');
  await evaluate(`document.querySelector('[data-design-node="customer-input"]').dispatchEvent(new KeyboardEvent('keydown', {key:'z',ctrlKey:true,shiftKey:true,bubbles:true}))`);
  await poll(async () => (await fixture('document()')).relations[0].referenceView.allowDrillThrough === true, 'designer redo restores relation');

  await openRoot();
  await poll(async () => (await fixture('reads()')).length > completed, 'pending read before owner suspension');
  let pendingIndex = (await fixture('reads()')).length - 1;
  await fixture('set({selected:false})');
  await poll(async () => (await fixture('reads()'))[pendingIndex].aborted, 'inactive owner aborts metadata');
  await fixture(`finish(${pendingIndex}, 'LATE-SECRET')`);
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'inactive owner hides modal after closing animation');
  completed = pendingIndex + 1;
  await fixture('set({selected:true})'); await ready();
  assert(!(await modalText()).includes('LATE-SECRET'));
  await fixture('rename()');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await modalText()).includes('该定义已发生变化'), 'stale field edit rejected');
  await click('.sp-form-relation-modal .ant-modal-footer button', '取消');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'unchanged stale dialog closes');

  await openRoot();
  await poll(async () => (await fixture('reads()')).length > completed, 'failed metadata request');
  pendingIndex = (await fixture('reads()')).length - 1; completed = pendingIndex + 1;
  await fixture(`fail(${pendingIndex})`);
  await poll(async () => (await modalText()).includes('目标资源定义加载失败'), 'visible metadata error');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('.sp-form-relation-modal .ant-modal-footer button')).find(node => node.textContent.trim() === '应用修改').disabled`), true);
  await click('.sp-form-relation-modal .ant-alert button', '重试');
  await ready();
  await click('.sp-form-relation-modal .ant-modal-footer button', '取消');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'metadata dialog closes');

  await click('[data-design-node="detail-input"]');
  await poll(() => evaluate(`Array.from(document.querySelectorAll('.sp-form-designer-properties [role="tab"]')).some(node => node.textContent.trim() === '字段')`), 'detail field property tab');
  await click('.sp-form-designer-properties [role="tab"]', '字段');
  await poll(() => evaluate(`Array.from(document.querySelectorAll('.sp-form-designer-properties button')).some(node => node.getClientRects().length && node.textContent.trim() === '配置关联')`), 'detail relation action');
  await click('.sp-form-designer-properties button', '配置关联');
  await poll(() => visible('.sp-form-relation-modal .ant-modal'), 'detail field opens shared editor');
  assert.equal((await fixture('document()')).details[0].fields[0].storageMode, 'STORED');
  // Use the actual shared EntitySelect to select a target, never inject a relation through the fixture.
  await openSelect('#relation_target');
  await poll(() => visible('.ant-select-item-option'), 'authorized target options');
  await click('.ant-select-item-option', 'CRM / 商品');
  await ready();
  await openSelect('#relation_settings_labelFieldId');
  await poll(() => visible('.ant-select-item-option'), 'authorized label fields');
  await click('.ant-select-item-option', '名称');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await fixture('document()')).relations.length === 2, 'owned detail relation commits atomically');
  const saved = await fixture('document()');
  assert.equal(saved.details[0].fields[0].storageMode, 'REFERENCE');
  assert.deepEqual(saved.relations[1].sourceField, {fieldId:'product',detailId:'lines'});
  assert.equal(saved.relations[1].target.formId, 'products');
  assert.equal(saved.relations[0].sourceField.fieldId, 'customer');

  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'detail relation editor closed');
  await click('.sp-form-designer-properties button', '新增临时字段');
  await poll(() => visible('.sp-form-data-modal .ant-modal'), 'temporary field uses shared field editor');
  assert.equal(await visible('#data_field_storageCode'), 0, 'temporary values have no physical code');
  assert.equal(await visible('#data_field_submitRequired'), 0, 'temporary values cannot require a persisted submission input');
  await click('.sp-form-data-modal .ant-modal-footer button', '添加');
  await poll(async () => (await fixture('document()')).details[0].fields.length === 2, 'temporary detail field commits');
  const temporaryField = (await fixture('document()')).details[0].fields[0];
  assert.equal(temporaryField.storageMode, 'TRANSIENT');
  assert.equal(temporaryField.storageCode, undefined);
  assert.equal(temporaryField.storageLabel, undefined);
  await poll(async () => !(await visible('.sp-form-data-modal .ant-modal')), 'field editor closed');
  await click('.sp-form-designer-properties button', '配置关联'); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '字段带出');
  await click('.sp-form-relation-modal button', '新增映射');
  await openSelect('.sp-form-relation-modal [id$="_targetFieldId_value"]');
  await click('.ant-select-item-option', '名称');
  await openSelect('.sp-form-relation-modal [id$="_mode_value"]');
  await click('.ant-select-item-option', '实时只读');
  await openSelect('.sp-form-relation-modal [id$="_destinationFieldId_value"]');
  await click('.ant-select-item-option', '新字段');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'detail live mapping commits');
  assert.equal((await fixture('document()')).relations[1].mappings[0].destinationFieldId, temporaryField.fieldId);
  assert.equal((await fixture('document()')).relations[1].mappings[0].mode, 'LIVE');

  await openRoot(); await ready();
  await openSelect('#relation_target');
  await poll(() => visible('.ant-select-item-option'), 'target replacement options');
  await click('.ant-select-item-option', 'CRM / 商品');
  await ready();
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await visible('.sp-form-relation-modal .ant-form-item-has-error, .sp-form-relation-modal .ant-form-item-explain-error, .sp-form-relation-modal .has-error'))
    || (await modalText()).includes('请检查目标资源'), 'replacement requires a new label field');
  assert.equal((await fixture('document()')).relations[0].target.formId, 'customers', 'incomplete replacement never reaches the owning draft');
  await click('.sp-form-relation-modal .ant-modal-footer button', '取消');
  await poll(() => visible('.sp-form-data-discard .ant-modal'), 'target replacement is unsaved local work');
  await click('.sp-form-data-discard button', '放弃修改');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'discard target replacement');

  await openRoot(); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '联动筛选');
  await click('.sp-form-relation-modal button', '新增联动条件');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await modalText()).includes('请检查目标资源'), 'incomplete context cannot commit');
  await openSelect('.sp-form-relation-modal [id$="_source_value"]');
  await click('.ant-select-item-option', '主表字段 · 地区');
  await openSelect('.sp-form-relation-modal [id$="_target_value"]');
  await click('.ant-select-item-option', '名称');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'context commits through owning draft');
  assert.deepEqual((await fixture('document()')).relations[0].referenceView.contextFilters,
    [{source:'FORM',sourceFieldId:'region',targetFieldId:'name',operator:'EQUALS'}]);
  await openRoot(); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '联动筛选');
  await poll(async () => (await modalText()).includes('主表字段 · 地区'), 'context authoring roundtrip');
  await click('.sp-form-relation-modal button', '删除');
  await click('.sp-form-relation-modal .ant-modal-footer button', '取消');
  await poll(() => visible('.sp-form-data-discard .ant-modal'), 'deleting context is unsaved local work');
  await click('.sp-form-data-discard button', '放弃修改');
  assert.equal((await fixture('document()')).relations[0].referenceView.contextFilters.length, 1);

  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'previous dialog closed');
  await openRoot(); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '字段带出');
  await click('.sp-form-relation-modal button', '新增映射');
  const mappingCommits = await fixture('commits()');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await modalText()).includes('请检查目标资源'), 'incomplete mapping rejected');
  assert.equal(await fixture('commits()'), mappingCommits);
  await openSelect('.sp-form-relation-modal [id$="_targetFieldId_value"]');
  await click('.ant-select-item-option', '名称');
  await openSelect('.sp-form-relation-modal [id$="_destinationFieldId_value"]');
  await click('.ant-select-item-option', '地区');
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => await fixture('commits()') === mappingCommits + 1, 'one mapping document command');
  const originalMapping = (await fixture('document()')).relations[0].mappings[0];
  assert.equal(originalMapping.targetFieldId, 'name');
  assert.equal(originalMapping.destinationFieldId, 'region');
  assert.equal(originalMapping.mode, 'SERVER_SNAPSHOT');
  assert.equal((await fixture('document()')).fields.find(field => field.fieldId === 'region').storageMode, 'STORED');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'mapping committed');
  await openRoot(); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '字段带出');
  await poll(async () => (await modalText()).includes('保存快照'), 'mapping authoring roundtrip');
  await poll(() => evaluate(`(() => {const body = document.querySelector('.sp-form-relation-mappings .ant-table-body'); const frame = document.querySelector('.sp-form-relation-mappings'); return body.getBoundingClientRect().height > 200 && body.getBoundingClientRect().bottom <= frame.getBoundingClientRect().bottom + 1;})()`), 'mapping rows fill the bounded platform table');
  await openSelect('.sp-form-relation-modal [id$="_mode_value"]');
  await click('.ant-select-item-option', '实时只读');
  await openSelect('.sp-form-relation-modal [id$="_destinationFieldId_value"]');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('.ant-select-item-option')).some(node => node.getClientRects().length && node.textContent.trim() === '地区')`), false, 'live mode never converts the persisted field');
  await click('.ant-select-item-option', '实时名称');
  await click('.sp-form-relation-modal .ant-modal-footer button', '取消');
  await poll(() => visible('.sp-form-data-discard .ant-modal'), 'mapping edit belongs to relation draft');
  await click('.sp-form-data-discard button', '放弃修改');
  assert.deepEqual((await fixture('document()')).relations[0].mappings[0], originalMapping);
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'mapping draft discarded');
  await openRoot(); await ready();
  await click('.sp-form-relation-modal [role="tab"]', '字段带出');
  await openSelect('.sp-form-relation-modal [id$="_mode_value"]');
  await click('.ant-select-item-option', '可编辑默认值');
  await poll(() => evaluate(`document.querySelector('[id$="_mode_value"]')?.closest('.ant-select')?.innerText.includes('可编辑默认值')`), 'editable default mode applied');
  await send('Emulation.setDeviceMetricsOverride', {width: 1000, height: 900, deviceScaleFactor: 1, mobile: false});
  await openSelect('.sp-form-relation-modal [id$="_nullPolicy_value"]');
  await click('.ant-select-item-option', '保留原值');
  await poll(async () => !(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')), 'single enum selection closes before resizing');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
  await poll(() => evaluate(`document.getAnimations().every(animation => animation.playState !== 'running')`), 'mapping viewport layout settles');
  if (process.env.RELATION_MAPPING_SCREENSHOT) {
    const screenshot = await send('Page.captureScreenshot', {format:'png'});
    await writeFile(process.env.RELATION_MAPPING_SCREENSHOT.replace(/\.png$/, '-passed.png'), Buffer.from(screenshot.data, 'base64'));
  }
  await click('.sp-form-relation-modal .ant-modal-footer button', '应用修改');
  await poll(async () => !(await visible('.sp-form-relation-modal .ant-modal')), 'default mapping committed');
  assert.deepEqual((await fixture('document()')).relations[0].mappings[0], {...originalMapping, mode:'EDITABLE_DEFAULT', nullPolicy:'PRESERVE'});

  await openRoot();
  await poll(async () => (await fixture('reads()')).length > completed, 'old authorization read');
  pendingIndex = (await fixture('reads()')).length - 1;
  await evaluate(`localStorage.setItem('sp.tenantId','other-tenant'); window.dispatchEvent(new Event('sp-set-tenant'));`);
  await poll(async () => (await fixture('reads()'))[pendingIndex].aborted, 'authorization change aborts old metadata');
  await fixture(`finish(${pendingIndex}, 'OLD-TENANT-SECRET')`);
  completed = pendingIndex + 1;
  await ready('新空间');
  assert(!(await modalText()).includes('OLD-TENANT-SECRET'));
  await fixture('reset()');
  await poll(async () => await fixture('commits()') === 0, 'draft reset before conversion');
  const conversionBase = (await fixture('document()')).relations[0];
  await click('button[aria-label="表单属性"], button[title="表单属性"]');
  await click('.sp-form-properties-drawer [role="tab"]', '多对多关联');
  await click('.sp-form-properties-drawer button', '新增多对多关联');
  const collectionModal = '.sp-form-collection-modal';
  await openSelect(`${collectionModal} #collection_target`);
  await click('.ant-select-item-option', 'CRM / 客户・日本語😀');
  await poll(async () => (await fixture('reads()')).length > completed, 'collection target metadata');
  await fixture(`finish(${(await fixture('reads()')).length - 1})`);
  await poll(() => visible('#collection_definition_label'), 'localized collection title');
  await evaluate(`document.querySelector('#collection_definition_label').focus()`);
  await send('Input.insertText', {text:'迁移后的客户'});
  await openSelect('#collection_definition_conversionSource');
  await click('.ant-select-item-option', '客户');
  await poll(() => evaluate(`document.querySelector('.sp-form-collection-modal').textContent.includes('历史记录保留')`),
    'migration effect is shown before applying');
  await click(`${collectionModal} .ant-modal-footer button`, '应用修改');
  await poll(async () => await fixture('commits()') === 1, 'conversion creates both relation changes in one command');
  const planned = await fixture('document()');
  assert.deepEqual(planned.relations[0], {...conversionBase, contractVersion:'2', retiredByCollectionId:planned.relations[1].relationId});
  assert.equal(planned.relations[1].collectionLabel.defaultValue, '迁移后的客户');
  await click('.sp-form-properties-drawer button', '删除');
  await poll(async () => await fixture('commits()') === 2, 'pending conversion removed');
  const reverted = await fixture('document()');
  assert.equal(reverted.relations.length, 1);
  assert.deepEqual(reverted.relations[0], conversionBase);
  await fixture('startRebind()');
  await poll(async () => (await fixture('document()')).relations.length === 2, 'published collection fixture');
  await poll(async () => !(await visible('.sp-form-properties-drawer .ant-drawer-content')), 'previous designer drawer closes');
  const publishedCollection = (await fixture('document()')).relations[1];
  await click('button[aria-label="表单属性"], button[title="表单属性"]');
  await click('.sp-form-properties-drawer [role="tab"]', '多对多关联');
  await click('.sp-form-properties-drawer button', '更换目标表单');
  await openSelect(`${collectionModal} #collection_target`);
  await click('.ant-select-item-option', 'CRM / 商品');
  await poll(async () => (await fixture('reads()')).at(-1).target.formId === 'products', 'replacement target metadata');
  await fixture(`finish(${(await fixture('reads()')).length - 1})`);
  const originalRecord = '6e4e1b4b-06f6-4ed3-b644-16a201291484';
  const replacementRecord = 'fa8b1575-f543-4ef2-81ac-85a4ad2b971a';
  const mapping = '#collection_definition_mappingText';
  await poll(() => visible(mapping), 'replacement records editor');
  await evaluate(`document.querySelector('${mapping}').focus()`);
  await send('Input.insertText', {text: originalRecord + ',' + replacementRecord});
  await poll(() => evaluate(`document.querySelector('${mapping}').value.length > 0`), 'typed record mapping');
  await evaluate(`document.querySelector('${collectionModal} .sp-form-relation-target .ant-select-clear')
    .dispatchEvent(new MouseEvent('mousedown', {bubbles:true}))`);
  await poll(() => evaluate(`!document.querySelector('${collectionModal} .sp-form-relation-target').textContent.includes('商品')`),
    'replacement target was cleared');
  assert.equal(await evaluate(`document.querySelector('${mapping}').value`), '',
    'clearing the replacement target discards its record mapping');
  const targetReadCount = (await fixture('reads()')).length;
  await openSelect(`${collectionModal} #collection_target`);
  await click('.ant-select-item-option', 'CRM / 商品');
  await poll(async () => (await fixture('reads()')).length > targetReadCount, 'reselected replacement target metadata');
  await fixture(`finish(${(await fixture('reads()')).length - 1})`);
  await evaluate(`document.querySelector('${mapping}').focus()`);
  await send('Input.insertText', {text: originalRecord + ',' + replacementRecord});
  await click(`${collectionModal} .ant-modal-footer button`, '应用修改');
  await poll(async () => await fixture('commits()') === 1, 'cross-form replacement modifies one draft');
  const replaced = (await fixture('document()')).relations[1];
  assert.notEqual(replaced.relationId, publishedCollection.relationId);
  assert.equal(replaced.target.formId, 'products');
  assert.deepEqual(replaced.collectionReplacement, {sourceRelationId: publishedCollection.relationId,
    targetRecords: {[originalRecord]: replacementRecord}});
  await click('.sp-form-properties-drawer button', '撤销改绑');
  await poll(async () => await fixture('commits()') === 2, 'unpublished replacement can be removed');
  assert.deepEqual((await fixture('document()')).relations[1], publishedCollection);
  await fixture('showEnums()');
  await poll(() => visible('#enum_fixture_number'), 'shared enum overlay fixture');
  await openSelect('#enum_fixture_number');
  await poll(() => visible('.ant-select-item-option'), 'numeric enum options');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('.ant-select-dropdown')).filter(node => node.getClientRects().length && !node.classList.contains('ant-select-dropdown-hidden')).every(node => !node.closest('#enum_clip') && Boolean(node.closest('.ant-modal-container, .ant-modal-content')))`), true, 'enum popup escapes the scroll body but stays in its modal');
  await click('.ant-select-item-option', 'Two');
  await poll(() => evaluate('window.enumFixture.values().number === 2'), 'numeric enum keeps its actual value');
  await openSelect('#enum_fixture_truth');
  await click('.ant-select-item-option', 'True');
  await poll(() => evaluate('window.enumFixture.values().truth === true'), 'boolean enum keeps its actual value');
  await openSelect('#enum_fixture_many');
  await click('.ant-select-item-option', '日本語');
  await click('.ant-select-item-option', 'English');
  await poll(() => evaluate('window.enumFixture.values().many.length === 2'), 'multiple enum keeps the full typed set');
  await evaluate('window.enumFixture.set({selected:false})');
  await poll(async () => !(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')), 'inactive owner closes enum popup');
  assert.deepEqual(await evaluate('window.enumFixture.values().many'), ['日本語', 'English']);
  await evaluate('window.enumFixture.set({selected:true,drawer:true})');
  const drawerNumber = '.ant-drawer-open #enum_fixture_number';
  await openSelect(drawerNumber);
  await poll(() => visible('.ant-select-item-option'), 'drawer options');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('.ant-select-dropdown')).filter(node => node.getClientRects().length && !node.classList.contains('ant-select-dropdown-hidden')).every(node => !node.closest('#enum_clip') && Boolean(node.closest('.ant-drawer-section, .ant-drawer-content')))`), true, 'enum popup belongs to its drawer');
  await click('.ant-select-item-option', 'Zero');
  await poll(() => evaluate('window.enumFixture.values().number === 0'), 'zero is not treated as an absent selection');
  await openSelect(drawerNumber);
  await evaluate('window.enumFixture.set({disabled:true})');
  await poll(async () => !(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')), 'disabled enum closes its popup');
  assert.equal(await evaluate('window.enumFixture.values().number'), 0);
  await evaluate('window.enumFixture.set({disabled:false})');
  await openSelect(drawerNumber);
  await evaluate('window.enumFixture.set({open:false})');
  await poll(async () => !(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')), 'closed retained drawer does not leave a body popup');
  await evaluate('window.enumFixture.set({open:true})');
  await poll(() => visible('.ant-drawer-open #enum_fixture_number'), 'retained drawer reopens');
  await poll(() => evaluate(`document.getAnimations().every(animation => animation.playState !== 'running')`), 'drawer reopen transition');
  assert.equal(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'), 0, 'reopening the drawer does not restore an old popup');
  for (const drawer of [true, false]) {
    await evaluate(`window.enumFixture.set({drawer:${drawer},open:true})`);
    const selector = drawer ? '.ant-drawer-open #enum_fixture_number' : '.ant-modal #enum_fixture_number';
    await poll(() => visible(selector), 'retained enum owner is ready');
    for (let cycle = 0; cycle < 3; cycle++) {
      await openSelect(selector);
      await poll(() => visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'),
        `retained ${drawer ? 'drawer' : 'modal'} popup opened in cycle ${cycle}`);
      await evaluate('window.enumFixture.set({open:false})');
      await poll(async () => !(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')), 'owner closes and clears popup');
      await evaluate('window.enumFixture.set({open:true})');
      await poll(() => visible(selector), 'owner reopened');
      await poll(() => evaluate(`document.getAnimations().every(animation => animation.playState !== 'running')`), 'owner reopens completely');
      assert.equal(await visible('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'), 0, `retained ${drawer ? 'drawer' : 'modal'} cycle ${cycle}`);
    }
  }
  assert.equal(errors.length, 0, JSON.stringify(errors));
  console.log(JSON.stringify({ok:true, scenarios:['real designer property entry','empty candidate rejected','empty qualification rejected','cancel restores draft','typed Unicode candidate condition','typed boolean eligibility and recheck','one command apply','undo','redo','owner cancellation','late read fenced','stale field conflict','metadata failure blocks apply','shared retry','detail field atomic association','temporary detail field through shared editor','live mapping onto authored temporary field','target replacement clears field bindings','authorization change fences metadata','incomplete context rejected','typed context uses platform form controls','context roundtrip and cancel','incomplete mapping rejected','mapping single command roundtrip','live mode preserves physical storage','mapping cancellation restores draft','editable default null policy','draft collection conversion','removing unsubmitted conversion restores the reference','enum modal popup escapes scroll body','numeric boolean and multiple values retained','inactive owner closes enum menu','drawer popup ownership','disabled field closes menu','closed drawer hides retained popup'], topology:'isolated browser fixture'},null,2));

} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
