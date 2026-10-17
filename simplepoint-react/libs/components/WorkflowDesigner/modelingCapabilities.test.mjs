import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import test from 'node:test';
import {canCreateParticipants, diagramLabel, modelingCapabilities} from './modelingCapabilities.ts';

test('diagram labels follow BPMN semantic fields rather than a synthetic name attribute', () => {
  assert.equal(diagramLabel({$type: 'bpmn:TextAnnotation', name: 'wrong', text: '正文\n第二行'}), '正文\n第二行');
  assert.equal(diagramLabel({$type: 'bpmn:Group', name: 'wrong', categoryValueRef: {value: '财务'}}), '财务');
  assert.equal(diagramLabel({$type: 'bpmn:Group'}), '');
  assert.equal(diagramLabel({$type: 'bpmn:Lane', name: '部门'}), '部门');
});

test('single-pool constraints cover first creation, batch paste and movement of existing pools', () => {
  const pool = {id: 'pool', type: 'bpmn:Participant'}, next = {id: 'next', type: 'bpmn:Participant'};
  assert.equal(canCreateParticipants([], [pool]), true);
  assert.equal(canCreateParticipants([], [pool, next]), false);
  assert.equal(canCreateParticipants([pool], [next]), false);
  assert.equal(canCreateParticipants([pool], [pool]), true);
  assert.equal(canCreateParticipants([pool], [{type: 'bpmn:Lane'}]), true);
  assert.equal(canCreateParticipants([pool, next], [{type: 'bpmn:TextAnnotation'}]), true, 'legacy diagrams remain repairable');
});

test('versioned server contract and installed BPMN palette use the actual pool entry', () => {
  const require = createRequire(import.meta.url);
  const palette = readFileSync(require.resolve('bpmn-js/lib/features/palette/PaletteProvider.js'), 'utf8');
  assert.ok(palette.includes("'create.participant-expanded'"));
  assert.equal(modelingCapabilities.schemaVersion, 1);
  assert.equal(modelingCapabilities.maxProcesses, 1);
  assert.equal(modelingCapabilities.maxParticipants, 1);
  assert.ok(modelingCapabilities.prohibitedElements.includes('serviceTask'));
  assert.ok(!modelingCapabilities.replacementEntries.includes('replace-with-service-task'));
});
