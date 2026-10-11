import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const stringCases = JSON.parse(readFileSync(new URL('../libs/components/ExpressionEditor/stringLiterals.fixture.json', import.meta.url), 'utf8'));

export async function smokeConditionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture, stringsOnly = false}) {
  const stringLiteral = id => stringCases.find(item => item.id === id).literal;
  const expected = stringsOnly ? {flow1: '${reason == ' + stringLiteral('lf') + ' && approved == true}',
    flow2: '${reason == ' + stringLiteral('cr') + ' && approved == true}'} : {flow1: '${amount == 9007199254740993 && approved == true}',
    flow2: '${amount == 0.10000000000000001 && reviewRole == "tenant.approver"}'};
  if (stringsOnly) {
    fixture.expressionRoles([{id: 'role-marker', value: '${literal-role}', name: '模板符号角色'}]);
    fixture.expressionValues({candidateGroups: "${'tenant.approver'}"});
  }
  fixture.expressionConditions(expected, stringsOnly ? [{name: 'reason', label: '理由', type: 'string', scope: 'both', required: false}] : []);
  const page = '.nb-tab-panel-active', drawer = '.ant-drawer-open';
  const properties = drawer + ' .workflow-designer__properties';
  const condition = properties + ' .ant-form-item:nth-of-type(3)';
  const mode = label => clickText(properties + ' .ant-segmented-item', label);
  const advancedText = () => evaluate(`document.querySelector('${properties} .cm-content').cmTile.root.view.state.doc.toString()`);
  const editAdvanced = async source => {
    await mode('高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'real advanced condition editor');
    await evaluate(`(()=>{const view=document.querySelector('${properties} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(source)}}});})()`);
  };
  const select = async id => {
    const point = await poll(() => evaluate(`(()=>{const designer=document.querySelector('${drawer} .workflow-designer');
      if(designer?.getAttribute('aria-busy')!=='false'||document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getComputedTiming().iterations!==Infinity&&a.effect?.target?.closest?.('.ant-drawer')))return;
      const hit=designer.querySelector('.djs-element[data-element-id="${id}"] .djs-hit');if(!hit)return;
      const rect=hit.getBoundingClientRect();const points=hit.tagName==='path'?[0.5,0.15,0.85,0.05,0.95].map(f=>{const p=hit.getPointAtLength(hit.getTotalLength()*f);return new DOMPoint(p.x,p.y).matrixTransform(hit.getScreenCTM());}):[{x:rect.x+rect.width/2,y:rect.y+rect.height/2}];
      const point=points.find(({x,y})=>['${id}','${id}_label'].includes(document.elementFromPoint(x,y)?.closest('.djs-element')?.getAttribute('data-element-id')));return point?{x:point.x,y:point.y}:undefined;})()`), 'real flow or its associated label is pointer-reachable').catch(async error => {
      console.log(await evaluate(`(()=>{const designer=document.querySelector('${drawer} .workflow-designer'),hit=designer?.querySelector('.djs-element[data-element-id="${id}"] .djs-hit');return {id:'${id}',busy:designer?.getAttribute('aria-busy'),hit:hit?.outerHTML,points:hit&&[0.05,0.5,0.95].map(f=>{const p=hit.getPointAtLength(hit.getTotalLength()*f),point=new DOMPoint(p.x,p.y).matrixTransform(hit.getScreenCTM());return {x:point.x,y:point.y,target:document.elementFromPoint(point.x,point.y)?.outerHTML.slice(0,200)}})};})()`));
      throw error;
    });
    await send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...point, buttons: 0});
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
    await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer__element-id')?.textContent.includes('${id}')`), 'real flow selected').catch(async error => {
      console.log(await evaluate(`({selected:document.querySelector('${drawer} .workflow-designer__element-id')?.textContent,point:${JSON.stringify(point)},target:document.elementFromPoint(${point.x},${point.y})?.outerHTML.slice(0,300),properties:document.querySelector('${properties}')?.textContent})`));
      throw error;
    });
  };
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="definition1"]')`), 'condition definition list');
  const open = () => clickText(page + ' tr[data-row-key="definition1"] button', '流程设计');
  let phase = 0;
  const save = async message => {
    await fill(properties + ' .ant-form-item:first-of-type input', '条件回归 ' + ++phase);
    const before = fixture.writes.length;
    await clickText(drawer + ' button', '保存草稿');
    await poll(() => fixture.writes.length === before + 1, 'actual condition draft request');
    const xml = fixture.writes.at(-1).data.bpmnXml;
    const values = await evaluate(`(()=>{const xml=new DOMParser().parseFromString(${JSON.stringify(xml)},'application/xml');
      return Object.fromEntries([...xml.getElementsByTagNameNS('*','sequenceFlow')].filter(node=>${JSON.stringify(Object.keys(expected))}.includes(node.getAttribute('id')))
      .map(node=>[node.getAttribute('id'),node.getElementsByTagNameNS('*','conditionExpression')[0]?.textContent??null]));})()`);
    assert.deepEqual(values, expected, message);
    await clickReady(drawer + ' .ant-drawer-close');
    await poll(() => evaluate(`!document.querySelector('${drawer} .workflow-designer')`), 'saved condition design closes');
  };
  await open();
  await select('flow1');
  if (stringsOnly) {
    await mode('条件配置');
    assert.equal(await evaluate(`!!document.querySelector('${properties} .cm-content')`), false,
      'valid Flowable multiline strings must be available in ordinary mode');
    assert.equal(await evaluate(`document.querySelector('${condition} textarea').value`), stringCases.find(item => item.id === 'lf').value);
    await save('unchanged LF and CR conditions survive the actual BPMN save');
    await open(); await select('flow2'); await mode('高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'CR advanced editor');
    await mode('条件配置');
    await save('ordinary/advanced round trips retain a CR string without a user edit');
    await open(); await select('flow2'); await mode('高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'CR partial-edit advanced editor');
    await evaluate(`(()=>{const view=document.querySelector('${properties} .cm-content').cmTile.root.view;const text=view.state.doc.toString(),from=text.lastIndexOf('true');view.dispatch({changes:{from,to:from+4,insert:'false'}});})()`);
    expected.flow2 = '${reason == ' + stringLiteral('cr') + ' && approved == false}';
    await save('editing only a separate advanced clause cannot normalize the string literal');
    await open(); await select('flow1');
    const edited = 'say "yes"\nnext\t\\path';
    await fill(condition + ' textarea', edited);
    expected.flow1 = '${(reason == "say \\"yes\\"\nnext\t\\\\path" && approved == true)}';
    await mode('高级表达式');
    await poll(async () => await advancedText() === expected.flow1, 'ordinary multiline input produces executable Flowable string syntax');
    await mode('条件配置');
    await save('explicit ordinary string edits preserve quotes, LF, tabs and backslashes');

    await open(); await select('flow1');
    expected.flow1 = "${reason == 'say \\\"yes\\\" and \\'no\\'' && approved == true}";
    await editAdvanced(expected.flow1); await mode('条件配置');
    assert.equal(await evaluate(`document.querySelector('${condition} textarea').value`), 'say "yes" and \'no\'');
    await save('single-quoted Flowable strings can be inspected ordinarily without changing saved syntax');

    await open(); await select('flow1');
    expected.flow1 = '${reason == "task() [\'class\']" && approved == true}';
    await editAdvanced(expected.flow1); await mode('条件配置'); await mode('高级表达式');
    await save('quoted reflection-like business text is data, not executable surface');

    await open(); await select('review');
    const groups = properties + ' .ant-form-item:nth-of-type(5)';
    await clickText(groups + ' .ant-segmented-item', '固定值');
    await poll(() => evaluate(`document.querySelector('${groups}')?.textContent.includes('审批角色')`), 'single-quoted constant hydrates the real role selector');
    await evaluate(`document.querySelector('${groups} .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '模板符号角色');
    const beforeRole = fixture.writes.length;
    await save('selecting a real authority with template markers retains both conditions');
    const roleXml = fixture.writes[beforeRole].data.bpmnXml;
    assert.equal(await evaluate(`(()=>{const xml=new DOMParser().parseFromString(${JSON.stringify(roleXml)},'application/xml');return xml.getElementsByTagNameNS('*','userTask')[0].getAttributeNS('http://flowable.org/bpmn','candidateGroups');})()`),
      '${"tenant.approver,${literal-role}"}', 'adding a literal role preserves the existing role and must not turn into variable evaluation');

    await open(); await select('flow1'); await mode('高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'invalid escape editor');
    await poll(() => evaluate("document.querySelectorAll('.ant-message-notice').length===0"), 'prior string feedback expires');
    const beforeInvalid = fixture.writes.length;
    await evaluate(`(()=>{const view=document.querySelector('${properties} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify('${reason == "a\\n"}')}}});[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
    await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'same-task invalid Flowable escape blocks save');
    assert.equal(fixture.writes.length, beforeInvalid);
    await editAdvanced(expected.flow1);
    await save('repairing unsupported escape syntax restores valid saving');
    return;
  }
  await poll(() => evaluate(`!!document.querySelector('${properties} input[role=spinbutton]')`), 'ordinary numeric condition');
  assert.equal(await evaluate(`document.querySelector('${properties} input[role=spinbutton]').value`), '9007199254740993',
    'ordinary numeric presentation must retain the exact large integer');
  await mode('高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'large integer advanced view');
  assert.equal(await advancedText(), expected.flow1);
  await mode('条件配置');
  await select('flow2');
  await poll(() => evaluate(`!!document.querySelector('${properties} input[role=spinbutton]')`), 'ordinary decimal condition');
  assert.equal(await evaluate(`document.querySelector('${properties} input[role=spinbutton]').value`), '0.10000000000000001',
    'ordinary numeric presentation must retain every decimal digit');
  await mode('高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'precise decimal advanced view');
  assert.equal(await advancedText(), expected.flow2);
  await mode('条件配置');
  await save('mode-only round trips preserve both source conditions exactly');

  await open(); await select('flow1');
  await evaluate(`(()=>{const selects=document.querySelectorAll('${properties} .ant-select');selects[selects.length-1].dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));})()`);
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '否');
  expected.flow1 = '${(amount == 9007199254740993 && approved == false)}';
  await mode('高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'edited sibling advanced view');
  assert.equal(await advancedText(), expected.flow1);
  await save('editing only a boolean sibling never rounds the original integer');

  await open(); await select('flow2');
  await fill(properties + ' input[role=spinbutton]', '0.10000000000000002');
  await poll(() => evaluate(`document.querySelector('${properties}')?.textContent.includes('审批角色')`), 'numeric edits keep ordinary directory controls available');
  expected.flow2 = '${(amount == 0.10000000000000002 && reviewRole == "tenant.approver")}';
  await save('actual precise numeric edits stay numeric, not quoted strings');

  await open(); await select('flow1');
  expected.flow1 = '${amount == "10"}';
  await editAdvanced(expected.flow1); await mode('条件配置');
  assert.equal(await advancedText(), expected.flow1, 'quoted numeric values cannot become ordinary numeric literals');
  await save('a type-incompatible advanced comparison keeps its quoted source');

  await open(); await select('flow2');
  expected.flow2 = '${approved ? amount > 1 : amount < 0}';
  await editAdvanced(expected.flow2); await mode('条件配置');
  assert.equal(await advancedText(), expected.flow2, 'unsupported advanced syntax remains in its genuine editor');
  await save('a rejected ordinary conversion does not rewrite a complex condition');

  await open(); await select('flow2');
  await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'complex condition reopens as advanced');
  await poll(() => evaluate("document.querySelectorAll('.ant-message-notice').length===0"), 'previous condition feedback expires');
  const beforeInvalid = fixture.writes.length;
  await evaluate(`(()=>{const view=document.querySelector('${properties} .cm-content').cmTile.root.view;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\${broken'}});[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
  await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'same-task invalid advanced condition blocks save');
  assert.equal(fixture.writes.length, beforeInvalid);
  expected.flow2 = '${amount == 12.5 && approved == true}';
  await editAdvanced(expected.flow2); await mode('条件配置');
  await poll(() => evaluate("document.querySelectorAll('.ant-message-notice').length===0"), 'invalid condition feedback expires');
  await evaluate(`(()=>{[...document.querySelectorAll('${properties} button')].find(button=>button.textContent==='添加条件').click();[...document.querySelectorAll('${drawer} button')].find(button=>button.textContent.replaceAll(' ','')==='保存草稿').click();})()`);
  await poll(() => evaluate("document.body.textContent.includes('请完成当前属性配置后保存')"), 'same-task incomplete ordinary condition blocks save');
  assert.equal(fixture.writes.length, beforeInvalid);
  await select('flow1'); await select('flow2');
  assert.equal(await evaluate(`document.querySelectorAll('${properties} button[aria-label="移除条件"]').length`), 3,
    'node switching retains all incomplete draft rules');
  await evaluate(`document.querySelectorAll('${properties} button[aria-label="移除条件"]')[2].click()`);
  expected.flow2 = '${(amount == 12.5 && approved == true)}';
  await save('repairing the ordinary draft retains an exact numeric condition and restores validity');

  await open(); await select('flow1');
  const previous = expected.flow1;
  const exponent = '${amount == 1e2 && approved == true}';
  await editAdvanced(exponent);
  await clickReady(drawer + ' button[aria-label="撤销"]');
  await poll(async () => await advancedText() === previous, 'actual modeler undo restores the prior expression');
  await clickReady(drawer + ' button[aria-label="重做"]');
  await poll(async () => await advancedText() === exponent, 'actual modeler redo restores the numeric expression');
  await mode('条件配置');
  await evaluate(`(()=>{const selects=document.querySelectorAll('${properties} .ant-select');selects[selects.length-1].dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));})()`);
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '否');
  expected.flow1 = '${(amount == 1e2 && approved == false)}';
  await save('editing an ordinary sibling after undo/redo retains exponent notation');

  await open(); await select('flow2');
  expected.flow2 = '${amount == -0 && approved == true}';
  await editAdvanced(expected.flow2); await mode('条件配置');
  await evaluate(`(()=>{const input=document.querySelector('${properties} input[role=spinbutton]');input.focus();input.blur();})()`);
  await mode('高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${properties} .cm-content')`), 'negative zero advanced view');
  assert.equal(await advancedText(), expected.flow2, 'focusing and blurring an unchanged ordinary numeric input is not an edit');
  await save('unchanged negative zero retains its original source');
}
