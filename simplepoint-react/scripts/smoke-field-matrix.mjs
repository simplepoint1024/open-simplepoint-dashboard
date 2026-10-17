import assert from 'node:assert/strict';
import {committedReactFiber} from './cdp-react-probe.mjs';
import {fieldMatrixModel as model, fieldMatrixTranslationNamespaces} from './common-main-fixture.mjs';

/** Genuine HTTP-exported protection schemas through the native SForm and default CRUD writer. */
export async function smokeFieldProtected({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  const matrix = fixture.matrix, root = '.nb-tab-panel-active [data-resource="/common/field-scopes"]';
  const standard = '.sp-simple-table-drawer.ant-drawer-open', configured = '.field-scope-editor.ant-drawer-open';
  matrix.enable();
  const button = async label => {await poll(() => evaluate(`[...document.querySelectorAll('${root} .sp-table-toolbar-main button')].some(b=>b.textContent.replaceAll(' ','')===${JSON.stringify(label)}&&!b.disabled)`), label + ' available');
    await evaluate(`(()=>{[...document.querySelectorAll('${root} .sp-table-toolbar-main button')].find(b=>b.textContent.replaceAll(' ','')===${JSON.stringify(label)}).click();return true;})()`);};
  const scope = async key => {await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify(key)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify(key)}}}));`);
    await clickMenu('契约测试 FieldScope');
    await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===20&&!document.querySelector('${root} .ant-spin-spinning')`), key + ' actual table');
    if (!await evaluate(`!!document.querySelector('${root} tr[data-row-key="FieldScope-239"]')`)) await clickReady(root + ' .ant-pagination-item-12');
    await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="FieldScope-239"]')&&!document.querySelector('${root} .ant-spin-spinning')`), key + ' captured owner');
    await clickReady(root + ' tr[data-row-key="FieldScope-239"] input[type="checkbox"]');
  };
  const submit = async form => {await poll(() => evaluate(`(()=>{const b=document.querySelector('${form} button[type="submit"]');return !!b&&!b.disabled;})()`), 'native metadata save ready');
    await evaluate(`(()=>{const f=document.querySelector('${form} form.rjsf'),c=document.querySelector('${form} .ant-drawer-close');f.requestSubmit();f.requestSubmit();c?.click();return true;})()`);};
  for (const mode of ['hidden-name', 'masked-name', 'hidden-entries', 'masked-entries', 'masked-child', 'hidden-description', 'masked-description']) {
    matrix.protected(mode); await scope('field-protected-' + mode);
    const original = structuredClone(matrix.row), reads = matrix.reads.length, writes = matrix.writes.length;
    const opaque = ['hidden-entries', 'masked-entries', 'masked-child'].includes(mode);
    assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-table-toolbar-main button')].find(b=>b.textContent.replaceAll(' ','')==='配置字段规则')?.disabled`), opaque);
    if (mode.endsWith('name')) assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-table-toolbar-main button')].find(b=>b.textContent.replaceAll(' ','')==='新增')?.disabled`), true,
      'required protected name cannot be fabricated on create');
    await button('编辑'); const drawer = opaque ? standard : configured;
    await poll(() => evaluate(`!!document.querySelector('${drawer} form.rjsf')&&!!document.querySelector('${drawer} #root_${mode.endsWith('description') ? 'name' : 'description'}')`), mode + ' genuine form');
    assert.equal(await evaluate(`!!document.querySelector('${opaque ? configured : standard}')`), false);
    assert.equal(await evaluate(`document.querySelectorAll('${drawer} form').length`), 1);
    if (mode.startsWith('hidden-') && !opaque) assert.equal(await evaluate(`!!document.querySelector('${drawer} #root_${mode.endsWith('name') ? 'name' : 'description'}')`), false, 'hidden field is not resurrected');
    if (mode === 'masked-name' || mode === 'masked-description') {
      const key = mode.endsWith('name') ? 'name' : 'description';
      assert.equal(await evaluate(`document.querySelector('${drawer} #root_${key}').value`), '***');
      assert.equal(await evaluate(`(()=>{const e=document.querySelector('${drawer} #root_${key}');return e.disabled||e.readOnly;})()`), true);
    }
    if (opaque) {
      assert.equal(await evaluate(`document.querySelector('${drawer} form').textContent.includes('仅保存基础信息')`), true);
      assert.equal(await evaluate(`getComputedStyle(document.querySelector('${drawer} .ant-drawer-content-wrapper')).width`), '480px', 'native Common drawer CSS width');
      assert.ok(Math.abs(await evaluate(`document.querySelector('${drawer} .ant-drawer-content-wrapper').getBoundingClientRect().width`) - 480) < 0.01,
        'native Common drawer geometry allows only subpixel transform rounding');
      assert.equal(await evaluate(`!!document.querySelector('${drawer} #root_entries')`), false);
      assert.equal(matrix.reads.length, reads, 'metadata form never loads any field directory');
      // Invalid native intent unlocks while preserving the same captured row and its opaque rules.
      await fill(drawer + ' #root_name', ' '); await submit(drawer);
      await poll(() => evaluate(`!!document.querySelector('${drawer} .ant-form-item-explain-error')`), 'real metadata name validation');
      assert.equal(matrix.writes.length, writes);
      await fill(drawer + ' #root_name', '原名称');
    }
    const field = mode.endsWith('description') ? 'name' : 'description', value = '合法修改-' + mode;
    await fill(drawer + ' #root_' + field, value);
    if (mode.endsWith('name')) {
      await poll(() => evaluate(`!!document.querySelector('${drawer} .field-resource-node button')`), 'actual directory retained despite protected name');
      await evaluate(`document.querySelector('${drawer} .field-resource-node button').click()`);
      await poll(() => evaluate(`!!document.querySelector('${drawer} [data-row-key="${model}#name"]')`), 'real field metadata');
      await clickReady(drawer + ` [data-row-key="${model}#name"] input[type="radio"][value="VISIBLE"]`);
    }
    if (mode === 'masked-entries') matrix.failWrite();
    if (opaque) await submit(drawer);
    else await clickText(drawer + ' .ant-drawer-footer button', '保存');
    await poll(() => matrix.writes.length === writes + 1 && matrix.writes.at(-1).completed, mode + ' one accepted write');
    if (mode === 'masked-entries') {
      await poll(() => evaluate(`document.querySelector('${drawer} .ant-alert-error')?.textContent.includes('字段权限事务保存失败')`), 'default writer retains failure');
      assert.equal(await evaluate(`!!document.querySelector('.sp-simple-table-discard .ant-modal')`), false, 'same-frame close cannot compete with accepted native intent');
      assert.equal(await evaluate(`document.querySelector('${drawer} #root_description').value`), value);
      await submit(drawer);
    }
    await poll(() => evaluate(`!document.querySelector('${drawer}')&&!document.querySelector('${root} .ant-spin-spinning')`), mode + ' own save closes and refreshes');
    const saved = matrix.writes.at(-1);
    assert.equal(saved.body[field], value);
    assert.equal(saved.body.id ?? saved.params.get('id'), 'FieldScope-239');
    if (opaque) {
      assert.deepEqual(Object.keys(saved.body).sort(), ['description', 'id', 'name']);
      assert.deepEqual(matrix.row.entries, original.entries, 'native generic metadata save preserves opaque rules exactly');
      assert.equal(matrix.reads.length, reads);
    } else {
      assert.deepEqual(saved.body.entries[0], original.entries[0], 'off-page real rules are retained');
      if (mode.endsWith('name')) {assert.equal('name' in saved.body, false); assert.equal(saved.body.entries.length, 2);}
      else assert.equal('description' in saved.body, false);
    }
    assert.equal(await evaluate(`document.querySelector('${root} .ant-pagination-item-active')?.textContent`), '12');
    console.log('PASS: genuine protected field editing ' + mode);
  }
  matrix.protected('hidden-entries'); await scope('field-protected-preflight');
  await button('编辑'); await poll(() => evaluate(`!!document.querySelector('${standard} #root_description')`), 'partial editor preflight owner');
  await fill(standard + ' #root_description', '旧作用域不得发起写入'); const preflight = matrix.writes.length;
  await evaluate(`(()=>{const f=document.querySelector('${standard} form.rjsf');f.requestSubmit();f.requestSubmit();localStorage.setItem('sp.contextId:__platform__','field-protected-preflight-next');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'field-protected-preflight-next'}}));return true;})()`);
  await scope('field-protected-preflight-next');
  assert.equal(matrix.writes.length, preflight, 'same-frame domain revocation cancels accepted validation before generic write');
  await button('编辑'); await poll(() => evaluate(`!!document.querySelector('${standard} #root_description')`), 'next real partial editor');
  await fill(standard + ' #root_description', '已发起写入保留归属'); const release = matrix.hold('write');
  await submit(standard); await poll(() => matrix.writes.length === preflight + 1, 'native partial write started');
  const oldWrite = matrix.writes.at(-1);
  await evaluate(`location.hash='#/profile'`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-nav-links')&&!document.querySelector('${standard}')`), 'inactive standard editor hidden');
  await clickMenu('契约测试 FieldScope');
  await poll(() => evaluate(`!!document.querySelector('${standard} form.rjsf')&&!document.querySelector('${standard} .ant-drawer-close')`), 'started partial write retains its lock on return');
  assert.equal(oldWrite.completed, false);
  await scope('field-protected-new-write'); await button('编辑');
  await poll(() => evaluate(`!!document.querySelector('${standard} #root_description')`), 'new authorization lease opens independent editor');
  await fill(standard + ' #root_description', '新作用域失败草稿'); matrix.failWrite(); await submit(standard);
  await poll(() => evaluate(`document.querySelector('${standard} .ant-alert-error')?.textContent.includes('字段权限事务保存失败')`), 'new scope retains its own failure');
  release(); await poll(() => oldWrite.completed, 'old dispatched write may finish on server after client revocation');
  assert.equal(await evaluate(`document.querySelector('${standard} #root_description').value`), '新作用域失败草稿', 'old completion cannot close or replace the new captured draft');
  await fill(standard + ' #root_description', '新作用域重试'); await submit(standard);
  await poll(() => evaluate(`!document.querySelector('${standard}')`), 'new partial owner retry succeeds');
  assert.equal(matrix.writes.at(-1).body.description, '新作用域重试'); assert.equal(matrix.writes.at(-1).body.id, 'FieldScope-239');
  console.log('PASS: partial editor same-frame scope revocation, hidden started write retention, old server completion and independent new-scope failure/retry');
  matrix.protected('hidden-entries'); await scope('field-protected-create');
  await button('新增'); await poll(() => evaluate(`!!document.querySelector('${standard} #root_name')`), 'new hidden-collection policy uses real standard form');
  await fill(standard + ' #root_name', '合法基础信息新增'); await submit(standard);
  await poll(() => evaluate(`!document.querySelector('${standard}')`), 'real metadata create completes');
  assert.equal(matrix.writes.at(-1).method, 'POST'); assert.equal('entries' in matrix.writes.at(-1).body, false);
  matrix.disable();
  console.log('PASS: protected HTTP schemas, lossless partial updates, native final data projection, validation/retry/same-frame lock, Common form style, preserved pagination and safe creation');
}

