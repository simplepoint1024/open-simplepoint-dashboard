import {useEffect, useRef} from 'react';

/** Retained overlays hide their DOM without unmounting fields or notifying their widgets. */
export function usePopupOwner(open: boolean, close: () => void) {
  const owner = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open || !owner.current) return;
    const node = owner.current;
    const ancestors: HTMLElement[] = [];
    for (let element = node.parentElement; element; element = element.parentElement) ancestors.push(element);
    const hidden = () => !node.isConnected || ancestors.some(element =>
      element.hidden || element.inert || element.getAttribute('aria-hidden') === 'true'
      || element.style.display === 'none' || element.style.visibility === 'hidden'
      || (element.classList.contains('ant-drawer') && !element.classList.contains('ant-drawer-open'))
      || [...element.classList].some(name => /-leave(?:-active|-prepare|-start)?$/.test(name)));
    const check = () => {if (hidden()) close();};
    const observer = new MutationObserver(check);
    ancestors.forEach(element => observer.observe(element,
      {attributes: true, attributeFilter: ['class', 'style', 'hidden', 'inert', 'aria-hidden']}));
    check();
    return () => observer.disconnect();
  }, [open, close]);
  return owner;
}

export function popupContainer(node: HTMLElement): HTMLElement {
  return node.closest<HTMLElement>('.ant-modal-container, .ant-modal-content, .ant-drawer-section, .ant-drawer-content')
    ?? node.ownerDocument.body;
}
