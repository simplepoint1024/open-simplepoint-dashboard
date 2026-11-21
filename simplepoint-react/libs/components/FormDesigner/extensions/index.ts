import {standardComponentTypes} from '../../FormComponents/Standard/model';
import StandardProperties from '../../FormComponents/Standard/Properties';
import StandardPreview from '../../FormComponents/Standard/Preview';
import DetailPreview from '../../FormComponents/Detail/Preview';
import type {ComponentType} from 'react';
import type {FormComponentDefinition, FormFieldWritePolicy} from '../../FormRuntime/contracts';
import type {ComponentDescriptor, DesignDocument} from '../contracts';
import MatrixProperties from '../../FormComponents/Matrix/Properties';
import {DetailFieldsProperties, DetailIndexesProperties} from '../../FormComponents/Detail/Properties';
import FieldCalculationProperties from '../FieldCalculationProperties';
import FieldDependencyProperties from '../FieldDependencyProperties';
import ReferenceProperties from '../../FormComponents/Reference/Properties';

export type DesignerPropertyEditorProps = {
  node: FormComponentDefinition; document: DesignDocument; components: readonly ComponentDescriptor[];
  writePolicies: readonly FormFieldWritePolicy[]; disabled: boolean;
  onChange: (node: FormComponentDefinition) => void; onDocumentChange: (document: DesignDocument) => void;
  onEditField?: (mode: 'stored' | 'calculated') => void;
};
/** Every property extension declares its own stable tab identity, icon and content. */
export type DesignerPropertyTab = {key: string; labelKey: string; label: string; icon: string; component: ComponentType<DesignerPropertyEditorProps>};
export const calculationPropertyTab: DesignerPropertyTab = {
  key: 'calculation', labelKey: 'app.calculation.title', label: '计算公式', icon: 'CalculatorOutlined', component: FieldCalculationProperties,
};
export const dependencyPropertyTab: DesignerPropertyTab = {
  key: 'dependencies', labelKey: 'app.calculation.dependencies', label: '依赖关系', icon: 'BranchesOutlined', component: FieldDependencyProperties,
};
export class DesignerPropertyRegistry {
  private readonly entries = new Map<string, readonly DesignerPropertyTab[]>();
  constructor(entries: readonly (readonly [string, readonly DesignerPropertyTab[]])[]) {
    for (const [type, tabs] of entries) {
      if (this.entries.has(type) || tabs.some(tab => !tab.key || ['general', 'calculation', 'dependencies'].includes(tab.key) || !tab.icon || !tab.labelKey)
        || new Set(tabs.map(tab => tab.key)).size !== tabs.length) throw new Error('Invalid designer property tabs');
      this.entries.set(type, Object.freeze(tabs.map(tab => Object.freeze({...tab}))));
    }
  }
  get(type: string) {return this.entries.get(type) ?? [];}
}
export const designerPropertyEditors = new DesignerPropertyRegistry([
  ['field.reference@1', [{key: 'relation', labelKey: 'app.relation.settings', label: '关联配置', icon: 'LinkOutlined', component: ReferenceProperties}]],
  ...standardComponentTypes.filter(type => type !== 'display.divider').map(type => [type + '@1', [{key: 'content', labelKey: 'app.designer.componentSettings', label: '组件配置', icon: 'SettingOutlined', component: StandardProperties}]] as const),
  ['layout.matrix@1', [{key: 'layout', labelKey: 'app.designer.groupLayout', label: '布局与展示', icon: 'LayoutOutlined', component: MatrixProperties}]],
  ['data.detail@1', [
    {key: 'fields', labelKey: 'app.designer.fields', label: '字段', icon: 'UnorderedListOutlined', component: DetailFieldsProperties},
    {key: 'indexes', labelKey: 'app.designer.indexes', label: '索引', icon: 'DatabaseOutlined', component: DetailIndexesProperties},
  ]],
]);

export const designerPreviews = new Map<string, ComponentType<Pick<DesignerPropertyEditorProps, 'node' | 'document'>>>([
  ['data.detail@1', DetailPreview],
  ...[...standardComponentTypes, 'field.switch'].map(type => [type + '@1', StandardPreview] as const),
]);
