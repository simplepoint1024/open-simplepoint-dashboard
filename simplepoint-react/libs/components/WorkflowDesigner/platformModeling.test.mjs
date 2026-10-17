import assert from 'node:assert/strict';
import test from 'node:test';
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
