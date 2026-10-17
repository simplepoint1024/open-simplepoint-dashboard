import assert from 'node:assert/strict';

export async function smokeHeaderLayout({send, evaluate, poll}) {
  await send('Emulation.setDeviceMetricsOverride', {width: 1024, height: 700, deviceScaleFactor: 1, mobile: false});
  await poll(() => evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), 'narrow header does not create document scrolling');
  const overflow = await evaluate(`(() => {
    const item = document.querySelector('.nb-top-menu-right .ant-menu-overflow-item-rest');
    return !!item && getComputedStyle(item).opacity !== '0' && item.getBoundingClientRect().width > 0;
  })()`);
  if (overflow) {
    const point = await poll(() => evaluate(`(() => {
      const title = document.querySelector('.nb-top-menu-right .ant-menu-overflow-item-rest .ant-menu-submenu-title');
      if (!title) return; const rect = title.getBoundingClientRect();
      const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
      return title.contains(document.elementFromPoint(x, y)) ? {x, y} : undefined;
    })()`), 'overflow menu is pointer reachable');
    await send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...point});
    await poll(() => evaluate(`[...document.querySelectorAll('.ant-menu-submenu-popup .nb-tools-item')]
      .some(item => {const button=item.querySelector('button'); if (!button) return false;
        const rect=button.getBoundingClientRect();
        return rect.width>0 && button.contains(document.elementFromPoint(rect.x+rect.width/2,rect.y+rect.height/2));})`),
      'overflowed navigation tools remain reachable');
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true,
      'opening overflow tools does not expand the document');
  }
  await send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: 500, y: 600});
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  console.log('PASS: responsive header bounds and reachable overflow tools at 1024 px');
}
