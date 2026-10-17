/** Shared real-DOM interactions; no mocked modeler or direct mutation of BPMN business objects. */
export function workflowDesignerDriver({clickReady, clickText, poll, evaluate, send, fixture}) {
  const drawer = '.ant-drawer-open', properties = `${drawer} .workflow-designer__properties`;
  const execution = `${properties} .workflow-multi-instance`;
  // Pointer coordinates must outlive drawer transitions; hit-testing a moving canvas is not readiness.
  const animating = `document.getAnimations().some(animation => animation.playState === 'running'
    && animation.effect?.getComputedTiming().iterations !== Infinity
    && animation.effect?.target?.closest?.('.workflow-definition-editor'))`;
  const pointer = async selector => {
    await evaluate(`document.querySelector(${JSON.stringify(selector)})?.scrollIntoView({block:'center'})`);
    const point = await poll(() => evaluate(`(()=>{if(${animating})return;const element=document.querySelector(${JSON.stringify(selector)});if(!element)return;
      const r=element.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;
      return element.contains(document.elementFromPoint(x,y))?{x,y}:undefined;})()`), 'reachable control ' + selector);
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
  };
  const select = async id => {
    const point = await poll(() => evaluate(`(()=>{if(${animating})return;const designer=document.querySelector('${drawer} .workflow-designer');
      if(designer?.getAttribute('aria-busy')!=='false')return;
      const hit=designer.querySelector('.djs-element[data-element-id="${id}"] .djs-hit');if(!hit)return;
      const r=hit.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;
      return document.elementFromPoint(x,y)?.closest('.djs-element')?.getAttribute('data-element-id')==='${id}'?{x,y}:undefined;})()`), 'reachable BPMN ' + id);
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
    await poll(() => evaluate(`document.querySelector('${properties} .workflow-designer__element-id')?.textContent.includes('${id}')`), 'properties ' + id);
  };
  const choose = async (label, text) => {
    await pointer(`${execution} .ant-select:has(input[aria-label="${label}"])`);
    await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', text);
  };
  const save = async () => {
    const before = fixture.writes.length;
    await clickText(`${drawer} button`, '保存草稿');
    await poll(() => fixture.writes.length === before + 1, 'execution settings save');
    await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer')?.getAttribute('aria-busy')==='false'`), 'save settled');
    return fixture.writes.at(-1).data.bpmnXml;
  };
  const editExpression = async (field, value) => {
    await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(field + ' .cm-content')})`), 'expression editor ready');
    await evaluate(`(()=>{const view=document.querySelector(${JSON.stringify(field + ' .cm-content')}).cmTile.root.view;
      view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(value)}}});})()`);
  };
  return {drawer, properties, execution, pointer, select, choose, save, editExpression,
    open: async () => {
      await clickText('.nb-tab-panel-active tr[data-row-key="definition1"] button', '流程设计');
      await poll(() => evaluate(`!(${animating}) && document.querySelector('${drawer} .workflow-designer')?.getAttribute('aria-busy')==='false'`), 'opened designer ready');
    },
    close: async () => {
      await clickReady(`${drawer} .ant-drawer-close`);
      await poll(() => evaluate(`!document.querySelector('.workflow-definition-editor .workflow-designer')`), 'closed designer disposed');
    }};
}
