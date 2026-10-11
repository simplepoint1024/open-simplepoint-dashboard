import assert from 'node:assert/strict';
import test from 'node:test';
import {PlatformModelingProvider} from './platformModeling.ts';

function provider(autoPlace) {
  const registrations = [], created = [], placed = [];
  const registry = {registerProvider: (...args) => registrations.push(args)};
  const instance = new PlatformModelingProvider(registry, registry, registry,
    {start: (...args) => created.push(args)}, {createShape: attributes => attributes},
    {get: () => autoPlace ? {append: (...args) => placed.push(args)} : undefined}, {userTask: '用户任务'});
  return {instance, registrations, created, placed};
}

test('palette adds a real user task and does not offer unconfigured event/multiple process creation', () => {
  const {instance, registrations, created} = provider(false);
  const entries = instance.getPaletteEntries()({'create.task': {}, 'create.intermediate-event': {},
    'create.participant': {}, 'create.start-event': {}, 'create.subprocess-expanded': {}, 'hand-tool': {}});
  assert.equal(registrations.length, 3);
  assert.equal(entries['create.task'], undefined);
  assert.equal(entries['create.intermediate-event'], undefined);
  assert.ok(entries['create.start-event']); assert.ok(entries['hand-tool']);
  entries['create.user-task'].action.click('pointer');
  assert.deepEqual(created[0], ['pointer', {type: 'bpmn:UserTask'}, undefined]);
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
