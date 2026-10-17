import type {FormComponentDefinition, FormFieldDefinition} from './contracts.ts';

export type FormActionKind = 'save' | 'submit' | 'reset' | 'open-dialog' | 'open-drawer' | 'close' | 'apply';
export type FormComponentRenderer = {
  type: string;
  version: string;
} & (
  | {kind: 'container'; layout: 'grid' | 'group' | 'tabs' | 'matrix' | 'cell'}
  | {kind: 'field'; widget?: string | ((field: FormFieldDefinition) => string | undefined)}
  | {kind: 'detail'}
  | {kind: 'display'}
  | {kind: 'action'; action: FormActionKind}
);

/** Explicit trusted registrations; published documents never import executable modules. */
export class FormComponentRegistry {
  private readonly entries: ReadonlyMap<string, FormComponentRenderer>;

  constructor(components: readonly FormComponentRenderer[]) {
    const entries = new Map<string, FormComponentRenderer>();
    for (const component of components) {
      const key = `${component.type}@${component.version}`;
      if (entries.has(key)) throw new Error(`Duplicate form component renderer: ${key}`);
      entries.set(key, Object.freeze({...component}));
    }
    this.entries = entries;
  }

  resolve(component: Pick<FormComponentDefinition, 'componentType' | 'componentVersion'>) {
    const key = `${component.componentType}@${component.componentVersion}`;
    const renderer = this.entries.get(key);
    if (!renderer) throw new Error(`Unsupported form component renderer: ${key}`);
    return renderer;
  }

  descriptors(): readonly FormComponentRenderer[] {return [...this.entries.values()];}
}

const field = (type: string, widget?: Extract<FormComponentRenderer, {kind: 'field'}>['widget']): FormComponentRenderer =>
  ({type, version: '1', kind: 'field', widget});
const action = (key: FormActionKind): FormComponentRenderer =>
  ({type: `action.${key}`, version: '1', kind: 'action', action: key});

export const platformFormComponents = new FormComponentRegistry([
  {type: 'data.detail', version: '1', kind: 'detail'},
  ...(['form', 'grid', 'group', 'tabs'] as const).map(type => ({type: `layout.${type}`, version: '1',
    kind: 'container' as const, layout: type === 'form' ? 'grid' as const : type})),
  {type: 'layout.matrix', version: '1', kind: 'container', layout: 'matrix'},
  {type: 'layout.cell', version: '1', kind: 'container', layout: 'cell'},
  {type: 'display.text', version: '1', kind: 'display'},
  ...['divider', 'image', 'alert', 'progress', 'icon', 'markdown'].map(type => ({type: `display.${type}`, version: '1', kind: 'display' as const})),
  field('field.rating', 'Rating'), field('field.cascader', 'Cascader'), field('field.transfer', 'Transfer'),
  field('field.text'), field('field.textarea', 'textarea'), field('field.json', 'textarea'), field('field.switch', 'Switch'),
  field('field.number', value => ['DECIMAL', 'INT64'].includes(value.type) ? 'Decimal' : undefined),
  field('field.select'), field('field.date'), field('field.time', 'Time'), field('field.datetime'),
  field('field.user', 'UserSelect'), field('field.role', 'RoleSelect'),
  field('field.organization', 'OrganizationSelect'), field('field.tenant', 'TenantSelect'),
  field('field.attachment', 'FormAttachments'),
  ...(['save', 'submit', 'reset', 'open-dialog', 'open-drawer', 'close', 'apply'] as const).map(action),
]);
