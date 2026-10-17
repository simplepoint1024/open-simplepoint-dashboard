import {platformComponentBehaviors} from '../FormComponents/modelRegistry';
import {designerPreviews} from './extensions';
import type {ComponentProps} from 'react';
import {createIcon} from '@simplepoint/shared/types/icon';
import HelpTip from '../HelpTip';
import {componentIcon} from './presentation';
import {useDraggable, useDroppable} from '@dnd-kit/core';
import type {FormComponentDefinition as Node} from '../FormRuntime/contracts.ts';
import TableActions from '../Table/Actions';
import type {ComponentDescriptor, DesignDocument} from './contracts.ts';

export type NodeActions = Pick<ComponentProps<typeof TableActions>, 'buttons' | 'onButtonEvents'>;
export type DragSource = {source: 'palette'; type: string; version: string; fieldId?: string} | {source: 'node'; componentId: string};
export type DropTarget = {parentId: string; index: number};

export function PaletteItem({item, label, disabled, fieldId, onAdd}: {
  item: ComponentDescriptor; label: string; disabled: boolean; fieldId?: string; onAdd: () => void;
}) {
  const {attributes, listeners, setNodeRef, isDragging} = useDraggable({
    id: `palette:${item.type}@${item.version}:${fieldId ?? ''}`, disabled,
    data: {source: 'palette', type: item.type, version: item.version, fieldId} satisfies DragSource,
  });
  return <div ref={setNodeRef} className={`sp-form-palette-item${isDragging ? ' is-dragging' : ''}`} data-palette-type={item.type}>
    {/* The drag handle is a specialized canvas interaction; the add action uses the platform renderer. */}
    <span className="sp-form-drag-handle" {...attributes} {...listeners} aria-label={label} aria-disabled={disabled}>⠿</span>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'insert', sort: 0, text: label,
      icon: componentIcon(item.type), size: 'small', type: 'text', disabled}]} onButtonEvents={{insert: onAdd}}/>
  </div>;
}

function DropSlot({parentId, index, disabled, append = false, emptyLabel}: DropTarget & {
  disabled: boolean; append?: boolean; emptyLabel?: string;
}) {
  const {setNodeRef, isOver} = useDroppable({id: `slot:${parentId}:${index}`, disabled,
    data: {parentId, index} satisfies DropTarget});
  return <div ref={setNodeRef} data-drop-parent={parentId} data-drop-index={index}
    className={`sp-form-drop-slot${append ? ' is-append' : ''}${isOver ? ' is-over' : ''}`}>
    {append && emptyLabel && <span>{emptyLabel}</span>}
  </div>;
}

export default function CanvasNode({node, document, components, selectedId, disabled, labels, emptyLabel, onSelect, actions, root = false}: {
  node: Node; document: DesignDocument; components: readonly ComponentDescriptor[];
  selectedId: string; disabled: boolean; labels: Record<string, string>; emptyLabel: string;
  onSelect: (id: string) => void; actions: (id: string) => NodeActions; root?: boolean;
}) {
  const behavior = platformComponentBehaviors.get(node), cells = behavior?.cells?.(node);
  const Preview = designerPreviews.get(`${node.componentType}@${node.componentVersion}`);
  const {setNodeRef, attributes, listeners, isDragging} = useDraggable({id: `node:${node.componentId}`,
    disabled: disabled || root || behavior?.structural, data: {source: 'node', componentId: node.componentId} satisfies DragSource});
  const descriptor = components.find(item => item.type === node.componentType && item.version === node.componentVersion);
  const field = document.fields.find(field => field.fieldId === node.fieldId);
  const label = node.label || field?.label || labels[node.componentType] || node.componentType;
  const nodeActions = actions(node.componentId);
  return <TableActions {...nodeActions} selectedKeys={[]} selectedRows={[]} menu={{trigger: 'contextMenu', target: <section ref={setNodeRef} data-design-node={node.componentId}
    data-component-type={node.componentType}
    className={`sp-form-canvas-node${root ? ' is-root' : ''}${descriptor?.container ? ' is-container' : ' is-control'}${selectedId === node.componentId ? ' is-selected' : ''}${isDragging ? ' is-dragging' : ''}`}
    onContextMenu={event => {event.stopPropagation(); onSelect(node.componentId);}}
    tabIndex={0} aria-label={label} onClick={event => {event.stopPropagation(); onSelect(node.componentId);}}
    onFocus={event => {if (event.target === event.currentTarget) onSelect(node.componentId);}}
    onKeyDown={event => {if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault(); onSelect(node.componentId);
    }}}>
    <div className="sp-form-canvas-title">
      {!root && !behavior?.structural && <span className="sp-form-drag-handle" {...attributes} {...listeners} aria-label={label} aria-disabled={disabled}>⠿</span>}
      <span className="sp-form-component-icon">{createIcon(componentIcon(node.componentType))}</span>
      <span className="sp-form-control-label" title={label}>{label}{field?.submitRequired && <em> *</em>}</span>
      {node.description && <HelpTip>{node.description}</HelpTip>}
      {!descriptor?.container && !Preview && <div className={`sp-form-canvas-placeholder${node.componentType.startsWith('action.') ? ' is-action' : ''}`}>
        <span>{node.placeholder || (node.componentType.startsWith('action.') || node.componentType === 'display.text' ? label : '')}</span>
        {['field.select', 'field.date', 'field.time', 'field.datetime'].includes(node.componentType) && createIcon(componentIcon(node.componentType))}
        {node.componentType === 'field.switch' && <span className="sp-form-switch-preview"/>}
      </div>}
      <span className="sp-form-node-menu" onClick={event => event.stopPropagation()} onKeyDown={event => event.stopPropagation()}>
        <TableActions {...nodeActions} selectedKeys={[]} selectedRows={[]} menu={{trigger: 'click', label}}/>
      </span>
    </div>
    {Preview && <Preview node={node} document={document}/>}
    {descriptor?.container ? <div className={cells?.managed ? 'sp-form-canvas-matrix' : 'sp-form-canvas-children'}
      style={cells?.managed ? {gridTemplateColumns: `repeat(${cells?.columns}, minmax(0, 1fr))`} : undefined}>
      {node.children.map((child, index) => <div key={child.componentId} className="sp-form-canvas-cell"
        style={{gridColumn: cells?.managed ? undefined : `span ${Math.max(1, Math.min(24, child.columnSpan))}`}}>
        {!cells?.managed && <DropSlot parentId={node.componentId} index={index} disabled={disabled}/>}
        <CanvasNode node={child} document={document} components={components} selectedId={selectedId}
          disabled={disabled} labels={labels} emptyLabel={emptyLabel} onSelect={onSelect} actions={actions}/>
      </div>)}
      {!cells?.managed && <DropSlot parentId={node.componentId} index={node.children.length} disabled={disabled} append
        emptyLabel={node.children.length ? undefined : emptyLabel}/>}
    </div> : null}
  </section>}}/>;
}
