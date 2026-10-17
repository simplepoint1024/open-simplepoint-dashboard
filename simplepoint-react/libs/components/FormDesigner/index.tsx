import FormProperties from './FormProperties';
import FormPreview from './Preview';
import type {DesignerPropertyRegistry} from './extensions';
import {platformComponentBehaviors} from '../FormComponents/modelRegistry';
import {useMemo, useRef, useState, type KeyboardEvent, type ReactNode} from 'react';
import {Alert, App, Collapse, Tabs} from 'antd';
import {DndContext, KeyboardSensor, PointerSensor, closestCenter, pointerWithin, useSensor, useSensors, type DragEndEvent} from '@dnd-kit/core';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import TableActions from '../Table/Actions';
import type {TableButtonProps, TableProps} from '../Table';
import HelpTip from '../HelpTip';
import {createIcon} from '@simplepoint/shared/types/icon';
import {componentGroup} from './presentation';
import FormRuntime, {platformFormComponents, type FormComponentRegistry} from '../FormRuntime';
import type {ComponentDescriptor, DesignDocument, DesignField} from './contracts.ts';
import type {FormFieldWritePolicy} from '../FormRuntime/contracts.ts';
import {addScene, copyComponent, insertComponent, locate, moveComponent,
  pasteComponent, recordHistory, redo, removeComponent, removeScene, undo, validateStructure, type Clipboard, type DesignHistory} from './model.ts';
import CanvasNode, {PaletteItem, type DragSource, type DropTarget, type NodeActions} from './Canvas';
import Properties from './Properties';
import ResizableSidebar from '../ResizableSidebar';
import DataSchemaEditor from './DataSchemaEditor';
import RuleEditor from './RuleEditor';
import {componentLabels, designError} from './labels';
import './style.css';

export type FormDesignerActions = {
  buttons: TableButtonProps[];
  onButtonEvents: NonNullable<TableProps<unknown>['onButtonEvents']>;
  active: boolean;
};

export type FormDesignerProps = {document: DesignDocument; components: readonly ComponentDescriptor[];
  writePolicies?: readonly FormFieldWritePolicy[];
  propertyRegistry?: DesignerPropertyRegistry;
  /** Hosts compose persistence actions with editor-owned history without taking over editor state. */
  toolbar?: {title?: ReactNode; beforePreview?: ReactNode; renderActions?: (actions: FormDesignerActions) => ReactNode};
  registry?: FormComponentRegistry; labels?: Record<string, string>;
  disabled?: boolean; onChange: (document: DesignDocument) => void};

