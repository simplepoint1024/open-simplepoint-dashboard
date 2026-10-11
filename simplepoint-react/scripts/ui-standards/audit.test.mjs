import assert from 'node:assert/strict';
import {test} from 'node:test';
import {classifyControls, inspectSource} from './audit.mjs';

test('resolves platform tables independently of local aliases', () => {
  const {controls} = inspectSource(`import Table from '@simplepoint/components/DataTable';
    import {Table as NativeTable} from 'antd';
    export const View = () => <><Table/><NativeTable/></>;`, 'example.tsx');
  assert.deepEqual(controls.map(({component, native}) => [component, native]), [['DataTable', false], ['Table', true]]);
});

test('exact reviews survive unrelated line shifts but never admit changed behavior or unrelated controls', () => {
  const source = `import {Button} from 'antd'; export const View = () => <Button onClick={() => selectDay()}/>`;
  const first = inspectSource(source, 'calendar.tsx').controls[0], finding = {file: 'calendar.tsx', ...first};
  const entry = {...finding, category: 'specialized-calendar', reason: 'Local calendar day selection, not a server resource or business operation.', evidence: ['calendar.test.mjs']};
  const ledger = {version: 1, controls: [entry]}, evidence = () => true;
  assert.equal(classifyControls([finding], ledger, evidence).unreviewed.length, 0);
  const shifted = {file: 'calendar.tsx', ...inspectSource('\n\n' + source, 'calendar.tsx').controls[0]};
  assert.equal(classifyControls([shifted], ledger, evidence).unreviewed.length, 0);
  const changed = {file: 'calendar.tsx', ...inspectSource(source.replace('selectDay()', 'deleteRecord()'), 'calendar.tsx').controls[0]};
  assert.equal(classifyControls([changed], ledger, evidence).unreviewed.length, 1);
  assert.equal(classifyControls([changed], ledger, evidence).stale.length, 1);
  assert.equal(classifyControls([{...finding, file: 'business.tsx'}], ledger, evidence).unreviewed.length, 1);
});
test('missing evidence, generic blanket categories and duplicate reviews fail the strict gate', () => {
  const finding = {file: 'calendar.tsx', ...inspectSource(`import {Button} from 'antd'; const View = () => <Button/>`, 'calendar.tsx').controls[0]};
  const entry = {...finding, category: 'navigation-control', reason: 'Reviewed local navigation trigger with no business mutation or separate resource query.', evidence: ['calendar.test.mjs']};
  assert.equal(classifyControls([finding], {version: 1, controls: [entry]}, () => false).invalid.length, 1);
  assert.equal(classifyControls([finding], {version: 1, controls: [{...entry, category: 'all-host-controls'}]}, () => true).unreviewed.length, 1);
  assert.equal(classifyControls([finding], {version: 1, controls: [entry, entry]}, () => true).invalid.length, 1);
});

test('covers namespace imports, picker variants and raw HTML without counting field layout helpers', () => {
  const {controls} = inspectSource(`import * as UI from 'antd'; import {Form as Field, DatePicker as Date} from 'antd';
    export const View = () => <><UI.Button/><UI.Table/><Field><Field.Item/></Field><Date.RangePicker/>
      <button/><table/><form/><select/><UI.Button.Group/><UI.Form.Item/></>;`, 'example.tsx');
  assert.deepEqual(controls.map(({component}) => component), ['Button', 'Table', 'Form', 'DatePicker.RangePicker',
    'html:button', 'html:table', 'html:form', 'html:select']);
  assert.ok(controls.every(({native}) => native));
});
