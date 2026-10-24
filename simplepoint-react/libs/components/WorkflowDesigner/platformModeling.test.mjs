import assert from 'node:assert/strict';
import test from 'node:test';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {PlatformModelingProvider} from './platformModeling.ts';

function provider(autoPlace) {
  const registrations = [], created = [], placed = [], rules = [];
  const registry = {registerProvider: (...args) => registrations.push(args)};
  const instance = new PlatformModelingProvider(registry, registry, registry,
    {start: (...args) => created.push(args)}, {createShape: attributes => attributes},
    {get: () => autoPlace ? {append: (...args) => placed.push(args)} : undefined}, {userTask: '用户任务'},
    {on: (...args) => rules.push(args)}, {getAll: () => [{id: 'pool', type: 'bpmn:Participant'}]});
  return {instance, registrations, created, placed, rules};
}

test('palette adds a real user task and preserves the actual single-pool entry', () => {
  const {instance, registrations, created} = provider(false);
  const entries = instance.getPaletteEntries()({'create.task': {}, 'create.intermediate-event': {},
    'create.participant-expanded': {}, 'create.start-event': {}, 'create.subprocess-expanded': {}, 'hand-tool': {}});
  assert.equal(registrations.length, 3);
  assert.equal(entries['create.task'], undefined);
  assert.equal(entries['create.intermediate-event'], undefined);
  assert.ok(entries['create.participant-expanded']);
  assert.ok(entries['create.start-event']); assert.ok(entries['hand-tool']);
  entries['create.user-task'].action.click('pointer');
  assert.deepEqual(created[0], ['pointer', {type: 'bpmn:UserTask'}, undefined]);
});

test('creation rules reject a second pool and popup headers do not imply participant execution', () => {
  const {instance, rules} = provider(false);
  const validate = rules[0][2];
  assert.equal(validate({context: {shape: {type: 'bpmn:Participant'}}}), false);
  assert.equal(validate({context: {elements: [{type: 'bpmn:Participant'}]}}), false);
  assert.equal(validate({context: {shape: {type: 'bpmn:Lane'}}}), undefined);
  assert.deepEqual(Object.keys(instance.getPopupMenuHeaderEntries()({'toggle-loop': {}, 'toggle-is-collection': {},
    'toggle-participant-multiplicity': {}})), ['toggle-loop', 'toggle-is-collection']);
});

test('context append preserves both auto-placement and drag connection to its source', () => {
  const source = {id: 'source'};
  const {instance, created, placed} = provider(true);
  const entries = instance.getContextPadEntries(source)({'append.append-task': {}, 'append.end-event': {},
    'append.receive-task': {}, 'append.intermediate-event': {}, delete: {}});
  assert.ok(entries.delete); assert.ok(entries['append.end-event']);
  assert.equal(entries['append.receive-task'], undefined);
  entries['append.user-task'].action.click('pointer');
  entries['append.user-task'].action.dragstart('pointer');
  assert.deepEqual(placed[0], [source, {type: 'bpmn:UserTask'}]);
  assert.deepEqual(created[0], ['pointer', {type: 'bpmn:UserTask'}, {source}]);
});

test('replacement menu offers configured platform node types without executable task variants', () => {
  const {instance} = provider(false);
  const keys = ['replace-with-user-task', 'replace-with-parallel-gateway', 'replace-with-default-flow',
    'replace-with-script-task', 'replace-with-service-task', 'replace-with-call-activity',
    'replace-with-task', 'replace-with-timer-start', 'replace-with-event-based-gateway'];
  assert.deepEqual(Object.keys(instance.getPopupMenuEntries()(Object.fromEntries(keys.map(key => [key, {}])))), keys.slice(0, 3));
});

test('installed replacement catalog and legacy aliases retain safe ordinary event/subprocess repair', async () => {
  const require = createRequire(import.meta.url);
  const moduleUrl = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
  // Node requires an extension; resolve the installed catalog dependency without altering its contents.
  const dependency = moduleUrl(readFileSync(require.resolve('bpmn-js/lib/features/popup-menu/PopupEntries.js'), 'utf8'));
  const source = readFileSync(require.resolve('bpmn-js/lib/features/replace/ReplaceOptions.js'), 'utf8');
  assert(source.includes("'../popup-menu/PopupEntries'"));
  const catalog = await import(moduleUrl(source.replace("'../popup-menu/PopupEntries'", JSON.stringify(dependency))));
  const entries = Object.values(catalog).filter(Array.isArray).flat();
  const {instance} = provider(false);
  for (const [key, type] of [['replace-with-none-start', 'bpmn:StartEvent'], ['replace-with-none-end', 'bpmn:EndEvent'],
    ['replace-with-subprocess', 'bpmn:SubProcess']]) {
    const entry = entries.find(entry => entry.actionName === key); assert(entry, key);
    assert.equal(entry.target.type, type); assert.equal(entry.target.eventDefinitionType, undefined);
    const menu = {[key]: {action: () => entry.target}, 'replace-with-script-task': {action: () => assert.fail('unsafe action')}};
    const filtered = instance.getPopupMenuEntries()(menu);
    assert.deepEqual(Object.keys(filtered), [key]); assert.equal(filtered[key], menu[key]);
    assert.deepEqual(filtered[key].action(), entry.target);
  }
  // Previous catalog names are accepted if a compatible host supplies them, never synthesized in the current UI.
  for (const alias of ['replace-with-none-start-event', 'replace-with-none-end-event']) {
    assert(!entries.some(entry => entry.actionName === alias));
    const action = () => alias, entry = {action};
    assert.equal(instance.getPopupMenuEntries()({[alias]: entry})[alias].action, action);
  }
});
