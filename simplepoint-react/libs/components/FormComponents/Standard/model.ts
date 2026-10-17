import type {FormComponentDefinition} from '../../FormRuntime/contracts.ts';
export type SelectionOption = {value: string; label: string; children?: SelectionOption[]};
export function optionDraft(raw: string | undefined): SelectionOption[] {
  try {const value: unknown = JSON.parse(raw ?? '[]'); return Array.isArray(value) ? value : [];} catch {return [];}
}
export function selectionOptions(raw: string | undefined): SelectionOption[] {
  try {
    let count = 0;
    const clean = (value: unknown, depth: number): SelectionOption[] => !Array.isArray(value) || depth > 6 ? []
      : value.filter(item => item && typeof item.value === 'string' && item.value && typeof item.label === 'string')
        .filter(() => ++count <= 200).map(item => ({value: item.value, label: item.label,
          ...(Array.isArray(item.children) && item.children.length ? {children: clean(item.children, depth + 1)} : {})}));
    return clean(optionDraft(raw), 1);
  } catch {return [];}

}
export function selectedKeys(raw: unknown): string[] {
  try {const parsed: unknown = JSON.parse(typeof raw === 'string' ? raw : '[]');
    return Array.isArray(parsed) && parsed.every(key => typeof key === 'string') ? parsed : [];} catch {return [];}
}
export function safeDisplayUrl(value: string): string {
  if (value.length > 2048 || /[\x00-\x20\x7f\\]/.test(value)) return '';
  if (value.startsWith('/') && !value.startsWith('//')) return value;
  try {const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? value : '';} catch {return '';}
}
export const standardComponentTypes = ['display.divider', 'display.image', 'display.alert', 'display.progress',
  'display.icon', 'display.markdown', 'field.rating', 'field.cascader', 'field.transfer'] as const;
export function displayText(node: FormComponentDefinition, key: string, fallback = '') {return node.properties[key]?.value ?? fallback;}
