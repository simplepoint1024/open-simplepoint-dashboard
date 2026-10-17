// A reload must outlive the old Runtime execution context. Do not evaluate
// location.reload() and await that soon-to-be-destroyed context's response.
export function createPageReloader({send, poll}) {
  const loadedFrames = new Map();
  const onEvent = message => {
    if (message.method === 'Page.lifecycleEvent' && message.params.name === 'load') {
      loadedFrames.set(message.params.frameId, message.params.loaderId);
    }
  };
  const reload = async () => {
    const before = (await send('Page.getFrameTree')).frameTree.frame;
    await send('Page.reload', {ignoreCache: true});
    await poll(async () => {
      const frame = (await send('Page.getFrameTree')).frameTree.frame;
      return frame.id === before.id && !!frame.loaderId && frame.loaderId !== before.loaderId
        && loadedFrames.get(frame.id) === frame.loaderId;
    }, 'new document completes the requested reload');
  };
  return {onEvent, reload};
}

// Check readiness and act in one Runtime task: a separate read/click can race
// React unmounting a scoped control. A successful click must never be retried.
export function createReadyControlClicker({evaluate, poll}) {
  return async selector => {
    const match = `[...document.querySelectorAll(${JSON.stringify(selector)})].find(control=>control.getClientRects().length && !control.disabled && !control.classList.contains('ant-btn-loading'))`;
    await poll(() => evaluate(`(()=>{const control=${match};if(!control)return false;control.click();return true;})()`),
      'enabled visible non-loading control ' + selector);
  };
}

// Segmented controls are labels around radios: label.disabled does not reflect
// the underlying input or a disabled parent. Wait for the real user action to unlock.
export function createReadyTextClicker({evaluate, poll}) {
  return async (selector, text) => {
    const match = `[...document.querySelectorAll(${JSON.stringify(selector)})].find(control=>
      control.getClientRects().length && !control.disabled && !control.classList.contains('ant-btn-loading')
      && !control.closest('[aria-disabled="true"],.ant-segmented-disabled,.ant-segmented-item-disabled,.ant-pagination-disabled')
      && !control.querySelector('input:disabled')
      && control.textContent.replaceAll(' ','')===${JSON.stringify(text.replaceAll(' ', ''))})`;
    await poll(() => evaluate(`(()=>{const control=${match};if(!control)return false;control.click();return true;})()`),
      'enabled visible non-loading action ' + text);
  };
}