/** Shared controlled editor: no networking, SQL, permissions or publication state live in the canvas. */
export default function FormDesigner({document, components, registry = platformFormComponents, labels: customLabels,
  disabled = false, onChange, writePolicies = [], toolbar, propertyRegistry}: FormDesignerProps) {
  const {t} = useI18n(), {message} = App.useApp();
  const selected = usePageSelected();
  const [sceneId, setSceneId] = useState(() => document.scenes.find(scene => scene.type === 'MAIN')!.sceneId);
  const [selectedId, setSelectedId] = useState('');
  const [mode, setMode] = useState('design');
  const [preview, setPreview] = useState(false), [previewValues, setPreviewValues] = useState<Record<string, unknown>>({});
  const [formPropertiesOpen, setFormPropertiesOpen] = useState(false);
  const [previewNotice, setPreviewNotice] = useState(false);
  const [failure, setFailure] = useState('');
  const [clipboard, setClipboard] = useState<Clipboard>();
  const [history, setHistory] = useState<DesignHistory>({past: [], present: document, future: []});
  const historyRef = useRef(history); historyRef.current = history;
  const sensors = useSensors(useSensor(PointerSensor, {activationConstraint: {distance: 6}}), useSensor(KeyboardSensor));
  const labels = {...componentLabels(t), ...customLabels};
  const supported = useMemo(() => components.filter(item => {
    try {registry.resolve({componentType: item.type, componentVersion: item.version}); return true;} catch {return false;}
  }), [components, registry]);
  const structuralError = useMemo(() => {
    try {validateStructure(document, supported); return '';} catch (error) {return designError(error, t);}
  }, [document, supported, t]);
  const scene = document.scenes.find(scene => scene.sceneId === sceneId) ?? document.scenes.find(scene => scene.type === 'MAIN')!;
  const current = locate(document, selectedId);
  const selection = current?.scene.sceneId === scene.sceneId ? current : locate(document, scene.root.componentId)!;
  const locked = disabled || !selected || preview || Boolean(structuralError);
  const parentId = supported.find(item => item.type === selection.node.componentType && item.version === selection.node.componentVersion)?.container
    ? selection.node.componentId : selection.parent?.componentId ?? scene.root.componentId;
  const parent = locate(document, parentId)!.node;
  const commitHistory = (next: DesignHistory) => {
    if (locked || next === historyRef.current) return;
    historyRef.current = next; setHistory(next); onChange(next.present); setFailure('');
  };
  const commit = (next: DesignDocument) => {
    validateStructure(next, supported);
    commitHistory(recordHistory(historyRef.current, next));
  };
  const attempt = (action: () => void) => {if (!locked) try {action();} catch (error) {setFailure(designError(error, t));}};
  const add = (item: ComponentDescriptor, destination = parentId, index = parent.children.length, fieldId?: string) => attempt(() => {
    const result = insertComponent(document, destination, index, item, labels[item.type] ?? item.type, supported, fieldId);
    commit(result.document); setSelectedId(result.componentId);
  });
  const destinationFor = (id: string) => {
    const target = locate(document, id)!;
    return supported.find(item => item.type === target.node.componentType && item.version === target.node.componentVersion)?.container
      ? target.node.componentId : target.parent?.componentId ?? target.scene.root.componentId;
  };
  const clone = (id = selection.node.componentId) => attempt(() => {
    const result = pasteComponent(document, destinationFor(id), copyComponent(document, id), supported);
    commit(result.document); setSelectedId(result.componentId);
  });
  const paste = (id = selection.node.componentId) => attempt(() => {
    if (!clipboard) return;
    const result = pasteComponent(document, destinationFor(id), clipboard, supported);
    commit(result.document); setSelectedId(result.componentId);
  });
  const remove = (id = selection.node.componentId) => attempt(() => {
    const target = locate(document, id)!;
    if (!target.parent) return;
    commit(removeComponent(document, id, supported)); setSelectedId(target.parent.componentId);
  });
  const move = (delta: number, id = selection.node.componentId) => attempt(() => {
    const target = locate(document, id)!;
    if (target.parent) commit(moveComponent(document, id, target.parent.componentId,
      target.index + (delta < 0 ? -1 : 2), supported));
  });
  const nodeActions = (id: string): NodeActions => {
    const target = locate(document, id)!;
    const structural = platformComponentBehaviors.get(target.node)?.structural;
    return {buttons: [
      {key: 'copy', sort: 0, icon: 'CopyOutlined', text: t('app.designer.copy', '复制'), disabled: locked || structural || !target.parent},
      {key: 'paste', sort: 1, icon: 'SnippetsOutlined', text: t('app.designer.paste', '粘贴'), disabled: locked || !clipboard},
      {key: 'duplicate', sort: 2, icon: 'CopyOutlined', text: t('app.designer.duplicate', '创建副本'), disabled: locked || structural || !target.parent},
      {key: 'up', sort: 3, icon: 'ArrowUpOutlined', text: t('app.designer.up', '上移'), disabled: locked || structural || !target.parent || target.index === 0},
      {key: 'down', sort: 4, icon: 'ArrowDownOutlined', text: t('app.designer.down', '下移'), disabled: locked || structural || !target.parent || target.index === target.parent.children.length - 1},
      {key: 'remove', sort: 5, icon: 'DeleteOutlined', text: t('app.designer.removeControl', '移除控件'), danger: true, disabled: locked || structural || !target.parent},
    ], onButtonEvents: {
      copy: () => attempt(() => setClipboard(copyComponent(document, id))), paste: () => paste(id), duplicate: () => clone(id),
      up: () => move(-1, id), down: () => move(1, id), remove: () => remove(id),
    }};
  };
  const createScene = (type: 'DIALOG' | 'DRAWER') => attempt(() => {
    const result = addScene(document, type, type === 'DIALOG' ? t('app.designer.dialog', '弹窗') : t('app.designer.drawer', '抽屉'), supported);
    commit(result.document); setSceneId(result.sceneId); setSelectedId('');
  });
  const reuse = (field: DesignField) => {
    let item: ComponentDescriptor | undefined;
    for (const candidate of document.scenes) {
      const visit = (node: typeof scene.root) => {
        if (node.fieldId === field.fieldId) item = supported.find(item => item.type === node.componentType && item.version === node.componentVersion);
        node.children.forEach(visit);
      }; visit(candidate.root);
    }
    item ??= supported.find(item => item.fieldRequired && item.supportedFieldTypes.includes(field.type));
    if (item) add(item, parentId, parent.children.length, field.fieldId);
  };
  const drop = (event: DragEndEvent) => attempt(() => {
    const source = event.active.data.current as DragSource | undefined;
    const target = event.over?.data.current as DropTarget | undefined;
    if (!source || !target) return;
    if (source.source === 'node') commit(moveComponent(document, source.componentId, target.parentId, target.index, supported));
    else {
      const item = supported.find(item => item.type === source.type && item.version === source.version);
      if (item) add(item, target.parentId, target.index, source.fieldId);
    }
  });
  const keyboard = (event: KeyboardEvent) => {
    if (locked || !event.currentTarget.contains(event.target as HTMLElement)
      || (event.target as HTMLElement).closest('input,textarea,select,[contenteditable="true"]')) return;
    const command = event.ctrlKey || event.metaKey;
    if (command && event.key.toLowerCase() === 'z') {
      event.preventDefault(); commitHistory(event.shiftKey ? redo(historyRef.current) : undo(historyRef.current));
    } else if (command && event.key.toLowerCase() === 'c' && selection.parent) {
      event.preventDefault(); attempt(() => setClipboard(copyComponent(document, selection.node.componentId)));
    } else if (command && event.key.toLowerCase() === 'v') {event.preventDefault(); paste();}
    else if (event.key === 'Delete' && selection.parent) {event.preventDefault(); remove();}
  };
  const previewMain = document.scenes.find(scene => scene.type === 'MAIN')!;
  const sceneActions = (id: string): NodeActions => ({buttons: [
    {key: 'dialog', sort: 0, icon: 'ExpandOutlined', text: t('app.designer.addDialog', '新建弹窗'), disabled: locked},
    {key: 'drawer', sort: 1, icon: 'LayoutOutlined', text: t('app.designer.addDrawer', '新建抽屉'), disabled: locked},
    {key: 'removeScene', sort: 2, icon: 'DeleteOutlined', text: t('app.designer.removeScene', '移除场景'), danger: true,
      disabled: locked || document.scenes.find(item => item.sceneId === id)?.type === 'MAIN'},
  ], onButtonEvents: {dialog: () => createScene('DIALOG'), drawer: () => createScene('DRAWER'), removeScene: () => attempt(() => {
    commit(removeScene(document, id, supported)); if (id === scene.sceneId) {setSceneId(previewMain.sceneId); setSelectedId('');}
  })}});
  const historyActions: FormDesignerActions = {
    active: selected && !preview,
    buttons: [
      {key: 'undo', sort: 0, icon: 'UndoOutlined', text: t('app.designer.undo', '撤销'), disabled: locked || !history.past.length},
      {key: 'redo', sort: 1, icon: 'RedoOutlined', text: t('app.designer.redo', '重做'), disabled: locked || !history.future.length},
    ],
    onButtonEvents: {undo: () => commitHistory(undo(historyRef.current)), redo: () => commitHistory(redo(historyRef.current))},
  };
  return <div className="sp-form-designer" onKeyDown={keyboard}>
    {(failure || structuralError) && <Alert type="error" showIcon title={failure || structuralError}/>}
    <PageSection selected={!preview}>
      <div className="sp-form-designer-header">
        <Tabs className="sp-form-designer-tabs" activeKey={mode} onChange={setMode} items={[
          {key: 'design', icon: createIcon('LayoutOutlined'), label: t('app.designer.layout', '表单设计')},
          {key: 'data', icon: createIcon('DatabaseOutlined'), label: t('app.designer.data', '数据设计')},
          {key: 'rules', icon: createIcon('BranchesOutlined'), label: t('app.rules.title', '交互规则')},
        ]}/>
        <div className="sp-form-designer-title">{toolbar?.title}</div>
        <div className="sp-form-designer-toolbar">
          {toolbar?.beforePreview}
          <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'preview', sort: 0, type: 'text', iconOnly: true, icon: 'EyeOutlined', text: t('app.designer.preview', '预览'), disabled: !selected || Boolean(structuralError)},
            {key: 'properties', sort: 1, type: 'text', iconOnly: true, icon: 'SettingOutlined', text: t('app.designer.formProperties', '表单属性'), disabled: locked},
          ]} onButtonEvents={{properties: () => setFormPropertiesOpen(true), preview: () => {setPreviewValues({}); setPreviewNotice(false); setPreview(true);}}}/>
          {toolbar?.renderActions ? toolbar.renderActions(historyActions) :
            <TableActions selectedKeys={[]} selectedRows={[]} buttons={historyActions.buttons} onButtonEvents={historyActions.onButtonEvents}
              menu={{trigger: ['hover', 'click'], label: t('app.designer.actions', '操作'), disabled: !historyActions.active}}/>}
          <HelpTip>{t('app.designer.addHint', '拖入画布，或点击添加到选中的布局。')}
            <br/>{t('app.designer.contextHint', '右键控件或点击更多操作，可复制、粘贴、移动和移除。支持 Ctrl/Cmd+C、V、Z 和 Delete。')}
            <br/>{t('app.designer.removeHint', '移除控件会保留数据字段，可从“数据设计”重新添加。')}</HelpTip>
        </div>
      </div>
      {mode === 'data' ? <DataSchemaEditor document={document} components={supported} writePolicies={writePolicies} disabled={locked}
        onChange={commit} onReuse={field => {reuse(field); setMode('design');}}/>
        : mode === 'rules' ? <RuleEditor document={document} disabled={locked} onChange={commit}/>
        : <DndContext sensors={sensors} onDragEnd={drop} collisionDetection={args => {
          const matches = pointerWithin(args); return matches.length ? matches : closestCenter(args);
        }}>
        <div className="sp-form-designer-workspace">
          <aside className="sp-form-designer-palette">
            <Collapse ghost defaultActiveKey={['fields', 'layout', 'business', 'actions']} items={[
              {key: 'fields', icon: 'FormOutlined', label: t('app.designer.groupFields', '基础字段')},
              {key: 'layout', icon: 'LayoutOutlined', label: t('app.designer.groupLayout', '布局与展示')},
              {key: 'business', icon: 'AppstoreOutlined', label: t('app.designer.groupBusiness', '业务组件')},
              {key: 'actions', icon: 'ThunderboltOutlined', label: t('app.designer.groupActions', '操作组件')},
            ].filter(group => supported.some(item => item.type !== 'layout.form' && platformComponentBehaviors.get({componentType: item.type, componentVersion: item.version})?.palette !== false && componentGroup(item.type) === group.key))
              .map(group => ({key: group.key, label: <span className="sp-form-component-group">{createIcon(group.icon)}{group.label}</span>, children: <div className="sp-form-palette-grid">
              {supported.filter(item => item.type !== 'layout.form' && platformComponentBehaviors.get({componentType: item.type, componentVersion: item.version})?.palette !== false && componentGroup(item.type) === group.key)
                .map(item => <PaletteItem key={`${item.type}@${item.version}`}
                  item={item} label={labels[item.type] ?? item.type} disabled={locked} onAdd={() => add(item)}/>)}
            </div>}))}/>
          </aside>
          <ResizableSidebar sidebar={<Properties propertyRegistry={propertyRegistry} document={document} componentId={selection.node.componentId} components={supported} disabled={locked}
            writePolicies={writePolicies} onChange={next => attempt(() => commit(next))}/>}>
          <main className="sp-form-designer-center">
            <div className="sp-form-designer-scenes">
              <Tabs activeKey={scene.sceneId} onChange={key => {setSceneId(key); setSelectedId(''); setFailure('');}}
                tabBarExtraContent={<TableActions selectedKeys={[]} selectedRows={[]} menu={{trigger: 'click', label: t('app.designer.sceneActions', '场景操作')}}
                  {...sceneActions(scene.sceneId)}/>}
                items={document.scenes.map(item => ({key: item.sceneId, label:
                  <TableActions selectedKeys={[]} selectedRows={[]} {...sceneActions(item.sceneId)} menu={{trigger: 'contextMenu', target:
                    <span className="sp-form-scene-label">{createIcon(item.type === 'MAIN' ? 'FormOutlined' : item.type === 'DIALOG' ? 'ExpandOutlined' : 'LayoutOutlined')}
                      {item.name || t('app.designer.unnamed', '未命名场景')}</span>}}/>
                }))}/>
            </div>
            <div className="sp-form-canvas-scroll">
              <CanvasNode node={scene.root} document={document} components={supported} root disabled={locked}
                selectedId={selection.node.componentId} labels={labels} onSelect={setSelectedId} actions={nodeActions}
                emptyLabel={t('app.designer.emptyCanvas', '将组件拖到这里开始设计')}/>
            </div>
          </main>
          </ResizableSidebar>
        </div>
      </DndContext>}
    </PageSection>
    <FormProperties document={document} open={formPropertiesOpen} disabled={locked} onClose={() => setFormPropertiesOpen(false)} onChange={next => attempt(() => commit(next))}/>
    <FormPreview open={preview && selected} onClose={() => setPreview(false)}>
      {previewNotice ? <Alert type="success" showIcon title={t('app.designer.previewValid', '预览校验通过，未保存业务数据。')}/>
        : <div className="sp-form-editor-help"><HelpTip>{t('app.designer.previewHint', '可试填表单并打开弹窗和抽屉，预览不会保存业务数据。')}</HelpTip></div>}
      <FormRuntime document={document} registry={registry} sceneId={previewMain.sceneId} value={previewValues} onChange={setPreviewValues}
        onAction={(_, values) => {setPreviewValues(values); setPreviewNotice(true); message.success(t('app.designer.previewValid', '预览校验通过，未保存业务数据。')); return true;}}/>
    </FormPreview>
  </div>;
}
export type {DesignDocument, DesignerContract, FormDraft, FormSummary} from './contracts.ts';
