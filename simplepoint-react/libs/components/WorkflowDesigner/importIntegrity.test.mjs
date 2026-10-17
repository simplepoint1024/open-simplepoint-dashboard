import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import test from 'node:test';
import {importDiagnosticMessage, importProblem, importProblemMessage} from './importIntegrity.ts';
import flowableModdle from './flowableModdle.ts';

// Exercise the same transitive parser as the installed modeler, not a hand-written XML stand-in.
const require = createRequire(import.meta.url);
const {BpmnModdle} = createRequire(require.resolve('bpmn-js/package.json'))('bpmn-moddle');
const xml = content => `<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://simplepoint.org/workflow">
  <bpmn:process id="approval" isExecutable="true"><bpmn:userTask id="review" flowable:assignee="actor"/>
  ${content}</bpmn:process></bpmn:definitions>`;
const parser = () => new BpmnModdle({flowable: flowableModdle});
const t = (key, fallback, values) => typeof fallback === 'string'
  ? fallback.replace(/\{(\w+)\}/g, (_, name) => String(values?.[name] ?? '')) : key;

test('valid platform attributes and repairable annotations do not trigger lossy-import protection', async () => {
  for (const format of ['text/plain', 'text/html']) {
    const result = await parser().fromXML(xml(`<bpmn:textAnnotation id="note" textFormat="${format}"><bpmn:text>正文</bpmn:text></bpmn:textAnnotation>`));
    assert.equal(importProblem(result.warnings), undefined);
    assert.equal(result.rootElement.rootElements[0].flowElements[0].assignee, 'actor');
  }
});

test('real parser drops unresolved scalar references; protection preserves their owner and target diagnostic', async () => {
  const moddle = parser();
  const result = await moddle.fromXML(xml('<bpmn:group id="group" categoryValueRef="missingCategory"/>'));
  const problem = importProblem(result.warnings);
  assert.equal(problem.failed, false);
  assert.equal(problem.count, 1);
  assert.deepEqual(problem.diagnostics, [{kind: 'reference', elementId: 'group', value: 'missingCategory'}]);
  assert.ok(!(await moddle.toXML(result.rootElement)).xml.includes('categoryValueRef'), 'reserializing would silently lose the original link');
  assert.match(importDiagnosticMessage(problem.diagnostics[0], t), /group.*missingCategory/);
});

test('real parser drops unresolved collection references and unknown elements, both must block writes', async () => {
  for (const content of [
    '<bpmn:laneSet id="lanes"><bpmn:lane id="lane"><bpmn:flowNodeRef>missingNode</bpmn:flowNodeRef></bpmn:lane></bpmn:laneSet>',
    '<bpmn:futureTask id="unsupported"/>',
  ]) {
    const moddle = parser(), result = await moddle.fromXML(xml(content));
    const problem = importProblem(result.warnings);
    assert.ok(problem?.count > 0);
    const output = (await moddle.toXML(result.rootElement)).xml;
    assert.ok(!output.includes('missingNode') && !output.includes('futureTask'));
  }
});

test('failed imports are blocking even without warnings; diagnostic storage is bounded and cycle-free', () => {
  assert.equal(importProblem([], true).failed, true);
  assert.match(importProblemMessage(importProblem([], true), t), /无法完整加载/);
  const element = {id: 'x'.repeat(1000)}; element.parent = element;
  const warnings = Array.from({length: 300}, () => ({context: {element}, error: new Error('internal stack')}));
  const problem = importProblem(warnings);
  assert.equal(problem.count, 300); assert.equal(problem.diagnostics.length, 20);
  assert.equal(problem.diagnostics[0].elementId.length, 256);
  assert.ok(!JSON.stringify(problem).includes('internal stack'));
});

test('localized problem categories do not rely on raw parser errors or interpolate HTML', () => {
  const problem = importProblem([{message: 'unresolved reference <x>', element: {id: '<script>'}, value: '${value}'}]);
  assert.match(importDiagnosticMessage(problem.diagnostics[0], t), /<script>.*\$\{value\}/);
  assert.equal(importProblemMessage(problem, key => key), 'workflow.import.incomplete');
  assert.equal(importDiagnosticMessage({kind: 'unsupported'}, key => key), 'workflow.import.unsupported');
  assert.doesNotThrow(() => importProblem([null, 'warning', {}, {element: 1}]));
});
