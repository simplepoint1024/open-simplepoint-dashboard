import assert from 'node:assert/strict';
import test from 'node:test';
import {runInNewContext} from 'node:vm';
import {createPageReloader, createReadyControlClicker, createReadyTextClicker} from './cdp-navigation.mjs';

test('reload waits for the new main-frame loader to load, not an old or child document', async () => {
  let frame = {id: 'main', loaderId: 'old'};
  const commands = [];
  let navigation;
  navigation = createPageReloader({
    send: async (method, params) => {
      commands.push({method, params});
      if (method === 'Page.getFrameTree') return {frameTree: {frame}};
      assert.equal(method, 'Page.reload');
      navigation.onEvent({method: 'Page.lifecycleEvent', params: {name: 'load', frameId: 'main', loaderId: 'old'}});
      return {};
    },
    poll: async check => {
      assert.equal(await check(), false, 'old document is not a completed reload');
      frame = {id: 'main', loaderId: 'new'};
      assert.equal(await check(), false, 'new loader must finish loading');
      navigation.onEvent({method: 'Page.lifecycleEvent', params: {name: 'load', frameId: 'child', loaderId: 'new'}});
      assert.equal(await check(), false, 'child load does not complete the main frame');
      navigation.onEvent({method: 'Page.lifecycleEvent', params: {name: 'DOMContentLoaded', frameId: 'main', loaderId: 'new'}});
      assert.equal(await check(), false, 'DOM ready alone is not load completion');
      navigation.onEvent({method: 'Page.lifecycleEvent', params: {name: 'load', frameId: 'main', loaderId: 'new'}});
      assert.equal(await check(), true);
    },
  });
  await navigation.reload();
  assert.deepEqual(commands.filter(command => command.method !== 'Page.getFrameTree'), [
    {method: 'Page.reload', params: {ignoreCache: true}},
  ]);
});

test('reload propagates navigation and timeout failures instead of hiding them', async () => {
  const failure = new Error('navigation failed');
  const navigation = createPageReloader({
    send: async method => {
      if (method === 'Page.getFrameTree') return {frameTree: {frame: {id: 'main', loaderId: 'old'}}};
      throw failure;
    },
    poll: () => assert.fail('failed reload must not poll'),
  });
  await assert.rejects(navigation.reload(), error => error === failure);
  const timeout = new Error('load timed out');
  const unloaded = createPageReloader({
    send: async method => method === 'Page.getFrameTree'
      ? {frameTree: {frame: {id: 'main', loaderId: 'old'}}} : {},
    poll: async check => {assert.equal(await check(), false); throw timeout;},
  });
  await assert.rejects(unloaded.reload(), error => error === timeout);
});

test('ready click reads and clicks in one task and succeeds even when the click removes its control', async () => {
  let controls = [], clicks = 0, reads = 0;
  const control = {disabled: false, getClientRects: () => [{}], classList: {contains: () => false},
    click: () => {clicks++; controls = [];}};
  const click = createReadyControlClicker({
    evaluate: expression => runInNewContext(expression, {document: {querySelectorAll: selector => {
      assert.equal(selector, '.scoped-control'); reads++; return controls;
    }}}),
    poll: async check => {assert.equal(await check(), false); controls = [control]; assert.equal(await check(), true);},
  });
  await click('.scoped-control'); assert.equal(clicks, 1); assert.equal(reads, 2, 'one DOM read per check; no later unguarded click');
});

test('ready click retains visible, enabled and non-loading eligibility and does not click rejected controls', async () => {
  let clicked = [];
  const control = (id, disabled = false, visible = true, loading = false) => ({disabled,
    getClientRects: () => visible ? [{}] : [], classList: {contains: name => name === 'ant-btn-loading' && loading}, click: () => clicked.push(id)});
  const controls = [control('hidden', false, false), control('disabled', true), control('loading', false, true, true), control('ready')];
  const click = createReadyControlClicker({evaluate: expression => runInNewContext(expression, {document: {querySelectorAll: () => controls}}),
    poll: async check => assert.equal(await check(), true)});
  await click('button'); assert.deepEqual(clicked, ['ready']);
});

test('ready click propagates execution failures and readiness timeouts without retrying an executed click', async () => {
  const failure = new Error('click execution failed'); let calls = 0;
  const click = createReadyControlClicker({evaluate: () => {calls++; throw failure;}, poll: async check => check()});
  await assert.rejects(click('button'), error => error === failure); assert.equal(calls, 1);
  const timeout = new Error('control never ready');
  const absent = createReadyControlClicker({evaluate: () => false, poll: async check => {assert.equal(await check(), false); throw timeout;}});
  await assert.rejects(absent('button'), error => error === timeout);
});

test('text click waits for both a disabled segmented parent and its radio to unlock', async () => {
  let parentDisabled = true, inputDisabled = true, clicks = 0, reads = 0;
  const label = {textContent: '已 办', disabled: undefined, getClientRects: () => [{}],
    classList: {contains: () => false}, closest: () => parentDisabled ? {} : null,
    querySelector: selector => {assert.equal(selector, 'input:disabled'); return inputDisabled ? {} : null;}, click: () => clicks++};
  const click = createReadyTextClicker({
    evaluate: expression => runInNewContext(expression, {document: {querySelectorAll: () => {reads++; return [label];}}}),
    poll: async check => {
      assert.equal(await check(), false); parentDisabled = false;
      assert.equal(await check(), false); inputDisabled = false;
      assert.equal(await check(), true);
    },
  });
  await click('.ant-segmented-item', '已办');
  assert.equal(clicks, 1); assert.equal(reads, 3);
});

test('text click chooses only matching visible non-loading controls in one browser task', async () => {
  const clicked = [];
  const control = (id, options = {}) => ({disabled: options.disabled, textContent: options.text ?? '保 存',
    getClientRects: () => options.hidden ? [] : [{}], classList: {contains: () => !!options.loading},
    closest: () => null, querySelector: () => null, click: () => clicked.push(id)});
  let controls = [control('hidden', {hidden:true}), control('disabled', {disabled:true}),
    control('loading', {loading:true}), control('different', {text:'删除'}), control('ready')];
  const click = createReadyTextClicker({
    evaluate: expression => runInNewContext(expression, {document: {querySelectorAll: () => {
      const result = controls; controls = []; return result;
    }}}), poll: async check => assert.equal(await check(), true),
  });
  await click('button', '保存'); assert.deepEqual(clicked, ['ready']);
});
