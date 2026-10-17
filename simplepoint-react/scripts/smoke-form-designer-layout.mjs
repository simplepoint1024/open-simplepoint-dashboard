import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';

/** Real DOM measurements catch nested-grid and viewport regressions that model tests cannot. */
export async function smokeFormDesignerLayout({action, clickReady, clickText, fill, poll, evaluate, send}) {
  const root = '.nb-tab-panel-active';
  const node = id => `${root} [data-design-node="${id}"]`;
  const selectedId = () => evaluate(`document.querySelector('${root} .sp-form-canvas-node.is-selected').dataset.designNode`);
  const insert = type => clickText(`${root} [data-palette-type="${type}"] button`, type === 'layout.grid' ? '栅格' : type === 'layout.group' ? '分组' : '单行文本');
  const context = async (id, action) => {
    await evaluate(`document.querySelector('${node(id)}').dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,clientX:650,clientY:350}))`);
    await clickText('.ant-dropdown:not(.ant-dropdown-hidden) .ant-dropdown-menu-item', action);
  };
  const initialCount = await evaluate(`document.querySelectorAll('${root} [data-design-node]').length`);
  await clickReady(root + ' .sp-form-canvas-node.is-root > .sp-form-canvas-title');
  await insert('layout.grid'); const grid = await selectedId();
  await insert('layout.group'); const group = await selectedId();
  await insert('field.text'); const first = await selectedId();
  await fill(root + ' #design_property_columnSpan', '12');
  await insert('field.text'); const second = await selectedId();
  await fill(root + ' #design_property_columnSpan', '12');
  const bounds = await evaluate(`(() => {
    const first=document.querySelector('${node(first)}').getBoundingClientRect(), second=document.querySelector('${node(second)}').getBoundingClientRect();
    const label=document.querySelector('${root} label[for="design_property_fieldLabel"]').getBoundingClientRect();
    const input=document.querySelector('${root} #design_property_fieldLabel').getBoundingClientRect();
    return {first:{left:first.left,right:first.right,top:first.top}, second:{left:second.left,top:second.top}, label:{top:label.top,bottom:label.bottom,right:label.right},input:{left:input.left,top:input.top,bottom:input.bottom}};
  })()`);
  assert.ok(Math.abs(bounds.first.top - bounds.second.top) < 2, 'half-width fields share the same row inside nested layouts');
  assert.ok(bounds.first.right <= bounds.second.left, 'nested grid columns never overlap');
  assert.ok(bounds.label.right <= bounds.input.left && bounds.label.top < bounds.input.bottom && bounds.input.top < bounds.label.bottom,
    'property label and input stay on one row');
  await context(first, '复制');
  await context(group, '粘贴');
  const pasted = await selectedId();
  assert.notEqual(pasted, first);
  assert.equal(await evaluate(`document.querySelector('${node(pasted)}').parentElement.parentElement.closest('[data-design-node]').dataset.designNode`), group,
    'right-click paste targets the clicked container');
  await context(first, '移除控件');
  assert.equal(await evaluate(`!!document.querySelector('${node(first)}')`), false, 'right-click remove targets the clicked unselected node');
  assert.equal(await evaluate(`!!document.querySelector('${node(pasted)}')`), true, 'right-click never removes the previous selection');
  for (let index = 0; index < 12; index++) await insert('field.text');
  for (const viewport of [{width:1600,height:1000},{width:1024,height:768}]) {
    await send('Emulation.setDeviceMetricsOverride', {...viewport, deviceScaleFactor:1,mobile:false});
    await poll(() => evaluate(`document.querySelector('${root} .sp-form-canvas-scroll').clientHeight > 100`), 'fixed-height canvas ready');
    const geometry = await evaluate(`(() => {
      const canvas=document.querySelector('${root} .sp-form-canvas-scroll'), workspace=document.querySelector('${root} .app-form-workspace'),
        editor=document.querySelector('${root} .sp-form-designer-workspace');
      canvas.scrollTop=canvas.scrollHeight;
      return {outerOverflow:workspace.scrollHeight-workspace.clientHeight, bottom:workspace.getBoundingClientRect().bottom,
        innerScroll:canvas.scrollTop, innerOverflow:canvas.scrollHeight-canvas.clientHeight,
        paletteOverflow:document.querySelector('${root} .sp-form-designer-palette').scrollWidth-document.querySelector('${root} .sp-form-designer-palette').clientWidth,
        canvasWidthOverflow:canvas.scrollWidth-canvas.clientWidth, gridContained:document.querySelector('${node(grid)}').getBoundingClientRect().width<=editor.scrollWidth};
    })()`);
    const header = await evaluate(`(() => {
      const bounds = selector => { const r = document.querySelector('${root} ' + selector).getBoundingClientRect(); return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,center:r.left+r.width/2}; };
      return {height:bounds('.sp-form-designer-header'), scenes:bounds('.sp-form-designer-scenes'), tabs:bounds('.sp-form-designer-tabs'), identity:bounds('.app-form-editor-identity'), toolbar:bounds('.sp-form-designer-toolbar'),
        buttons:[...document.querySelectorAll('${root} .sp-form-designer-toolbar button')].map(b=>b.getAttribute('aria-label'))};
    })()`);
    assert.ok(header.height.bottom-header.height.top <= 38 && header.scenes.bottom-header.scenes.top <= 38, 'both designer bars keep compact heights: ' + JSON.stringify(header));
    assert.deepEqual(header.buttons.filter(Boolean), ['返回','预览','表单属性','操作'], 'one toolbar has return before preview and actions after it');
    assert.ok(header.tabs.right <= header.identity.left && header.identity.right <= header.toolbar.left, 'title and save status do not overlap tabs or actions');
    assert.ok(Math.abs(header.identity.center - (header.tabs.right + header.toolbar.left) / 2) <= 2, 'title and save status are centered between tabs and actions');
    assert.ok(header.toolbar.right <= viewport.width && header.tabs.top < header.toolbar.bottom && header.toolbar.top < header.tabs.bottom, 'tabs and actions share one bounded row');
    assert.ok(geometry.outerOverflow <= 2 && geometry.bottom <= viewport.height, 'designer fits its page without outer vertical scrolling');
    assert.ok(geometry.innerOverflow > 0 && geometry.innerScroll > 0, 'long forms scroll within the canvas');
    assert.ok(geometry.paletteOverflow <= 2 && geometry.canvasWidthOverflow <= 2 && geometry.gridContained, 'palette and nested fields do not overflow horizontally');
  }
  await send('Emulation.setDeviceMetricsOverride', {width:1600,height:1000,deviceScaleFactor:1,mobile:false});
  await evaluate(`document.querySelector('${root} .sp-form-canvas-scroll').scrollTop=0`);
  if (process.env.MF_SMOKE_FORM_LAYOUT_SCREENSHOT) {
    const screenshot=await send('Page.captureScreenshot',{format:'png'});
    await writeFile(process.env.MF_SMOKE_FORM_LAYOUT_SCREENSHOT,Buffer.from(screenshot.data,'base64'));
  }
  await clickReady(root + ' .sp-form-designer-toolbar button[aria-label="预览"]');
  const preview = '.sp-form-designer-preview:not([style*="display: none"]) .ant-modal';
  await poll(() => evaluate(`!!document.querySelector('${preview} .sp-form-runtime-field input')`), 'nested layout preview ready');
  assert.equal(await evaluate(`[...document.querySelectorAll('${preview} .sp-form-runtime-grid')].every(grid=>grid.scrollWidth<=grid.clientWidth+2)`), true,
    'runtime preview uses bounded gutters in nested 24-unit grids');
  await poll(() => evaluate(`(() => {const e=document.querySelector('${preview} .sp-form-preview-viewport');return Math.abs(e.getBoundingClientRect().width-e.offsetWidth)<1;})()`), 'preview opening animation completed');
  const previewInput=preview+' .sp-form-runtime-field input';
  await fill(previewInput, '三端预览保留内容');
  for (const [device,width] of [['Phone',390],['iPad',768],['PC',1120]]) {
    await clickText(preview+' .ant-segmented-item-label', device);
    await poll(() => evaluate(`document.querySelector('${preview} .sp-form-preview-viewport')?.dataset.device==='${device}'`), 'preview device '+device);
    await poll(() => evaluate(`Math.abs(document.querySelector('${preview} .sp-form-preview-viewport').getBoundingClientRect().width-${width})<1`), 'settled device width '+device);
    assert.equal(await evaluate(`document.querySelector('${preview} .sp-form-preview-viewport').offsetWidth`),width);
    assert.equal(await evaluate(`document.querySelector('${previewInput}').value`),'三端预览保留内容');
    if (device==='Phone') assert.equal(await evaluate(`[...document.querySelectorAll('${preview} .sp-form-runtime-field')].every(f=>getComputedStyle(f).gridColumn==='1 / -1')`),true,'phone uses the runtime responsive field layout');
  }
  console.log('PASS: PC/iPad/Phone viewport widths, phone responsive fields, and retained trial input across switches');
  await clickReady(preview + ' .ant-modal-close');
  // Restore the original design through real undo commands, including removed field definitions.
  for (let index = 0; index < 20; index++) await action('撤销');
  assert.equal(await evaluate(`document.querySelectorAll('${root} [data-design-node]').length`),initialCount);
  console.log('PASS: grouped icon palette, right-click target identity, nested half-width grids, horizontal properties and bounded scrolling at 1600/1024px');
}
