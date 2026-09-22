import type {MouseEvent} from 'react';

/** Keep row selection separate from controls embedded in a cell. */
export function isRowSelectionClick(event: MouseEvent<HTMLElement>): boolean {
  const target = event.target;
  if (!(target instanceof Element)) return false;
  if (event.defaultPrevented || window.getSelection()?.toString()) return false;
  return !target.closest('button, a, input, textarea, select, label, [role="button"], [role="checkbox"], '
    + '[role="combobox"], .ant-select, .ant-dropdown, .ant-popover, '
    + '[contenteditable="true"], .ant-table-selection-column, .ant-table-row-expand-icon, '
    + '[data-row-selection-ignore]');
}
