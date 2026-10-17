import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {translateBpmn} from './bpmnTranslation.ts';
import {createTranslator} from '../../shared/i18n/translator.ts';

test('native editor strings use platform locales, including diagrams and loop controls', () => {
  const root = new URL('../../../../simplepoint-services/simplepoint-service-workflow/src/main/resources/META-INF/simplepoint/i18n/', import.meta.url);
  const zh = createTranslator(JSON.parse(readFileSync(new URL('zh-CN/workflow.json', root), 'utf8')));
  const en = createTranslator(JSON.parse(readFileSync(new URL('en-US/workflow.json', root), 'utf8')));
  assert.equal(translateBpmn(zh, 'Create pool/participant'), '创建泳池（仅支持一个可执行流程）');
  assert.equal(translateBpmn(en, 'Create pool/participant'), 'Create pool/participant');
  assert.equal(translateBpmn(zh, 'Add lane above'), '在上方添加泳道');
  assert.equal(translateBpmn(zh, 'Parallel multi-instance'), '并行多实例');
  assert.equal(translateBpmn(zh, 'Default flow'), '默认流');
});

test('unknown native diagnostics keep wording, own placeholder values and missing placeholders', () => {
  const t = (_key, fallback) => fallback;
  assert.equal(translateBpmn(t, 'Unknown element {id}; {missing}', {id: '节点'}), 'Unknown element 节点; {missing}');
  assert.equal(translateBpmn(t, '{toString}', {}), '{toString}');
});
