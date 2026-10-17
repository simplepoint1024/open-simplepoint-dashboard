import assert from 'node:assert/strict';
import {workflowDesignerDriver} from './workflow-designer-driver.mjs';

export async function smokeWorkflowDiagram(context) {
  const {clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture} = context;
  const {drawer, properties, pointer, select, save, open, close} = workflowDesignerDriver(context);
  const panel = `${properties} .workflow-diagram-properties`;
  const escaped = JSON.stringify;
  const shapeSelector = id => `${drawer} .djs-element[data-element-id="${id}"]`;
  const outline = async id => {
    const probe = `(()=>{const element=document.querySelector(${escaped(shapeSelector(id))});
      if(!element)return;const r=(element.querySelector('.djs-visual rect')??element).getBoundingClientRect();
      const points=[{x:r.x+12,y:r.y+r.height/2},{x:r.x+1,y:r.y+r.height/2},{x:r.x+r.width/2,y:r.y+1},
        {x:r.x+r.width-1,y:r.y+r.height/2},{x:r.x+r.width/2,y:r.y+r.height-1},
        {x:r.x+12,y:r.y+r.height/4},{x:r.x+12,y:r.y+r.height*0.75}];
      return points.map(p=>({...p,target:document.elementFromPoint(p.x,p.y)?.closest('.djs-element')?.getAttribute('data-element-id'),
        top:document.elementFromPoint(p.x,p.y)?.outerHTML.slice(0,160)}));})()`;
    let point;
    try {point = await poll(async () => (await evaluate(probe))?.find(p=>p.target===id), 'reachable diagram boundary ' + id);}
    catch(error){console.error('diagram boundary diagnostic', id, await evaluate(probe));throw error;}
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
    await poll(() => evaluate(`document.querySelector('${properties} .workflow-designer__element-id')?.textContent.includes(${escaped(id)})`), 'diagram properties ' + id);
  };
  const inspect = xml => evaluate(`(()=>{const d=new DOMParser().parseFromString(${escaped(xml)},'application/xml');
    const byId=id=>[...d.getElementsByTagNameNS('*','*')].find(e=>e.id===id);
    const group=byId('group'), category=group?.getAttribute('categoryValueRef');
    return {text:byId('note')?.getElementsByTagNameNS('*','text')[0]?.textContent,
      format:byId('note')?.getAttribute('textFormat')??'text/plain', group:category?byId(category)?.getAttribute('value'):undefined,
      groupSyntheticName:group?.getAttribute('name'), collection:byId('data')?.getAttribute('isCollection'),
      store:byId('store_ref')?.getAttribute('name'), pool:byId('pool')?.getAttribute('name'), lane:byId('lane')?.getAttribute('name'),
      assignee:byId('review')?.getAttributeNS('http://flowable.org/bpmn','assignee'),
      pools:d.getElementsByTagNameNS('*','participant').length, processes:d.getElementsByTagNameNS('*','process').length};})()`);
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="definition1"]')`), 'definition ready');
  fixture.diagramConfiguration();
  await open();
  await select('note');
  const note = '  多行说明 & <script>不是脚本</script>\n${notExecuted}  ';
  await fill(`${panel} textarea[aria-label="注释内容"]`, note);
  await evaluate(`window.__diagramSvg=document.querySelector('${drawer} .workflow-designer__canvas svg');window.spI18n.setLocale('en-US')`);
  try {
    await poll(() => evaluate(`document.querySelector('${panel} textarea[aria-label="Annotation text"]')?.value===${escaped(note)}
      && document.querySelector('${drawer} .djs-palette [data-action="create.participant-expanded"]')?.getAttribute('aria-label')?.includes('Create pool')`), 'native palette and unsaved annotation follow English locale');
  } catch (error) {
    console.error('diagram locale diagnostic', await evaluate(`({locale:window.spI18n.locale,
      translated:window.spI18n.t('workflow.bpmn.createPool'),
      fields:[...document.querySelectorAll('${panel} textarea')].map(e=>({label:e.getAttribute('aria-label'),value:e.value})),
      palette:[...document.querySelectorAll('${drawer} .djs-palette [data-action="create.participant-expanded"]')].map(e=>e.outerHTML),
      sameCanvas:window.__diagramSvg===document.querySelector('${drawer} .workflow-designer__canvas svg'),
      selected:document.querySelector('${properties} .workflow-designer__element-id')?.textContent})`));
    throw error;
  }
  assert.equal(await evaluate(`window.__diagramSvg===document.querySelector('${drawer} .workflow-designer__canvas svg')`), true, 'locale does not replace the canvas');
  await evaluate(`delete window.__diagramSvg;window.spI18n.setLocale('zh-CN')`);
  await poll(() => evaluate(`document.querySelector('${drawer} .djs-palette [data-action="create.participant-expanded"]')?.getAttribute('aria-label')?.includes('创建泳池')`), 'native palette follows Chinese locale');
  await outline('group');
  await fill(`${panel} input[aria-label="分类名称"]`, '财务分类');
  await select('data_ref');
  await clickReady(`${panel} button[aria-label="数据集合"]`);
  await select('store_ref');
  assert.ok(await evaluate(`document.querySelector('${panel}').textContent.includes('不是数据库连接器')`));
  await fill(`${panel} input[aria-label="名称"]`, '归档资料（说明）');
  await outline('pool');
  await fill(`${panel} input[aria-label="名称"]`, '单流程泳池');
  await outline('lane');
  assert.ok(await evaluate(`document.querySelector('${panel}').textContent.includes('不会自动授权')`));
  await fill(`${panel} input[aria-label="名称"]`, '财务职责');
  const expected = await inspect(await save());
  assert.equal(expected.text, note); assert.equal(expected.group, '财务分类');
  assert.equal(expected.groupSyntheticName, null); assert.equal(expected.collection, 'true');
  assert.equal(expected.store, '归档资料（说明）'); assert.equal(expected.pool, '单流程泳池');
  assert.equal(expected.lane, '财务职责'); assert.equal(expected.assignee, '${approver}');
  assert.equal(expected.pools, 1); assert.equal(expected.processes, 1);
  await close(); await open(); await select('note');
  assert.equal(await evaluate(`document.querySelector('${panel} textarea')?.value`), note);
  assert.deepEqual(await inspect(await save()), expected, 'all real semantic fields survive save and reload');
  await select('data_ref'); await clickReady(`${panel} button[aria-label="数据集合"]`);
  await clickReady(`${drawer} button[aria-label="撤销"]`);
  assert.equal((await inspect(await save())).collection, 'true', 'collection change participates in undo');
  await outline('group'); await fill(`${panel} input[aria-label="分类名称"]`, '');
  assert.equal((await inspect(await save())).group, '', 'clear uses the real category, not a stale visible name');
  await close();

  // Imported non-plain annotations remain readable and explicitly repairable; never silently rewrite XML.
  fixture.diagramConfiguration({textFormat: 'text/html'});
  await open(); await select('end');
  const before = fixture.writes.length;
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`!!document.querySelector('${panel} button[aria-label="使用纯文本格式"]')`), 'whole-canvas validation focuses annotation');
  assert.equal(fixture.writes.length, before);
  await clickReady(`${panel} button[aria-label="使用纯文本格式"]`);
  assert.equal((await inspect(await save())).format, 'text/plain');
  await close();

  fixture.diagramConfiguration({participantMultiplicity: true});
  await open(); await select('end');
  const beforeMultiplicity = fixture.writes.length;
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`!!document.querySelector('${panel} button[aria-label="参与者多实例（不支持，请关闭）"]')`), 'unsupported imported participant is focused and repairable');
  assert.equal(fixture.writes.length, beforeMultiplicity);
  await clickReady(`${panel} button[aria-label="参与者多实例（不支持，请关闭）"]`);
  assert.ok(!(await save()).includes('participantMultiplicity'));
  await close();

  // Exercise the actual native palette: one pool wraps the existing process, another is forbidden.
  fixture.standardLoopConfiguration('');
  await open();
  const createPool = async () => {
    await pointer(`${drawer} .djs-palette [data-action="create.participant-expanded"]`);
    const point = await evaluate(`(()=>{const r=document.querySelector('${drawer} .workflow-designer__canvas').getBoundingClientRect();return {x:r.x+r.width*0.65,y:r.y+r.height*0.6};})()`);
    await send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...point, button: 'none'});
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
    await pointer(`${drawer} .djs-palette [data-action="hand-tool"]`);
  };
  await createPool();
  let source = await inspect(await save());
  assert.equal(source.pools, 1); assert.equal(source.processes, 1);
  await createPool();
  source = await inspect(await save());
  assert.equal(source.pools, 1, 'actual second pool creation is rejected before mutation');
  assert.equal(source.processes, 1); assert.equal(source.assignee, '${approver}');
  await close();
  console.log('PASS: diagram semantic properties, multiline plain text, category/collection undo, save/reload, imported format repair and actual single-pool creation rules');
}