export async function smokeFieldMatrix({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.matrix.enable();
  const matrix = fixture.matrix, root = '.nb-tab-panel-active', drawer = '.field-scope-editor.ant-drawer-open';
  matrix.row.entries = [{resource: 'org.simplepoint.fixture.OffPage', field: 'hiddenField', access: 'MASKED'}];
  await clickMenu('契约测试 FieldScope');
  if (await evaluate(`!!document.querySelector('${root} button[aria-label="清除全部筛选"]')`)) await clickReady(`${root} button[aria-label="清除全部筛选"]`);
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===20 && !document.querySelector('${root} .ant-spin-spinning')`), 'field matrix base table');
  await clickReady(`${root} .ant-pagination-item-12`);
  await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="FieldScope-239"]') && !document.querySelector('${root} .ant-spin-spinning')`), 'captured off-page field scope');
  await clickReady(`${root} tr[data-row-key="FieldScope-239"] input[type="checkbox"]`);
  await clickText(`${root} .sp-table-toolbar-main button`, '配置字段规则');
  await poll(() => evaluate(`!!document.querySelector('${drawer} form.rjsf input#root_name') && document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际资源 0')`), 'real platform form composed with lazy directory');
  assert.equal(await evaluate(`document.querySelectorAll('${drawer} form').length`), 1, 'no nested forms');
  assert.equal(await evaluate(`document.querySelector('${drawer} input#root_name').closest('.field-permission-sidebar')!==null`), true, 'established matrix layout preserved');
  assert.equal(await evaluate(`[...document.querySelectorAll('${drawer} .ant-drawer-footer button')].find(b=>b.textContent==='保存')?.disabled`), true, 'initial draft clean');
  await fill(`${drawer} input#root_name`, ' ');
  const before = matrix.writes.length;
  await clickText(`${drawer} .ant-drawer-footer button`, '保存');
  await poll(() => evaluate(`!!document.querySelector('${drawer} .ant-form-item-explain-error')`), 'standard SForm validates blank name');
  assert.equal(matrix.writes.length, before);
  await fill(`${drawer} input#root_name`, '实际字段权限');
  await fill(`${drawer} .ant-pagination-simple-pager input`, '35');
  await evaluate(`document.querySelector('${drawer} .ant-pagination-simple-pager input').dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))`);
  await poll(() => evaluate(`document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际资源 520')`), 'field resource beyond first 500');
  await evaluate(`(() => {const button=[...document.querySelectorAll('${drawer} .field-resource-node button')].find(b=>b.textContent.startsWith('实际资源 520'));if(!button||button.disabled)throw Error('Field resource not selectable');button.click();})()`);
  await poll(() => evaluate(`!!document.querySelector('${drawer} [data-row-key="${model}#name"]')`), 'fields only requested for selected page');
  await clickReady(`${drawer} [data-row-key="${model}#name"] input[type="radio"][value="VISIBLE"]`);
  assert.equal(matrix.reads.filter(r => r.path.endsWith('/page-fields')).length, 1, 'does not download metadata for every catalog page');
  const dimensions = await evaluate(`(() => {const a=document.querySelector('${drawer} .field-permission-layout').getBoundingClientRect(), b=document.querySelector('${drawer} .ant-drawer-body').getBoundingClientRect();return {height:a.height,bottom:a.bottom,limit:b.bottom};})()`);
  assert.ok(dimensions.height > 200 && dimensions.bottom <= dimensions.limit + 2, JSON.stringify(dimensions));
  await clickReady(`${drawer} .ant-drawer-close`);
  await poll(() => evaluate(`document.querySelector('.ant-modal')?.textContent.includes('放弃未保存')`), 'explicit discard protects draft');
  await evaluate(`location.hash='#/profile'`);
  await poll(() => evaluate(`!!document.querySelector('${root} .profile-nav-links') && !document.querySelector('${drawer}') && !document.querySelector('.ant-modal')`), 'inactive editor and confirmation hidden');
  await clickMenu('契约测试 FieldScope');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal')`), 'own page restores discard confirmation');
  await clickText('.ant-modal-footer button', '继续编辑');
  await poll(() => evaluate(`!document.querySelector('.ant-modal')`), 'continue editing');
  assert.equal(await evaluate(`document.querySelector('${drawer} input#root_name').value`), '实际字段权限');
  matrix.failWrite();
  await evaluate(`(() => {const form=document.querySelector('${drawer} form.rjsf'),close=document.querySelector('${drawer} .ant-drawer-close');form.requestSubmit();form.requestSubmit();close?.click();return true;})()`);
  await poll(() => matrix.writes.length === before + 1, 'same-frame transactional single flight');
  assert.equal(await evaluate(`[...document.querySelectorAll('.ant-modal')].some(modal=>modal.getClientRects().length&&modal.textContent.includes('放弃未保存'))`), false,
    'accepted native validation intent must block same-frame close/discard');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .ant-drawer-close')`), false);
  await poll(() => evaluate(`document.querySelector('${drawer} .ant-alert-error')?.textContent.includes('字段权限事务保存失败')`), 'transaction failure retains entire matrix');
  assert.equal(await evaluate(`document.querySelector('${drawer} [data-row-key="${model}#name"] input[type="radio"][value="VISIBLE"]').checked`), true);
  await clickText(`${drawer} .ant-drawer-footer button`, '保存');
  await poll(() => evaluate(`!document.querySelector('${drawer}') && document.querySelector('${root} tr[data-row-key="FieldScope-239"]')?.textContent.includes('实际字段权限')`), 'retry refreshes owner without resetting page');
  assert.equal(await evaluate(`document.querySelector('${root} .ant-pagination-item-active')?.textContent`), '12');
  assert.deepEqual(matrix.writes.at(-1).body.entries, [{resource: 'org.simplepoint.fixture.OffPage', field: 'hiddenField', access: 'MASKED'},
    {resource: model, field: 'name', access: 'VISIBLE'}]);
  assert.equal(matrix.writes.at(-1).params.get('id'), 'FieldScope-239');
  fixture.matrix.disable();
  console.log('PASS: field matrix real SForm, lazy directory beyond 500, one-page metadata, retained off-page rules, discard/inactive protection, validation, transaction retry/lock and preserved pagination');
}

