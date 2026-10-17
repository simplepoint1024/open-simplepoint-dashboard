import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const numericCases = JSON.parse(readFileSync(new URL('../libs/components/ExpressionEditor/numericLiterals.fixture.json', import.meta.url), 'utf8'));
const priorityCases = JSON.parse(readFileSync(new URL('../libs/components/ExpressionEditor/priorityLiterals.fixture.json', import.meta.url), 'utf8'));
const dueDateCases = JSON.parse(readFileSync(new URL('../libs/components/WorkflowDesigner/dueDateLiterals.fixture.json', import.meta.url), 'utf8'));

export async function smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture, numericOnly = false, priorityOnly = false, dueDateOnly = false, calendarOnly = false, roleOnly = false}) {
  const expected = numericOnly ? {assignee: '${1e2}', candidateUsers: '${1.00}', candidateGroups: '${1e2}', dueDate: '${"P1DT2H"}', priority: '${10}'}
    : {assignee: '  ${approver}  ', candidateUsers: 'member-1', candidateGroups: '${"tenant.approver"}', dueDate: '${"P1DT2H"}', priority: '${10}'};
  if (priorityOnly) expected.priority = '${0.5}';
  if (calendarOnly) expected.dueDate = '${"2026-02-30T12:00:00Z"}';
  if (roleOnly) fixture.expressionRoles([
    {id: 'combined-role', value: 'review,finance', name: '逗号权限角色'},
    {id: 'review-role', value: 'review', name: '复核角色'}, {id: 'finance-role', value: 'finance', name: '财务角色'},
  ]);
  if (numericOnly) {
    fixture.expressionUsers([{id: '100.0', value: '100.0', name: '引擎结果用户'}, {id: '1e2', value: '1e2', name: '表达式原文用户'},
      {id: '1.0', value: '1.0', name: '小数结果用户'}, {id: '1.00', value: '1.00', name: '小数原文用户'}]);
    fixture.expressionRoles([{id: 'numeric-result', value: '100.0', name: '引擎结果角色'}, {id: 'numeric-source', value: '1e2', name: '表达式原文角色'}]);
    fixture.expressionRoles(numericCases.filter(item => item.value !== '100.0').map((item, index) => ({
      id: 'native-number-' + index, value: item.value, name: '数值角色 ' + item.literal,
    })));
  }
  fixture.expressionValues(expected);
  const page = '.nb-tab-panel-active', drawer = '.ant-drawer-open';
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="definition1"]')`), 'expression definition list');
  const openTask = async () => {
  await clickText(page + ' tr[data-row-key="definition1"] button', '流程设计');
  const point = await poll(() => evaluate(`(()=>{const designer=document.querySelector('${drawer} .workflow-designer');
    if(designer?.getAttribute('aria-busy')!=='false'||document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getComputedTiming().iterations!==Infinity&&a.effect?.target?.closest?.('.ant-drawer')))return;
    const hit=designer.querySelector('.djs-element[data-element-id="review"] .djs-hit');if(!hit)return;
    const r=hit.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;
    return document.elementFromPoint(x,y)?.closest('.djs-element')?.getAttribute('data-element-id')==='review'?{x,y}:undefined;})()`), 'real task is pointer-reachable');
  await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
  await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
  await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer__element-id')?.textContent.includes('review')`), 'selected real task');
  };
  const indices = {assignee: 3, candidateUsers: 4, candidateGroups: 5, dueDate: 6, priority: 7};
  const field = key => `${drawer} .workflow-designer__properties > form > .ant-form-item:nth-of-type(${indices[key]})`;
  const mode = (key, label) => clickText(field(key) + ' .ant-segmented-item', label);
  const advancedText = key => evaluate(`document.querySelector('${field(key)} .cm-content').cmTile.root.view.state.doc.toString()`);
  let phase = 0;
  const save = async message => {
    await fill(drawer + ' .workflow-designer__properties .ant-form-item:first-of-type input', '模式切换回归 ' + ++phase);
    const before = fixture.writes.length;
    await clickText(drawer + ' button', '保存草稿');
    await poll(() => fixture.writes.length === before + 1, 'real designer draft save');
    const saved = fixture.writes.at(-1).data.bpmnXml;
    const values = await evaluate(`(()=>{const xml=new DOMParser().parseFromString(${JSON.stringify(saved)},'application/xml');const node=[...xml.getElementsByTagNameNS('*','userTask')].find(e=>e.getAttribute('id')==='review');return Object.fromEntries(${JSON.stringify(Object.keys(expected))}.map(key=>[key,node.getAttributeNS('http://flowable.org/bpmn',key)]));})()`);
    assert.deepEqual(values, expected, message);
    await clickReady(drawer + ' .ant-drawer-close');
    await poll(() => evaluate(`!document.querySelector('${drawer} .workflow-designer')`), 'saved design closed without discard');
  };
  if (calendarOnly) {
    for (const source of ['${"2026-02-30T12:00:00Z"}', '${"2026-10-12T12:00:00"}', '${"P1W"}']) {
      expected.dueDate = source; fixture.expressionValues(expected);
      await openTask(); await mode('dueDate', '固定值');
      if (source.includes('P1W')) {
        await poll(() => evaluate(`document.querySelectorAll('${field('dueDate')} input[role=spinbutton]').length===4`), 'unsupported calendar period controls');
        assert.deepEqual(await evaluate(`[...document.querySelectorAll('${field('dueDate')} input[role=spinbutton]')].map(input=>input.value)`), ['', '', '', '']);
      } else {
        await poll(() => evaluate(`!!document.querySelector('${field('dueDate')} .ant-picker input')`), 'existing calendar text ordinary view');
        assert.equal(await evaluate(`document.querySelector('${field('dueDate')} .ant-picker input')?.value`), '',
          'different calendar normalization or implicit timezone must not be shown as a guessed browser date');
      }
      assert.equal(await evaluate(`document.querySelector('${field('dueDate')}').textContent.includes('原配置已保留')`), true);
      await mode('dueDate', '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${field('dueDate')} .cm-content')`), 'original calendar spelling restored');
      assert.equal(await advancedText('dueDate'), source);
      await save('unsupported calendar presentation must not mutate an accepted existing engine configuration');
    }
    await openTask(); await mode('dueDate', '固定值');
    await poll(() => evaluate(`document.querySelectorAll('${field('dueDate')} input[role=spinbutton]').length===4`), 'explicit duration replacement');
    await fill(field('dueDate') + ' input[role=spinbutton]', '3');
    expected.dueDate = 'P3D';
    await save('only an explicit ordinary edit replaces the preserved calendar spelling');
    return;
  }
  await openTask();
  if (roleOnly) {
    const roles = field('candidateGroups');
    await mode('candidateGroups', '固定值');
    await evaluate(`document.querySelector('${roles} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    const item = await poll(() => evaluate(`(()=>{const item=[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].find(item=>item.textContent.includes('逗号权限角色'));if(!item)return;const r=item.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,disabled:item.getAttribute('aria-disabled')==='true'||item.classList.contains('ant-select-item-option-disabled'),title:item.title};})()`), 'actual unsupported role option');
    assert.equal(item.disabled, true, 'a single role authority with a CSV delimiter must not be selectable');
    assert.ok(item.title.includes('无法安全用于流程'));
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', x: item.x, y: item.y, button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', x: item.x, y: item.y, button: 'left', buttons: 0, clickCount: 1});
    await save('one unsupported role authority must not silently become two unrelated native candidate groups');
    await openTask(); await mode('candidateGroups', '固定值');
    await evaluate(`document.querySelector('${roles} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '复核角色');
    await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '财务角色');
    expected.candidateGroups = 'tenant.approver,review,finance';
    await save('explicit selection of distinct actual roles still produces the intended native candidate groups');
    return;
  }
  if (dueDateOnly) {
    const dueDate = field('dueDate');
    await mode('dueDate', '高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')`), 'due-date advanced editor');
    const before = fixture.writes.length;
    await evaluate(`(()=>{const view=document.querySelector('${dueDate} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\${true}'}});[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
    const outcome = await poll(async () => fixture.writes.length > before ? 'submitted'
      : await evaluate("document.body.textContent.includes('请完成当前属性配置后保存')") ? 'blocked' : undefined,
    'due-date edit and actual save share the same validity boundary');
    assert.equal(outcome, 'blocked', 'a native-invalid due-date scalar must never be saved in the same Runtime task');
    assert.equal(fixture.writes.length, before);
    for (const item of dueDateCases.filter(item => item.typeIssue)) {
      await evaluate(`(()=>{const view=document.querySelector('${dueDate} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(item.source)}}});})()`);
      await mode('dueDate', '固定值');
      await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')&&document.querySelector('${dueDate} .ant-alert')?.textContent.includes('到期时间')`), 'native-invalid due date stays advanced and repairable');
      assert.equal(await advancedText('dueDate'), item.source);
      const writes = fixture.writes.length;
      await clickText(drawer + ' button', '保存草稿');
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      assert.equal(fixture.writes.length, writes, item.source + ' cannot submit an invalid due date');
    }
    for (const source of ['${"P1DT2H"}', '${"2026-10-12T12:00:00Z"}']) {
      await evaluate(`(()=>{const field=document.querySelector('${dueDate}'),view=field.querySelector('.cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(source)}}});[...field.querySelectorAll('.ant-segmented-item')].find(item=>item.textContent.trim()==='固定值').click();})()`);
      expected.dueDate = source;
      if (source.includes('P1D')) {
        await poll(() => evaluate(`document.querySelectorAll('${dueDate} input[role=spinbutton]').length===4`), 'repaired relative due-date control is rendered');
        assert.deepEqual(await evaluate(`[...document.querySelectorAll('${dueDate} input[role=spinbutton]')].map(input=>input.value)`), ['1', '2', '0', '0']);
      } else {
        await poll(() => evaluate(`!!document.querySelector('${dueDate} .ant-picker input')`), 'repaired absolute due-date control is rendered');
        const localDate = await evaluate(`(()=>{const value=new Date('2026-10-12T12:00:00Z'),pad=part=>String(part).padStart(2,'0');return value.getFullYear()+'-'+pad(value.getMonth()+1)+'-'+pad(value.getDate())+' '+pad(value.getHours())+':'+pad(value.getMinutes())+':'+pad(value.getSeconds());})()`);
        assert.equal(await evaluate(`document.querySelector('${dueDate} .ant-picker input')?.value`), localDate);
      }
      await mode('dueDate', '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')`), 'supported temporal source restored');
      assert.equal(await advancedText('dueDate'), source);
      await save('repairing the due date keeps the exact temporal expression through genuine serialization');
      await openTask(); await mode('dueDate', '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')`), 'reopened due-date expression');
    }
    const complex = '${true ? "P1D" : null}';
    const commitAndSwitch = source => evaluate(`(()=>{const field=document.querySelector('${dueDate}'),view=field.querySelector('.cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(source)}}});[...field.querySelectorAll('.ant-segmented-item')].find(item=>item.textContent.trim()==='固定值').click();})()`);
    await commitAndSwitch(complex); expected.dueDate = complex;
    await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')&&document.querySelector('${dueDate}')?.textContent.includes('不能转换为普通配置')`), 'same-event complex input keeps its advanced representation');
    assert.equal(await advancedText('dueDate'), complex);
    await save('same-event mode switching does not replace a current complex expression with the prior fixed value');
    await openTask(); await mode('dueDate', '高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')`), 'advanced input before native undo');
    await commitAndSwitch('${"P1DT2H"}');
    await poll(() => evaluate(`document.querySelectorAll('${dueDate} input[role=spinbutton]').length===4`), 'current fixed temporal view before native undo');
    await clickReady(drawer + ' .workflow-designer__toolbar button[aria-label="撤销"]');
    await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')`), 'native undo restores the current complex model value');
    await mode('dueDate', '固定值');
    assert.equal(await advancedText('dueDate'), complex, 'an acknowledged input snapshot must not revive after native canvas undo');
    await save('native undo and a rejected ordinary view preserve the actual current model source');
    await openTask(); await mode('dueDate', '高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${dueDate} .cm-content')`), 'restored due-date expression before clearing');
    await evaluate(`(()=>{const view=document.querySelector('${dueDate} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\${null}'}});})()`);
    expected.dueDate = '${null}';
    await save('an explicit null due date keeps the native no-deadline behavior');
    return;
  }
  if (priorityOnly) {
    await mode('priority', '固定值');
    await poll(() => evaluate(`!!document.querySelector('${field('priority')} input[role=spinbutton]')`), 'native priority preview resolved');
    assert.equal(await evaluate(`document.querySelector('${field('priority')} input[role=spinbutton]').value`), '0',
      'ordinary priority must match native Number.intValue rather than local decimal rounding');
    await save('priority scalar presentation leaves the original expression untouched');
    await openTask();
    const priority = field('priority');
    const edit = async source => {
      await mode('priority', '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${priority} .cm-content')`), 'priority advanced editor');
      await evaluate(`(()=>{const view=document.querySelector('${priority} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(source)}}});})()`);
      expected.priority = source;
    };
    const priorityRequests = () => fixture.requests.filter(item => item.path.endsWith('/priority-preview'));
    const delayed = '${"1e2"}', latest = '${1e2}';
    fixture.delayPriorityPreview(delayed, 1500);
    await edit(delayed); await mode('priority', '固定值');
    await poll(() => priorityRequests().some(item => item.params.value === delayed), 'delayed invalid priority preview starts');
    await edit(latest); await mode('priority', '固定值');
    await poll(() => evaluate(`document.querySelector('${priority} input[role=spinbutton]')?.value==='100'`), 'current valid priority resolves first');
    await poll(() => fixture.priorityPreviewCompletions.some(item => item.value === delayed), 'old invalid priority preview completes');
    assert.equal(await evaluate(`document.querySelector('${priority} input[role=spinbutton]')?.value`), '100', 'late invalid source cannot change the value or validity of a new priority');
    await save('late unsupported priority results cannot block a corrected current draft');
    await openTask();
    const unavailable = '${-0.5}'; fixture.failPriorityPreview(unavailable);
    await edit(unavailable); await mode('priority', '固定值');
    await poll(() => evaluate(`document.querySelector('${priority}')?.textContent.includes('优先级预览暂不可用')`), 'transient priority preview failure stays recoverable');
    assert.equal(await evaluate(`document.querySelector('${priority} input[role=spinbutton]').value`), '', 'failed native preview never falls back to rounded source text');
    await save('preview transport failure preserves a valid original priority expression');
    await openTask(); await mode('priority', '固定值');
    await poll(() => evaluate(`document.querySelector('${priority} input[role=spinbutton]')?.value==='0'`), 'priority native read retries after reopening');
    assert.equal(priorityRequests().filter(item => item.params.value === unavailable).length, 2);
    for (const item of priorityCases.filter(item => item.source.startsWith('${'))) {
      await edit(item.source); await mode('priority', '固定值');
      if (item.issue) {
        await poll(() => evaluate(`!!document.querySelector('${priority} .cm-content')&&document.querySelector('${priority} .ant-alert')?.textContent.includes('优先级')`), 'invalid native priority stays advanced with an explicit issue');
        assert.equal(await advancedText('priority'), item.source);
        const before = fixture.writes.length;
        await clickText(drawer + ' button', '保存草稿');
        await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'invalid priority cannot save');
        await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
        assert.equal(fixture.writes.length, before, item.source + ' cannot submit a previous valid priority');
      } else {
        await poll(() => evaluate(`!!document.querySelector('${priority} input[role=spinbutton]')`), 'native priority conversion for ' + item.source);
        assert.equal(await evaluate(`document.querySelector('${priority} input[role=spinbutton]').value`), item.value ?? '');
        await mode('priority', '高级表达式');
        await poll(() => evaluate(`!!document.querySelector('${priority} .cm-content')`), 'priority source restored');
        assert.equal(await advancedText('priority'), item.source);
        await save('each supported priority view survives actual BPMN serialization: ' + item.source);
        await openTask();
      }
    }
    await edit('${10}');
    await poll(() => evaluate("document.querySelectorAll('.ant-message-notice').length===0"), 'previous invalid-save feedback expires');
    const beforeBoolean = fixture.writes.length;
    await evaluate(`(()=>{const view=document.querySelector('${priority} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\${true}'}});[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
    await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'same-task boolean priority input blocks save before a preview request');
    assert.equal(fixture.writes.length, beforeBoolean);
    await edit('${9007199254740993}');
    await save('correcting invalid priorities preserves the exact large-integer source');
    for (const item of priorityCases.filter(item => !item.source.startsWith('${') && !item.issue)) {
      expected.priority = item.source; fixture.expressionValues(expected);
      await openTask(); await mode('priority', '固定值');
      await poll(() => evaluate(`!!document.querySelector('${priority} input[role=spinbutton]')`), 'persisted fixed integer text converted natively');
      assert.equal(await evaluate(`document.querySelector('${priority} input[role=spinbutton]').value`), item.value);
      await save('fixed integer text is previewed without changing its original spelling');
    }
    await openTask(); await mode('priority', '固定值');
    await poll(() => evaluate(`!!document.querySelector('${priority} input[role=spinbutton]')`), 'fixed priority replacement control');
    await fill(priority + ' input[role=spinbutton]', '37');
    expected.priority = '37';
    await save('only an actual priority input commits a new fixed value');
    return;
  }
  if (numericOnly) {
    const previewRequests = () => fixture.requests.filter(item => item.path.endsWith('/literal-preview'));
    const edit = async (key, source) => {
      await mode(key, '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${field(key)} .cm-content')`), 'numeric advanced editor ready');
      await evaluate(`(()=>{const view=document.querySelector('${field(key)} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(source)}}});})()`);
      expected[key] = source;
    };
    for (const [key, label] of [['assignee', '引擎结果用户'], ['candidateUsers', '小数结果用户'], ['candidateGroups', '引擎结果角色']]) {
      await mode(key, '固定值');
      await poll(() => evaluate(`document.querySelector('${field(key)}')?.textContent.includes(${JSON.stringify(label)})`), 'numeric identity uses the evaluated engine result');
      assert.equal(await evaluate(`document.querySelector('${field(key)}')?.textContent.includes('原文')`), false);
      await mode(key, '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${field(key)} .cm-content')`), 'original numeric expression restored');
      assert.equal(await advancedText(key), expected[key]);
    }
    assert.equal(previewRequests().length, 2, 'identical user/role constants share one scope-keyed native read');
    await mode('priority', '固定值');
    await poll(() => evaluate(`!!document.querySelector('${field('priority')} input[role=spinbutton]')`), 'native priority control ready after its own preview');
    assert.equal(await evaluate(`document.querySelector('${field('priority')} input[role=spinbutton]').value`), '10');
    assert.equal(previewRequests().length, 2, 'priority retains numeric-control presentation without identity lookups');
    await save('numeric identity previews preserve every original expression byte');
    await openTask();
    await mode('assignee', '固定值');
    await poll(() => evaluate(`document.querySelector('${field('assignee')}')?.textContent.includes('引擎结果用户')`), 'cached native identity survives reopening');
    assert.equal(previewRequests().length, 2, 'opening the same definition does not duplicate successful native reads');
    for (const item of numericCases) {
      await edit('candidateGroups', '${' + item.literal + '}');
      await mode('candidateGroups', '固定值');
      const label = item.value === '100.0' ? '引擎结果角色' : '数值角色 ' + item.literal;
      await poll(() => evaluate(`document.querySelector('${field('candidateGroups')}')?.textContent.includes(${JSON.stringify(label)})`), 'native numeric role ' + item.literal);
      await mode('candidateGroups', '高级表达式');
      await poll(() => evaluate(`!!document.querySelector('${field('candidateGroups')} .cm-content')`), 'numeric spelling returns unchanged');
      assert.equal(await advancedText('candidateGroups'), expected.candidateGroups);
    }
    const countBeforeQuoted = previewRequests().length;
    await edit('candidateGroups', '${"1e2"}'); await mode('candidateGroups', '固定值');
    await poll(() => evaluate(`document.querySelector('${field('candidateGroups')}')?.textContent.includes('表达式原文角色')`), 'quoted numeric-looking identity stays literal');
    assert.equal(previewRequests().length, countBeforeQuoted);
    await save('all native numeric spellings and quoted numeric-looking role IDs survive actual saving');
    await openTask();
    const delayed = '${ 1e7 }', latest = '${ 0.0001 }';
    fixture.delayNumericPreview(delayed, 1500);
    await edit('candidateGroups', delayed); await mode('candidateGroups', '固定值');
    await poll(() => previewRequests().some(item => item.params.expression === delayed), 'delayed native request starts');
    await edit('candidateGroups', latest); await mode('candidateGroups', '固定值');
    await poll(() => evaluate(`document.querySelector('${field('candidateGroups')}')?.textContent.includes('数值角色 0.0001')`), 'new native role resolves before the old request');
    await poll(() => fixture.numericPreviewCompletions.some(item => item.expression === delayed), 'old native request completes');
    assert.equal(await evaluate(`document.querySelector('${field('candidateGroups')}')?.textContent.includes('数值角色 0.0001')`), true,
      'a late native result cannot replace the current identity');
    await save('out-of-order native previews cannot change the latest expression');

    await openTask();
    const unavailable = '${ 1e2 }'; fixture.failNumericPreview(unavailable);
    await edit('assignee', unavailable); await mode('assignee', '固定值');
    await poll(() => evaluate(`document.querySelector('${field('assignee')}')?.textContent.includes('身份预览暂不可用')`), 'failed native preview has an explicit recoverable state');
    assert.equal(await evaluate(`document.querySelectorAll('${field('assignee')} .ant-select-selection-item').length`), 0,
      'a failed preview keeps replacement available without selecting the numeric source as an identity');
    await save('native preview failure does not erase or invalidate the original numeric expression');
    await openTask(); await mode('assignee', '固定值');
    await poll(() => evaluate(`document.querySelector('${field('assignee')}')?.textContent.includes('引擎结果用户')`), 'failed native reads can retry without an expression rewrite');
    assert.equal(previewRequests().filter(item => item.params.expression === unavailable).length, 2);
    await evaluate(`document.querySelector('${field('assignee')} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '租户成员 2');
    expected.assignee = 'member-2';
    await save('only an explicit real user selection replaces the numeric expression with a fixed identity');
    return;
  }
  // Element name and documentation precede assignee/candidate user properties.
  const users = field('candidateUsers');
  await fill(drawer + ' .workflow-designer__properties .ant-form-item:first-of-type input', '模式切换回归');
  await clickText(users + ' .ant-segmented-item', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${users} .cm-content')`), 'literal advanced preview');
  await save('opening an advanced value preview must not rewrite any persisted task attribute');

  await openTask();
  await mode('assignee', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('assignee')} .cm-content')`), 'whitespace advanced value');
  assert.equal(await advancedText('assignee'), expected.assignee);
  await mode('assignee', '固定值');
  assert.equal(await evaluate(`document.querySelectorAll('${field('assignee')} .ant-select-selection-item').length`), 0,
    'a reference is not a fixed user identity and must not hydrate it as one');
  await mode('assignee', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('assignee')} .cm-content')`), 'reference restored');
  assert.equal(await advancedText('assignee'), expected.assignee);
  await mode('candidateUsers', '流程变量');
  assert.equal(await evaluate(`document.querySelectorAll('${users} .ant-select-selection-item').length`), 0, 'mode change must not select the first variable');
  await mode('candidateUsers', '固定值');
  await poll(() => evaluate(`document.querySelector('${users}')?.textContent.includes('租户成员 1')`), 'original literal user restored as presentation');
  await mode('candidateGroups', '固定值');
  await poll(() => evaluate(`document.querySelector('${field('candidateGroups')}')?.textContent.includes('审批角色')`), 'constant uses actual role selector');
  await mode('candidateGroups', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('candidateGroups')} .cm-content')`), 'role constant advanced restored');
  assert.equal(await advancedText('candidateGroups'), expected.candidateGroups);
  await mode('dueDate', '固定值');
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('${field('dueDate')} input[role=spinbutton]')].map(input=>input.value)`), ['1', '2', '0', '0']);
  await mode('dueDate', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('dueDate')} .cm-content')`), 'duration expression restored');
  assert.equal(await advancedText('dueDate'), expected.dueDate);
  await mode('priority', '固定值');
  await poll(() => evaluate(`!!document.querySelector('${field('priority')} input[role=spinbutton]')`), 'native priority control ready');
  assert.equal(await evaluate(`document.querySelector('${field('priority')} input[role=spinbutton]').value`), '10');
  await mode('priority', '高级表达式');
  await save('ordinary/variable/advanced round trips retain every original attribute byte');

  await openTask(); await mode('candidateUsers', '流程变量');
  await evaluate(`document.querySelector('${users} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '指定审批人');
  expected.candidateUsers = '${approver}';
  await mode('candidateUsers', '固定值');
  assert.equal(await evaluate(`document.querySelectorAll('${users} .ant-select-selection-item').length`), 0, 'changing view after a selection must not revive the cached previous fixed user');
  await save('an explicit variable selection commits once; returning to fixed view does not undo it');

  await openTask(); await mode('candidateUsers', '固定值');
  await evaluate(`document.querySelector('${users} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '租户成员 2');
  expected.candidateUsers = 'member-2';
  await mode('candidateUsers', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${users} .cm-content')`), 'selected user advanced preview');
  assert.equal(await advancedText('candidateUsers'), '${"member-2"}');
  await save('an explicit real user selection is saved as a literal even when its advanced preview is open');

  await openTask(); await mode('assignee', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('assignee')} .cm-content')`), 'complex expression editor');
  expected.assignee = '${approver == initiator ? approver : initiator}';
  await evaluate(`(()=>{const view=document.querySelector('${field('assignee')} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(expected.assignee)}}});})()`);
  await mode('assignee', '固定值');
  assert.equal(await evaluate(`!!document.querySelector('${field('assignee')} .cm-content')`), true, 'unrepresentable expressions retain their advanced editor');
  assert.equal(await advancedText('assignee'), expected.assignee);
  await save('complex advanced values survive rejected ordinary conversion without default assignment');

  await openTask(); await mode('assignee', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('assignee')} .cm-content')`), 'invalid expression editor');
  const writesBeforeInvalid = fixture.writes.length;
  await evaluate(`(()=>{const view=document.querySelector('${field('assignee')} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\${broken'}});[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
  await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'same-task invalid expression blocks actual save');
  assert.equal(fixture.writes.length, writesBeforeInvalid);
  await evaluate(`(()=>{const view=document.querySelector('${field('assignee')} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(expected.assignee)}}});})()`);
  await save('repairing an invalid advanced draft saves only its corrected value');

  await openTask(); await mode('assignee', '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${field('assignee')} .cm-content')`), 'unwrapped advanced draft editor');
  await poll(() => evaluate("document.querySelectorAll('.ant-message-notice').length===0"), 'previous save feedback expires');
  const writesBeforeUnwrapped = fixture.writes.length;
  await evaluate(`(()=>{const view=document.querySelector('${field('assignee')} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'approver'}});[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
  await poll(() => evaluate("document.body.textContent.includes('表达式必须使用 ${…} 格式')"), 'unwrapped advanced draft is diagnosed');
  await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'unwrapped advanced draft blocks actual save');
  assert.equal(fixture.writes.length, writesBeforeUnwrapped, 'editing advanced syntax must not bypass validation by becoming a literal');
  await evaluate(`(()=>{const view=document.querySelector('${field('assignee')} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(expected.assignee)}}});})()`);
  await save('correcting unwrapped advanced syntax restores a valid persisted expression');
}
