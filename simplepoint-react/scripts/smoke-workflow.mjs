import assert from 'node:assert/strict';

export async function smokeWorkflow({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture, capturePreview}) {
  await evaluate(`localStorage.setItem('sp.tenantId','workflow-smoke-tenant');window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'workflow-smoke-tenant'}));`);
  const page = '.nb-tab-panel-active';
  const drawer = '.ant-drawer-open';
  const selectCanvasElement = async element => {
    const point = await poll(() => evaluate(`(() => {
      const designer=document.querySelector('${drawer} .workflow-designer');
      if(!designer || designer.getAttribute('aria-busy')!=='false'
        || document.getAnimations().some(animation=>animation.playState==='running'
          && animation.effect?.getComputedTiming().iterations!==Infinity
          && animation.effect?.target?.closest?.('.ant-drawer'))) return;
      const hit=designer.querySelector('.djs-element[data-element-id="${element}"] .djs-hit');
      if(!hit) return;
      const rect=hit.getBoundingClientRect();
      const points=hit.tagName==='path' ? [0.5,0.15,0.85,0.05,0.95].map(fraction=> {
        const local=hit.getPointAtLength(hit.getTotalLength()*fraction);
        return new DOMPoint(local.x,local.y).matrixTransform(hit.getScreenCTM());
      }) : [{x:rect.x+rect.width/2,y:rect.y+rect.height/2}];
      const point=points.find(({x,y})=>document.elementFromPoint(x,y)?.closest('.djs-element')?.getAttribute('data-element-id')==='${element}');
      return point ? {x:point.x,y:point.y} : undefined;})()`), `${element} is loaded and pointer-reachable after the drawer transition`).catch(async error => {
        console.log(await evaluate(`(() => {
          const hit=document.querySelector('${drawer} .djs-element[data-element-id="${element}"] .djs-hit');
          const rect=hit?.getBoundingClientRect(), x=rect?.x+rect?.width/2,y=rect?.y+rect?.height/2;
          return {point:{x,y},busy:document.querySelector('${drawer} .workflow-designer')?.outerHTML.slice(0,150),
            target:document.elementFromPoint(x,y)?.outerHTML.slice(0,400),hit:hit?.outerHTML,
            animations:document.getAnimations().map(animation=>({state:animation.playState,target:animation.effect?.target?.className}))};})()`));
        if (capturePreview) await capturePreview(); throw error;
      });
    await send('Input.dispatchMouseEvent', {...point, type: 'mouseMoved', buttons: 0});
    await send('Input.dispatchMouseEvent', {...point, type: 'mousePressed', button: 'left', buttons: 1, clickCount: 1});
    await send('Input.dispatchMouseEvent', {...point, type: 'mouseReleased', button: 'left', buttons: 0, clickCount: 1});
  };
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="definition1"]')`), 'workflow definition list');
  assert.equal(await evaluate(`!!document.querySelector('${page} .ant-card')`), false, 'no extra feature-specific card');
  assert.equal(await evaluate(`!!document.querySelector('${page} [aria-label="表格设置"]')`), true, 'platform table tools');
  await clickText(`${page} tr[data-row-key="definition1"] button`, '基础信息');
  await poll(() => evaluate(`!!document.querySelector('${drawer} input[id="root_name"]')`), 'metadata platform form');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .workflow-designer')`), false, 'basic metadata is separate from process design');
  await fill(`${drawer} input[id="root_name"]`, '统一样式审批');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => fixture.writes.length === 1, 'metadata save');
  assert.equal(fixture.writes[0].data.name, '统一样式审批');
  assert.equal(fixture.writes[0].data.formContract.variables[0].type, 'user');
  await clickReady(`${drawer} .ant-drawer-close`);
  await clickText(`${page} tr[data-row-key="definition1"] a`, 'v2');
  await poll(() => evaluate(`!!document.querySelector('${drawer} tr[data-row-key="version1"]')`), 'version field opens published history');
  await clickText(`${drawer} tr[data-row-key="version1"] button`, '查看版本');
  await poll(() => evaluate(`!!document.querySelector('${drawer} .djs-container svg')`), 'read-only historical BPMN');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .djs-palette')`), false, 'published version has no editing palette');
  assert.ok(await evaluate(`document.querySelector('${drawer} .workflow-version-preview').textContent.includes('历史名称')`),
    'historical preview displays snapshotted metadata, not the current definition name');
  await clickText(`${drawer} tr[data-row-key="version1"] button`, '发起流程');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal .ant-select')`), 'typed start form with user selector');
  await evaluate(`document.querySelector('.ant-modal #root_approver').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await poll(() => fixture.requests.some(request => request.path === '/common/tenant-directory/users'), 'bounded tenant user search');
  await clickText('.ant-select-dropdown .ant-select-item-option', '租户成员 1');
  await poll(() => evaluate(`!!document.querySelector('.workflow-process-start button[type="submit"]:not(:disabled)')`), 'required user value enables submission');
  await clickText('.workflow-process-start button[type="submit"]', '发起流程');
  await poll(() => fixture.writes.some(write => write.path.endsWith('/instances')), 'historical start submitted');
  const started = fixture.writes.find(write => write.path.endsWith('/instances')).data;
  assert.equal(started.version, 1, 'start pins the chosen published version');
  assert.deepEqual(started.variables, {approver: 'member-1'});
  await clickReady(`${drawer} .ant-drawer-close`);
  await clickText(`${page} tr[data-row-key="definition1"] button`, '流程设计');
  await poll(() => evaluate(`!!document.querySelector('${drawer} .djs-palette')`), 'lazy process designer');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .djs-palette [data-action="create.task"]')`), false,
    'new palette tasks must not silently create an auto-completing generic Task');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} .djs-palette [data-action="create.user-task"]')`), true,
    'platform palette exposes a real human user task');
  await selectCanvasElement('review');
  await poll(() => evaluate(`!!document.querySelector('${drawer} .workflow-designer__properties .ant-select')`), 'node assignment controls');
  assert.equal(await evaluate(`document.querySelectorAll('${drawer} [data-domain-select="user"]').length`), 1,
    'candidate users use the public selector while the saved assignee remains a variable');
  const assignee = `${drawer} .workflow-designer__properties .ant-form-item:has(.ant-segmented)`;
  await evaluate(`document.querySelector('${assignee}').querySelectorAll('.ant-segmented-item')[0].click()`);
  await poll(() => evaluate(`document.querySelectorAll('${drawer} [data-domain-select="user"]').length===2`),
    'fixed assignee and candidate users both use the public user selector');
  await evaluate(`document.querySelector('${assignee}').querySelectorAll('.ant-segmented-item')[1].click()`);
  await poll(() => evaluate(`document.querySelectorAll('${drawer} [data-domain-select="user"]').length===1`),
    'changing only the editing view preserves the saved variable');
  assert.equal(await evaluate(`document.querySelectorAll('${drawer} [data-domain-select="role"]').length`), 1);
  await poll(() => fixture.requests.some(request => request.path === '/common/tenant-directory/roles/selected')
    && evaluate(`document.querySelector('${drawer} .workflow-designer__properties')?.textContent.includes('审批角色')`), 'candidate role labels hydrate by authority');
  assert.equal(await evaluate(`document.querySelector('${drawer} .workflow-designer__properties').textContent.includes('审批角色')`), true, 'candidate role uses a shared role selector');
  assert.equal(await evaluate(`document.querySelector('${drawer} .workflow-designer__properties input[aria-label="天"]')?.value`), '1',
    'existing relative due date is visible in ordinary mode');
  assert.equal(await evaluate(`document.querySelector('${drawer} .workflow-designer__properties input[aria-label="小时"]')?.value`), '2');
  const name = `${drawer} .workflow-designer__properties input.ant-input`;
  await fill(name, '立即保存节点');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => fixture.writes.length >= 3, 'canvas save flushes current XML');
  assert.ok(fixture.writes.at(-1).data.bpmnXml.includes('立即保存节点'), 'no debounce-induced lost edit');
  assert.ok(fixture.writes.at(-1).data.bpmnXml.includes('assignee="${approver}"'), 'selector view switching does not replace dynamic assignment');
  assert.ok(fixture.writes.at(-1).data.bpmnXml.includes('P1DT2H'), 'unmodified ISO duration is retained');
  await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer')?.getAttribute('aria-busy')==='false'`),
    'save finished and canvas accepts pointer input');
  for (const [element, label] of [['flow1', '审批角色'], ['flow2', '租户成员 1']]) {
    await selectCanvasElement(element);
    await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer__element-id')?.textContent.includes('${element}')`),
      `${element} property panel switched to the selected sequence flow`).catch(async error => {
        console.log(JSON.stringify(await evaluate(`({selected: document.querySelector('${drawer} .workflow-designer__element-id')?.textContent,
          allPanels: [...document.querySelectorAll('.workflow-designer__element-id')].map(element=>({text:element.textContent,
            visibility:getComputedStyle(element).visibility,rect:element.getBoundingClientRect().toJSON(),
            parents:(()=>{let node=element,result=[];for(let i=0;node&&i<7;i++,node=node.parentElement)result.push(node.className);return result;})()})),
          edge: document.querySelector('${drawer} .djs-element[data-element-id="${element}"]')?.outerHTML.slice(0, 1600)})`), null, 2));
        if (capturePreview) await capturePreview(); throw error;
      });
    assert.equal(await evaluate(`document.querySelectorAll('${drawer} .workflow-designer__element-id').length`), 1,
      'switching nodes must remove the previous property form, not stack it above the new node');
    await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer__properties')?.textContent.includes('${label}')`),
      `${element} ordinary condition uses directory labels instead of an ID text input`);
    assert.equal(await evaluate(`document.querySelectorAll('${drawer} .workflow-designer__properties .ant-select').length`), 4,
      'condition reuses shared group/field/operator/directory controls');
    assert.ok(await evaluate(`[...document.querySelectorAll('${drawer} .workflow-designer__properties button')].some(button=>button.textContent==='添加条件')`),
      'a saved single comparison can add sibling conditions without first rewriting the expression');
    await clickText(`${drawer} .workflow-designer__properties .ant-segmented-item`, '高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${drawer} .workflow-designer__properties .cm-content')`), 'lazy advanced expression editor');
    const original = await evaluate(`document.querySelector('${drawer} .workflow-designer__properties .cm-content').textContent`);
    await clickText(`${drawer} .workflow-designer__properties .ant-segmented-item`, '条件配置');
    await poll(() => evaluate(`!document.querySelector('${drawer} .workflow-designer__properties .cm-content')`), 'lossless ordinary mode conversion');
    await clickText(`${drawer} .workflow-designer__properties .ant-segmented-item`, '高级表达式');
    await poll(() => evaluate(`!!document.querySelector('${drawer} .workflow-designer__properties .cm-content')`), 'advanced editor restored');
    assert.equal(await evaluate(`document.querySelector('${drawer} .workflow-designer__properties .cm-content').textContent`), original,
      'unchanged switching must not rewrite persisted expressions');
    await clickText(`${drawer} .workflow-designer__properties .ant-segmented-item`, '条件配置');
  }
  const writesBeforeIncomplete = fixture.writes.length;
  await clickText(`${drawer} .workflow-designer__properties button`, '添加条件');
  await poll(() => evaluate(`document.querySelector('${drawer} .workflow-designer__properties')?.textContent.includes('请填写有效的条件值')`),
    'an incomplete visual rule blocks submission without erasing the saved condition');
  await selectCanvasElement('review');
  await selectCanvasElement('flow2');
  await poll(() => evaluate(`document.querySelectorAll('${drawer} .workflow-designer__properties button[aria-label="移除条件"]').length===2
    && document.querySelector('${drawer} .workflow-designer__properties')?.textContent.includes('请填写有效的条件值')`),
    'node switching retains the incomplete visual draft and its validation error');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.body.textContent.includes('请完成当前属性配置后保存')`), 'incomplete property save is rejected locally');
  assert.equal(fixture.writes.length, writesBeforeIncomplete, 'invalid draft must not issue a save request');
  await evaluate(`document.querySelectorAll('${drawer} .workflow-designer__properties button[aria-label="移除条件"]')[1].click()`);
  await poll(() => evaluate(`!document.querySelector('${drawer} .workflow-designer__properties')?.textContent.includes('请填写有效的条件值')`),
    'removing incomplete condition restores validity');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => fixture.writes.length === writesBeforeIncomplete + 1
    && evaluate(`document.querySelector('${drawer} .workflow-designer')?.getAttribute('aria-busy')==='false'`), 'valid draft can be saved again');
  if (capturePreview) await capturePreview();
  await clickReady(`${drawer} .ant-drawer-close`);
  await clickMenu('流程任务测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="task1"]')`), 'workflow tasks list');
  await clickText(`${page} tr[data-row-key="task1"] button`, '办理');
  await poll(() => evaluate(`!!document.querySelector('${drawer} input[type="checkbox"]')`), 'normal typed task form');
  await clickText(`${drawer} button`, '通过');
  await poll(() => fixture.writes.some(write => write.path.endsWith('/complete')), 'task submitted');
  assert.deepEqual(fixture.writes.find(write => write.path.endsWith('/complete')).data.variables, {approved: false}, 'false is a valid required boolean');
  await poll(() => evaluate(`!document.querySelector('.workflow-task-editor.ant-drawer-open')`), 'approval transaction has finished');
  await evaluate(`localStorage.setItem('sp.tenantId','__platform__');window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'__platform__'}));`);
  console.log('PASS: workflow platform layout, separate metadata/design, published history, user/role selectors, immutable start version, canvas save flush, typed task submission');
}
