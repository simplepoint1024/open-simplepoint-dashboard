import assert from 'node:assert/strict';

export async function smokeMcpCapabilities({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture}) {
  fixture.enable();
  // Different fixtures represent different grants. Change the actual query scope,
  // rather than expecting a valid 30-second permission cache to observe an unversioned grant change.
  await evaluate(`localStorage.setItem('sp.contextId:__platform__','mcp-capability-grants');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'mcp-capability-grants'}}));`);
  await clickMenu('MCP 分页测试');
  const page = '.nb-tab-panel-active';
  let modal = '.mcp-tool-dialog:not([style*="display: none"]) .ant-modal';
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="typed-tool"]')`), 'READY server beyond first 500 is automatically reachable');
  assert.equal(await evaluate(`document.querySelector('${page} tr[data-row-key="typed-tool"]').textContent.includes('Server-518')`), true);
  assert.equal(await evaluate(`!!document.querySelector('${page} .ant-card')`), false, 'capabilities follow the standard layout without a new title card');
  const readLabel = await evaluate(`window.spI18n.t('ai.mcp.resources.read', '读取 Resource')`);
  const promptLabel = await evaluate(`window.spI18n.t('ai.mcp.prompts.get', '获取 Prompt')`);
  const selector = `${page} .ant-select input`;
  // The shared selector is outside the inner capability tabs.
  await poll(() => evaluate(`!!document.querySelector('${selector}')`), 'server selector is mounted');
  // Use the real pointer/focus sequence. A wrapper-only synthetic mousedown does
  // not focus the input or establish that the selector is open before typing.
  const point = await evaluate(`(()=>{const input=document.querySelector('${selector}');input.scrollIntoView({block:'center'});const rect=input.getBoundingClientRect();return {x:rect.left+rect.width/2,y:rect.top+rect.height/2};})()`);
  await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
  await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
  await poll(() => evaluate(`document.activeElement===document.querySelector('${selector}') && document.querySelector('${selector}')?.getAttribute('aria-expanded')==='true'`), 'READY selector is focused and open before typing');
  await send('Input.insertText', {text: 'Server519'});
  try {
    await poll(() => evaluate(`!!document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="Server519"]')`), 'READY selector uses bounded server search');
  } catch (error) {
    console.error('READY selector state', await evaluate(`(()=>{const input=document.querySelector('${selector}');return {value:input?.value,open:input?.getAttribute('aria-expanded'),focused:document.activeElement===input,options:[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].map(option=>option.title)};})()`));
    console.error('READY directory requests', fixture.requests.filter(request => request.path.endsWith('/servers')).map(request => request.params.toString()));
    throw error;
  }
  await clickReady('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="Server519"]');
  await poll(() => evaluate(`document.querySelector('${page} tr[data-row-key="typed-tool"]')?.textContent.includes('Server-519')`), 'capabilities switch to the selected server');
  await clickText(`${page} tr[data-row-key="typed-tool"] button`, '调用');
  await poll(() => evaluate(`!!document.querySelector('${modal} #root_count')`), 'typed tool uses the platform form');
  assert.equal(await evaluate(`document.querySelectorAll('${modal} form').length`), 1, 'no nested native form around SForm');
  await fill(`${modal} #root_count`, '7');
  await clickReady(`${modal} button.ant-switch`);
  await poll(() => evaluate(`document.querySelector('${modal} .cm-content')?.textContent.includes('7')`), 'ordinary arguments convert to JSON without loss');
  const setJson = async value => {
    await evaluate(`document.querySelector('${modal} .cm-content').focus()`);
    await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'a', code: 'KeyA', modifiers: 2, windowsVirtualKeyCode: 65});
    await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'a', code: 'KeyA', modifiers: 2, windowsVirtualKeyCode: 65});
    await send('Input.insertText', {text: value});
  };
  await setJson('{"count":');
  await clickReady(`${modal} button.ant-switch`);
  await poll(() => evaluate(`document.querySelector('${modal} .ant-alert-warning') && document.querySelector('${modal} .cm-content')?.textContent==='{"count":'`), 'invalid JSON cannot be silently converted');
  const beforeBackground = fixture.writes.length;
  await evaluate(`location.hash='#/profile?state=unrelated-profile-state&code=unrelated-profile-code'`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && ![...document.querySelectorAll('.ant-modal')].some(modal=>modal.getClientRects().length)`), 'inactive capability dialog and incomplete JSON hidden');
  assert.equal(fixture.writes.length, beforeBackground, 'retained MCP does not process another route OAuth parameters');
  await clickMenu('MCP 分页测试');
  await poll(() => evaluate(`document.querySelector('${modal} .cm-content')?.textContent==='{"count":' && !!document.querySelector('${modal}')?.getClientRects().length`), 'incomplete JSON and selected server retained');
  await setJson('{"count":7}');
  await clickReady(`${modal} button.ant-switch`);
  await poll(() => evaluate(`document.querySelector('${modal} #root_count')?.value==='7'`), 'advanced mode returns typed values intact');
  await clickText(`${modal} .ant-modal-footer button`, '调用');
  await poll(() => fixture.writes.length === 1, 'tool submitted once');
  assert.deepEqual(fixture.writes[0].data.arguments, {count: 7});
  await poll(() => evaluate(`!![...document.querySelectorAll('${modal} .ant-modal-footer button')].find(button=>button.textContent.replaceAll(' ','')==='取消' && !button.disabled)`), 'tool mutation settled');
  await clickText(`${modal} .ant-modal-footer button`, '取消');
  await poll(() => evaluate(`!document.querySelector('${modal}')`), 'tool target closed');
  modal = '.mcp-resource-dialog:not([style*="display: none"]) .ant-modal';
  await clickReady(`${page} .ant-tabs-tab[data-node-key="resources"]`);
  await clickText(`${page} tr[data-row-key="demo://resource"] button`, readLabel);
  await poll(() => evaluate(`!!document.querySelector('${modal} #root_uri')`), 'resource URI uses the platform form');
  await fill(`${modal} #root_uri`, '');
  assert.equal(await evaluate(`!!document.querySelector('${modal} #root_uri')`), true, 'clearing a required URI must not close the dialog');
  await clickText(`${modal} .ant-modal-footer button`, readLabel);
  await poll(() => evaluate(`!!document.querySelector('${modal} .ant-form-item-has-error')`), 'required URI validates before request');
  assert.equal(fixture.writes.length, 1);
  await clickText(`${modal} .ant-modal-footer button`, '取消');
  await poll(() => evaluate(`!document.querySelector('${modal}')`), 'resource target closed');
  modal = '.mcp-prompt-dialog:not([style*="display: none"]) .ant-modal';
  await clickReady(`${page} .ant-tabs-tab[data-node-key="prompts"]`);
  await clickText(`${page} tr[data-row-key="typed-prompt"] button`, promptLabel);
  await poll(() => evaluate(`!!document.querySelector('${modal} #root_subject')`), 'prompt arguments use real advertised fields');
  await fill(`${modal} #root_subject`, '主题值');
  await clickText(`${modal} .ant-modal-footer button`, promptLabel);
  await poll(() => fixture.writes.length === 2, 'prompt submitted');
  assert.deepEqual(fixture.writes[1].data.arguments, {subject: '主题值'});
  await poll(() => evaluate(`!![...document.querySelectorAll('${modal} .ant-modal-footer button')].find(button=>button.textContent.replaceAll(' ','')==='取消' && !button.disabled)`), 'prompt mutation settled');
  await clickText(`${modal} .ant-modal-footer button`, '取消');
  await poll(() => evaluate(`!document.querySelector('${modal}')`), 'prompt target closed');
  modal = '.mcp-snapshot-dialog:not([style*="display: none"]) .ant-modal';
  await clickReady(`${page} .ant-tabs-tab[data-node-key="snapshots"]`);
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="Snapshot-19"]')`), 'history is server-paged');
  await clickText(`${page} tr[data-row-key="Snapshot-19"] button`, '查看差异');
  await poll(() => fixture.requests.some(request => request.path.endsWith('/snapshots/Snapshot-19/previous')), 'comparison loads the true predecessor beyond the page boundary');
  await poll(() => evaluate(`!!document.querySelector('${modal} .ant-table')`), 'snapshot difference dialog');
  await clickReady('.mcp-snapshot-dialog .ant-modal-close');
  await poll(() => evaluate(`![...document.querySelectorAll('.mcp-snapshot-dialog .ant-modal')].some(modal=>modal.getClientRects().length)`), 'actual snapshot target closed');
  await clickReady(`${page} .ant-pagination-item-26`);
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="Snapshot-519"]')`), 'discovery history beyond 500 remains reachable');
  await clickReady(`${page} .ant-tabs-tab[data-node-key="publications"]`);
  await poll(() => evaluate(`document.querySelector('${page} tr[data-row-key="Publication-existing"]')?.textContent.includes('Server520')`), 'publication list decorates only current-page server references');
  await clickText(`${page} button`, '新增');
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open #root_upstreamServerId')`), 'publication form uses the shared paged EntitySelect');
  await clickReady('.ant-drawer-open .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open #root_upstreamServerId')`), 'publication edit target closed');
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open #root_upstreamServerId')`), 'unmodified publication draft closed');
  await clickReady(`${page} tr[data-row-key="Publication-existing"] .ant-checkbox-input`);
  await clickText(`${page} button`, '编辑');
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open .ant-select')?.textContent.includes('Server520')`), 'existing non-READY readable reference hydrates independently of eligibility');
  await clickReady('.ant-drawer-open .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open #root_upstreamServerId')`), 'publication edit target closed');
  assert.ok(fixture.requests.filter(request => request.path.endsWith('/servers') || request.path.endsWith('/snapshots'))
    .every(request => Number(request.params.get('size')) <= 100));
  assert.equal(fixture.writes.length, 2, 'read/selection/history checks do not mutate resources');
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  await poll(() => evaluate(`![...document.querySelectorAll('.nb-tabs-bar .ant-tabs-tab')].some(tab=>tab.textContent.includes('MCP 分页测试'))`), 'MCP page has no abandoned blocking editor');
  assert.equal(await evaluate(`[...document.querySelectorAll('.ant-modal-confirm')].some(modal=>modal.getClientRects().length)`), false);
  console.log('PASS: MCP bounded READY options, typed arguments and lossless JSON, required URI, paged history/predecessor and existing publication hydration');
}