export async function smokeFieldPolicy({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture, mode}) {
  const matrix = fixture.matrix, root = '.nb-tab-panel-active', drawer = '.field-scope-editor.ant-drawer-open';
  matrix.enable(); matrix.policy(mode);
  matrix.row.entries = [{resource: model, field: 'name', access: 'MASKED'}];
  const original = structuredClone(matrix.row.entries);
  await clickMenu('契约测试 FieldScope');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===20 && !document.querySelector('${root} .ant-spin-spinning')`), 'policy table ready');
  await clickReady(`${root} .ant-pagination-item-12`);
  await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="FieldScope-239"]') && !document.querySelector('${root} .ant-spin-spinning')`), 'policy owner ready');
  await clickReady(`${root} tr[data-row-key="FieldScope-239"] input[type="checkbox"]`);
  await clickText(`${root} .sp-table-toolbar-main button`, '配置字段规则');
  await poll(() => evaluate(`!!document.querySelector('${drawer} form.rjsf input#root_name') && document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际资源 0')`), 'policy form ready');
  await evaluate(`document.querySelector('${drawer} .field-resource-node button').click()`);
  await poll(() => evaluate(`document.querySelectorAll('${drawer} input[type="radio"]').length>0`), 'policy radios ready');
  assert.equal(await evaluate(`[...document.querySelectorAll('${drawer} input[type="radio"]')].every(input=>input.disabled)`), true, 'root or nested read-only policy prevents existing and new access mutations');
  if (mode === 'root') assert.equal(await evaluate(`document.querySelector('${drawer} [data-row-key="${model}#name"] button[aria-label="清除字段设置"]')?.disabled`), true,
    'read-only collection cannot remove an existing entry through the clear action');
  if (mode === 'child') {
    assert.equal(await evaluate(`document.querySelector('${drawer} input#root_name').readOnly || document.querySelector('${drawer} input#root_name').disabled`), true, 'ordinary schema field respects actual metadata policy');
    await fill(`${drawer} textarea#root_description`, '只读规则保留，仅修改描述');
  } else await fill(`${drawer} input#root_name`, '只读规则保留，仅修改名称');
  await clickText(`${drawer} .ant-drawer-footer button`, '保存');
  await poll(() => evaluate(`!document.querySelector('${drawer}') && !document.querySelector('${root} .ant-spin-spinning')`), 'allowed metadata write');
  assert.equal(matrix.writes.length, 1);
  assert.deepEqual(matrix.writes[0].body.entries, original, 'metadata-only save retains actual read-only rules');
  if (mode === 'child') {
    await clickText(`${root} .sp-table-toolbar-main button`, '配置字段规则');
    await poll(() => evaluate(`!!document.querySelector('${drawer} form.rjsf') && document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际资源 0')`), 'child readonly collection remains navigable');
    await evaluate(`document.querySelector('${drawer} .field-resource-node button').click()`);
    await poll(() => evaluate(`!!document.querySelector('${drawer} [data-row-key="${model}#name"] button[aria-label="清除字段设置"]')`), 'clear applies to the writable collection, not editing child access');
    await clickReady(`${drawer} [data-row-key="${model}#name"] button[aria-label="清除字段设置"]`);
    await clickText(`${drawer} .ant-drawer-footer button`, '保存');
    await poll(() => evaluate(`!document.querySelector('${drawer}') && !document.querySelector('${root} .ant-spin-spinning')`), 'child readonly does not prohibit removal from a writable collection');
    assert.equal(matrix.writes.length, 2); assert.deepEqual(matrix.writes[1].body.entries, []);
  }
  const saved = matrix.writes.length;
  matrix.row.entries = '***';
  await clickReady(`${root} button[aria-label="刷新"]`);
  await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key="FieldScope-239"]')?.textContent.includes('***') && !document.querySelector('${root} .ant-spin-spinning')`), 'masked collection displays as unavailable, not a fake count');
  if (!await evaluate(`document.querySelector('${root} tr[data-row-key="FieldScope-239"] input[type="checkbox"]').checked`)) {
    await clickReady(`${root} tr[data-row-key="FieldScope-239"] input[type="checkbox"]`);
  }
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-table-toolbar-main button')].find(button=>button.textContent.replaceAll(' ','')==='配置字段规则')?.disabled`), true, 'masked collection cannot open a destructive empty matrix');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-table-toolbar-main button')].find(button=>button.textContent.replaceAll(' ','')==='编辑')?.disabled`), false, 'opaque rules must not prohibit legitimate metadata editing');
  assert.equal(matrix.writes.length, saved);
  matrix.disable();
  console.log(`PASS: field matrix ${mode} read-only policy, metadata-only write retains rules and masked collection cannot be overwritten`);
}

/** Actual SForm/RJSF, native callbacks and scoped HTTP: never a substitute form or query provider. */
export async function smokeFieldLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  const matrix = fixture.matrix, root = '.nb-tab-panel-active [data-resource="/common/field-scopes"]';
  const drawer = '.field-scope-editor.ant-drawer-open', discard = '.field-scope-discard .ant-modal', deletion = '.sp-simple-table-delete .ant-modal';
  matrix.enable(); matrix.hierarchy(true);
  matrix.row.entries = [{resource: 'remote.OldProjection', field: 'retired', access: 'MASKED'}];
  const button = async (selector, text) => {
    await poll(() => evaluate(`[...document.querySelectorAll(${JSON.stringify(selector + ' button')})].some(b=>b.textContent.replaceAll(' ','')===${JSON.stringify(text)}&&!b.disabled&&!b.classList.contains('ant-btn-loading'))`), 'enabled field action ' + text);
    await clickText(selector + ' button', text);
  };
  const ready = async () => {
    try {await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key]') && !document.querySelector('${root} .ant-spin-spinning')`), 'actual field table settles');}
    catch (error) {console.error('Field readiness diagnostics', await evaluate(`({hash:location.hash, context:localStorage.getItem('sp.contextId:__platform__'),active:document.querySelector('.nb-tab-panel-active')?.textContent.slice(0,1000),resources:[...document.querySelectorAll('[data-resource]')].map(e=>e.getAttribute('data-resource'))})`)); throw error;}
  };
  const ownRow = async () => {
    await ready();
    if (!await evaluate(`!!document.querySelector('${root} [data-row-key="FieldScope-239"]')`)) {await clickReady(root + ' .ant-pagination-item-12'); await ready();}
    const checkbox = root + ' [data-row-key="FieldScope-239"] input[type="checkbox"]';
    await poll(() => evaluate(`!!document.querySelector('${checkbox}') && !document.querySelector('${root} .ant-spin-spinning')`), 'requested field policy row actually arrived');
    if (!await evaluate(`document.querySelector('${checkbox}').checked`)) await clickReady(checkbox);
  };
  const open = async () => {await ownRow(); await button(root, '配置字段规则'); await poll(() => evaluate(`!!document.querySelector('${drawer} #root_description')`), 'actual matrix SForm');};
  const hide = async () => {await clickMenu('首页'); await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'retained field overlay hides');};
  const show = async () => {await clickMenu('契约测试 FieldScope'); await ready();};
  const scope = async id => {
    await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify(id)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify(id)}}}));`);
    await poll(() => evaluate(`!document.querySelector('${drawer}') && !document.querySelector('${discard}')`), 'old field authorization owner disposes'); await show();
  };
  const readCount = kind => matrix.reads.filter(read => read.kind === kind).length;
  const choose = async number => {
    await evaluate(`(()=>{const b=[...document.querySelectorAll('${drawer} .field-resource-node button')].find(e=>e.textContent.startsWith('实际资源 ${number}'));if(!b||b.disabled)throw Error('Resource not selectable');b.click();return true;})()`);
  };
  const engineProbe = `const element=document.querySelector('${drawer} form.rjsf');let fiber=element[Object.keys(element).find(k=>k.startsWith('__reactFiber$'))],instance;
    while(fiber){if(fiber.stateNode?.formElement?.current===element&&typeof fiber.stateNode.submit==='function'){instance=fiber.stateNode;break;}fiber=fiber.return;}if(!instance)throw Error('Native RJSF instance missing');`;
  await evaluate(`(()=>{const fetch=window.fetch.bind(window);window.__matrixAborts=[];window.fetch=(input,options)=>{const url=typeof input==='string'?input:input.url,signal=options?.signal??input?.signal;
    if(url?.includes('/field-scopes')&&signal)signal.addEventListener('abort',()=>window.__matrixAborts.push(url),{once:true});return fetch(input,options);};return true;})()`);
  await clickMenu('契约测试 FieldScope'); await scope('matrix-parent'); await ownRow();
  await evaluate(`(()=>{const buttons=[...document.querySelectorAll('${root} .sp-table-toolbar-main button')];buttons.find(b=>b.textContent.replaceAll(' ','')==='删除').click();buttons.find(b=>b.textContent.replaceAll(' ','')==='配置字段规则').click();return true;})()`);
  await poll(() => evaluate(`!!document.querySelector('${deletion}')`), 'root delete confirmation owns its selection');
  assert.equal(await evaluate(`!!document.querySelector('${drawer}')`), false, 'root delete synchronously blocks the external matrix');
  await button(deletion, '取消');
  await evaluate(`(()=>{const buttons=[...document.querySelectorAll('${root} .sp-table-toolbar-main button')];buttons.find(b=>b.textContent.replaceAll(' ','')==='配置字段规则').click();buttons.find(b=>b.textContent.replaceAll(' ','')==='删除').click();return true;})()`);
  await poll(() => evaluate(`!!document.querySelector('${drawer} form.rjsf')`), 'matrix owns the reverse same-frame barrier');
  assert.equal(await evaluate(`!!document.querySelector('${deletion}')`), false);
  await clickReady(drawer + ' .ant-drawer-close'); await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'clean matrix closes normally');

  matrix.failRead('resources:root:0'); await scope('matrix-read-owners'); await open();
  await poll(() => evaluate(`!!document.querySelector('${drawer} .field-permission-tree .ant-alert-error')`), 'root directory failure is inline without automatic retries');
  assert.equal(readCount('resources:root:0'), 2, 'one read in each authorization domain, not an automatic retry loop');
  const releaseRoot = matrix.hold('resources:root:0'), beforeRoot = readCount('resources:root:0');
  await evaluate(`(()=>{const b=[...document.querySelectorAll('${drawer} .field-permission-tree button')].find(b=>b.textContent.replaceAll(' ','')==='重试');window.__oldMatrixRetry=b[Object.keys(b).find(k=>k.startsWith('__reactProps$'))].onClick;b.click();b.click();return true;})()`);
  await poll(() => readCount('resources:root:0') === beforeRoot + 1, 'same-frame root retries join one query');
  await hide(); await poll(() => evaluate(`window.__matrixAborts.some(url=>url.includes('/resources')&&!url.includes('parentId='))`), 'unused directory request aborts on hide');
  await evaluate(`window.__oldMatrixRetry()`); assert.equal(readCount('resources:root:0'), beforeRoot + 1, 'a stale native retry cannot read from an inactive owner');
  releaseRoot(); await show(); await poll(() => evaluate(`document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际目录')`), 'same owner resumes directory reading');
  await evaluate(`(()=>{window.__matrixTable=document.querySelector('${root}');return true;})()`);
  const expand = drawer + ' .field-permission-tree button[aria-label="展开或收起资源"]';
  const releaseChild = matrix.hold('resources:matrix-root:0'), beforeChild = readCount('resources:matrix-root:0');
  await clickReady(expand); await poll(() => readCount('resources:matrix-root:0') === beforeChild + 1, 'actual child branch is pending');
  await clickReady(expand); await poll(() => evaluate(`window.__matrixAborts.some(url=>url.includes('parentId=matrix-root'))`), 'collapsing a branch cancels its last real consumer');
  releaseChild(); await clickReady(expand); await poll(() => evaluate(`document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际资源 0')`), 'expanded child can resume');
  await fill(drawer + ' .ant-pagination-simple-pager input', '35');
  await evaluate(`document.querySelector('${drawer} .ant-pagination-simple-pager input').dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))`);
  await poll(() => evaluate(`document.querySelector('${drawer} .field-permission-tree')?.textContent.includes('实际资源 520')`), 'nested directory remains paged beyond 500');
  const releaseTarget = matrix.hold('page-fields:matrix.page520'); await choose(520);
  await poll(() => matrix.reads.some(read => read.kind === 'page-fields:matrix.page520' && !read.completed), 'selected target metadata is genuinely pending');
  matrix.failRead('page-fields:matrix.page519'); await choose(519);
  await poll(() => evaluate(`window.__matrixAborts.some(url=>url.includes('code=matrix.page520'))`), 'changing the selected target aborts its unused metadata'); releaseTarget();
  await poll(() => evaluate(`!!document.querySelector('${drawer} .field-permission-fields .ant-alert-error')`), 'target failure is inline and manually retryable');
  assert.equal(readCount('page-fields:matrix.page519'), 1);
  const retryTarget = matrix.hold('page-fields:matrix.page519');
  await evaluate(`(()=>{const b=[...document.querySelectorAll('${drawer} .field-permission-fields button')].find(b=>b.textContent.replaceAll(' ','')==='重试');b.click();b.click();return true;})()`);
  await poll(() => readCount('page-fields:matrix.page519') === 2, 'target retries join'); retryTarget();
  await poll(() => evaluate(`!!document.querySelector('${drawer} [data-row-key="${model}#description"]')`), 'actual HTTP model fields arrive');
  // Metadata can render before its translation namespaces arrive. A changed translator is a valid
  // index input; measure value-only edits after the native i18n provider and React have applied it.
  await evaluate(`(async()=>{if(!window.spI18n?.ensure)throw Error('Actual i18n provider missing');
    await window.spI18n.ensure(${JSON.stringify(fieldMatrixTranslationNamespaces)});
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));return true;})()`);
  const rowsProbe = `let fiber=(${committedReactFiber.toString()})(document.querySelector('${drawer} [data-row-key="${model}#name"]')),rows;
    while(fiber){if(fiber.memoizedProps?.dataSource?.some(r=>r.resource===${JSON.stringify(model)}&&r.pageCode==='matrix.page519'))rows=fiber.memoizedProps.dataSource;fiber=fiber.return;}if(!rows)throw Error('Actual field table metadata source missing');`;
  await evaluate(`(()=>{${rowsProbe}window.__matrixRows=rows;window.__matrixTranslator=window.spI18n?.t;return true;})()`);
  await evaluate(`(()=>{document.querySelector('${drawer} [data-row-key="${model}#name"] input[value="VISIBLE"]').click();document.querySelector('${drawer} [data-row-key="${model}#description"] input[value="MASKED"]').click();return true;})()`);
  await poll(() => evaluate(`document.querySelector('${drawer} [data-row-key="${model}#name"] input[value="VISIBLE"]').checked&&document.querySelector('${drawer} [data-row-key="${model}#description"] input[value="MASKED"]').checked`), 'same-frame field changes retain both values');
  const sameRows = await evaluate(`(()=>{${rowsProbe}return rows.length===window.__matrixRows.length
    && rows.every((row,index)=>row.metadata===window.__matrixRows[index].metadata);})()`);
  if (!sameRows) console.error('Field index input diagnostics', await evaluate(`(()=>{${rowsProbe}return {translatorUnchanged:window.__matrixTranslator===window.spI18n?.t,
    before:window.__matrixRows.map(row=>({field:row.field,title:row.title})),after:rows.map(row=>({field:row.field,title:row.title}))};})()`));
  assert.equal(await evaluate(`window.__matrixTranslator===window.spI18n?.t`), true, 'value-only memoization uses the same settled translator');
  assert.equal(sameRows, true, 'editing values does not rebuild the complete metadata field index');
  assert.equal(await evaluate(`(()=>{${rowsProbe}return rows.find(row=>row.field==='name').accessName
    !==window.__matrixRows.find(row=>row.field==='name').accessName;})()`), true,
    'access header filtering receives the current edited value, not a frozen metadata snapshot');
  await fill(drawer + ' #root_description', '原生字段矩阵草稿');
  const releasePendingFields = matrix.hold('page-fields:matrix.page518');
  await evaluate(`(()=>{const input=document.querySelector('${drawer} [data-row-key="${model}#name"] input[type="radio"]');let fiber=input[Object.keys(input).find(k=>k.startsWith('__reactFiber$'))],change;
    while(fiber){if(fiber.memoizedProps?.name===${JSON.stringify(model + '#name')}&&typeof fiber.memoizedProps.onChange==='function')change=fiber.memoizedProps.onChange;fiber=fiber.return;}if(!change)throw Error('Native field callback missing');
    const b=[...document.querySelectorAll('${drawer} .field-resource-node button')].find(b=>b.textContent.startsWith('实际资源 518'));b.click();change({target:{value:'HIDDEN'}});return true;})()`);
  await poll(() => matrix.reads.some(read => read.kind === 'page-fields:matrix.page518' && !read.completed), 'new target pending before save');
  matrix.failWrite(); const releaseWrite = matrix.hold('write'), beforeWrite = matrix.writes.length;
  await evaluate(`(()=>{${engineProbe}const close=document.querySelector('${drawer} .ant-drawer-close');instance.submit();instance.submit();close?.click();return true;})()`);
  await poll(() => matrix.writes.length === beforeWrite + 1, 'actual imperative engine validation accepts one captured write');
  assert.equal(await evaluate(`!!document.querySelector('${discard}')`), false); assert.equal(await evaluate(`!!document.querySelector('${drawer} .ant-drawer-close')`), false);
  await poll(() => evaluate(`window.__matrixAborts.some(url=>url.includes('code=matrix.page518'))`), 'save disables unused field reads');
  const expected = [{resource: 'remote.OldProjection', field: 'retired', access: 'MASKED'}, {resource: model, field: 'name', access: 'VISIBLE'}, {resource: model, field: 'description', access: 'MASKED'}];
  assert.deepEqual(matrix.writes.at(-1).body.entries, expected, 'the old target callback cannot change the new selection draft');
  const writeAborts = await evaluate(`window.__matrixAborts.filter(url=>url.includes('/configuration')).length`);
  await hide(); assert.equal(await evaluate(`window.__matrixAborts.filter(url=>url.includes('/configuration')).length`), writeAborts, 'started writes survive merely hiding');
  releasePendingFields(); releaseWrite(); await poll(() => matrix.writes.at(-1).completed, 'hidden write actually completed');
  assert.equal(await evaluate(`!!document.querySelector('.ant-message-error') || !!document.querySelector('.ant-message-success')`), false, 'inactive write has no global feedback');
  await show(); await poll(() => evaluate(`document.querySelector('${drawer} .ant-alert-error')?.textContent.includes('字段权限事务保存失败')`), 'original owner retains its failed write');
  assert.equal(await evaluate(`document.querySelector('${drawer} #root_description').value`), '原生字段矩阵草稿');
  await button(drawer + ' .ant-drawer-footer', '保存'); await poll(() => evaluate(`!document.querySelector('${drawer}') && !document.querySelector('${root} .ant-spin-spinning')`), 'retained retry succeeds');
  assert.deepEqual(matrix.writes.at(-1).body.entries, expected); assert.equal(matrix.writes.at(-1).params.get('id'), 'FieldScope-239');
  assert.equal(await evaluate(`window.__matrixTable===document.querySelector('${root}')`), true); assert.equal(await evaluate(`document.querySelector('${root} .ant-pagination-item-active').textContent`), '12');
  console.log('PASS: owned root/child/target reads, joined retries, collapsed/hidden/saving cancellation, actual metadata memoization, same-frame fields, native imperative validation and retained started-write failures');

  await open(); await fill(drawer + ' #root_name', ' '); const invalid = matrix.writes.length;
  await button(drawer + ' .ant-drawer-footer', '保存'); await poll(() => evaluate(`!!document.querySelector('${drawer} .ant-form-item-explain-error')`), 'actual AJV validation failure stays inline');
  assert.equal(matrix.writes.length, invalid); await fill(drawer + ' #root_name', '合法再次编辑');
  await clickReady(drawer + ' .ant-drawer-close'); await poll(() => evaluate(`!!document.querySelector('${discard}')`), 'normal dirty close owns discard');
  await evaluate(`(()=>{${engineProbe}element.requestSubmit();instance.submit();instance.props.onSubmit({formData:instance.state.formData});return true;})()`);
  assert.equal(matrix.writes.length, invalid, 'native, imperative and late validation callbacks cannot bypass the discard owner');
  await button(discard, '继续编辑');
  await evaluate(`(()=>{document.querySelector('${drawer} form.rjsf').requestSubmit();localStorage.setItem('sp.contextId:__platform__','matrix-validation-next');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'matrix-validation-next'}}));return true;})()`);
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'prevalidation authorization changes dispose the old form'); await show();
  assert.equal(matrix.writes.length, invalid, 'old validation is not authority to write in a fresh authorization domain');
  await open(); await fill(drawer + ' #root_description', '旧授权域写入'); const oldRelease = matrix.hold('write');
  await poll(() => evaluate(`!document.querySelector('.ant-message-success')`), 'earlier accepted success feedback has settled');
  await button(drawer + ' .ant-drawer-footer', '保存'); await poll(() => matrix.writes.length === invalid + 1 && !matrix.writes.at(-1).completed, 'old authorization write has actually started');
  await scope('matrix-write-next'); await poll(() => evaluate(`window.__matrixAborts.some(url=>url.includes('/configuration'))`), 'authorization disposal aborts the client write observation');
  await open(); await fill(drawer + ' #root_description', '新授权域草稿'); matrix.failWrite(); const newRelease = matrix.hold('write');
  await button(drawer + ' .ant-drawer-footer', '保存'); await poll(() => matrix.writes.length === invalid + 2 && !matrix.writes.at(-1).completed, 'fresh owner acquires its independent write');
  oldRelease(); await poll(() => matrix.writes[invalid].completed, 'old server work may finish after client abort');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .ant-drawer-close')`), false, 'old completion cannot unlock the new owner');
  assert.equal(await evaluate(`!!document.querySelector('.ant-message-success')`), false);
  newRelease(); await poll(() => evaluate(`document.querySelector('${drawer} .ant-alert-error')?.textContent.includes('字段权限事务保存失败')`), 'only the new owner retains its failure');
  assert.equal(await evaluate(`document.querySelector('${drawer} #root_description').value`), '新授权域草稿');
  await button(drawer + ' .ant-drawer-footer', '保存'); await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'new owner retries independently');
  assert.equal(matrix.writes.length, invalid + 3); assert.equal(matrix.writes.at(-1).body.description, '新授权域草稿');
  matrix.disable();
  console.log('PASS: synchronous parent CRUD/matrix barriers, native invalid/discard guards, prevalidation scope disposal, started-write scope abort and old cleanup isolation');
}
