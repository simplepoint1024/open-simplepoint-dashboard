import assert from 'node:assert/strict';
import {workflowDesignerDriver} from '../../../simplepoint-react/scripts/workflow-designer-driver.mjs';

/** Exercise the installed modeler's actual replacement menu and undo without modifying live versions. */
export async function verifyActualWorkflowModeling({evaluate, send, poll, click, call, definitions, state, save}) {
  const drawer = '.workflow-definition-editor.ant-drawer-open';
  const driver = workflowDesignerDriver({evaluate, send, poll, clickText: click,
    clickReady: selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`)});
  state.cases ??= {}; state.originals ??= {}; await save();
  await evaluate("location.hash='/workflow/definitions'");
  for (const [kind, definitionId] of Object.entries(definitions)) {
    const original = await call('/workflow/definitions/' + definitionId);
    state.originals[kind] = original; await save();
    await click(`.nb-tab-panel-active [data-row-key="${definitionId}"] button`, '流程设计');
    const selected = () => evaluate(`document.querySelector('${drawer} .workflow-designer__element-id')?.textContent.split(' · ')[0].trim()`);
    const select = async id => {if (await selected() !== id) await driver.select(id);};
    const replace = async key => {
      await driver.pointer('.djs-context-pad.open [data-action=replace]');
      await poll(() => evaluate("!!document.querySelector('.djs-popup [data-id]')"), 'replacement menu');
      const entries = await evaluate("[...document.querySelectorAll('.djs-popup [data-id]')].map(e=>e.getAttribute('data-id'))");
      assert(entries.includes(key), key + ': available=' + entries.join(','));
      await driver.pointer(`.djs-popup [data-id="${key}"]`);
      await poll(() => evaluate("!document.querySelector('.djs-popup')"), 'replacement applied');
      const description = await evaluate(`document.querySelector('${drawer} .workflow-designer__element-id')?.textContent`);
      assert(description); state.cases[key] = {passed: true, definitionId, description, entries}; await save();
      console.log('PASS actual modeling replacement ' + key);
      return selected();
    };
    const undo = async count => {
      for (let i = 0; i < count; i++) await click(drawer + ' button', '撤销');
    };
    if (kind === 'descriptive') {
      await select('review');
      const collapsed = await replace('replace-with-collapsed-subprocess');
      await select(collapsed); await replace('replace-with-user-task'); await undo(2);
      await select('review'); await replace('replace-with-expanded-subprocess'); await undo(1);
      await select('document_ref');
      const store = await replace('replace-with-data-store-reference');
      await select(store); await replace('replace-with-data-object-reference'); await undo(2);
      await select('start'); await replace('replace-with-none-end'); await undo(1);
      await select('end'); await replace('replace-with-none-start'); await undo(1);
      // A user task's outgoing flow supports conditional/default/ordinary flow variants.
      const selectFlow = async id => {
        if (await selected() === id) return;
        const point = await poll(() => evaluate(`(()=>{const p=document.querySelector('${drawer} .djs-element[data-element-id="${id}"] .djs-hit');if(!p)return;
          const points=[.2,.8,.5,.1,.9].map(f=>{const v=p.getPointAtLength(p.getTotalLength()*f);return new DOMPoint(v.x,v.y).matrixTransform(p.getScreenCTM())});
          const hit=points.find(v=>document.elementFromPoint(v.x,v.y)?.closest('.djs-element')?.getAttribute('data-element-id')==='${id}');return hit?{x:hit.x,y:hit.y}:undefined})()`), 'reachable flow');
        await send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...point, buttons: 0});
        await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
        await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1});
        await poll(async () => await selected() === id, 'flow selected');
      };
      await selectFlow('finish'); await replace('replace-with-conditional-flow');
      await selectFlow('finish'); await replace('replace-with-sequence-flow'); await undo(2);
      await selectFlow('finish'); await replace('replace-with-default-flow'); await undo(1);
    } else {
      await select('split');
      let id = await replace('replace-with-exclusive-gateway');
      await select(id); id = await replace('replace-with-parallel-gateway');
      await select(id); await replace('replace-with-inclusive-gateway'); await undo(3);
    }
    await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
    // Undo restores the original graph. A dirty marker can still require explicit discard.
    if (await evaluate("[...document.querySelectorAll('.ant-modal button')].some(e=>e.textContent.replaceAll(' ','')==='放弃修改')")) {
      await click('.ant-modal button', '放弃修改');
    }
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'designer closed after undo');
    assert.deepEqual(await call('/workflow/definitions/' + definitionId), original);
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
