import {useContext, createContext, type HTMLAttributes, type ReactNode} from 'react';
import {Checkbox, Tabs} from 'antd';
import {DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors} from '@dnd-kit/core';
import {SortableContext, arrayMove, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy} from '@dnd-kit/sortable';
import {CSS} from '@dnd-kit/utilities';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import DataTable from '../DataTable';
import SForm from '../SForm/Lazy';
import HelpTip from '../HelpTip';
import ResourceDrawer from '../ResourceDrawer';
import type {DesignDocument, DesignField} from './contracts';
import type {FormFieldAccess} from '../FormRuntime/contracts';
import {designerFormContext} from './presentation';
import {orderedListFields} from './formPresentation';

type Props = {document: DesignDocument; disabled: boolean; open: boolean; onClose: () => void; onChange: (value: DesignDocument) => void};
/** Live document commands participate in the designer's existing undo/save lifecycle. */
export default function FormProperties({document, disabled, open, onClose, onChange}: Props) {
  const {t} = useI18n(), selected = usePageSelected();
  const locked = disabled || !selected || !open;
  const fields = document.fields.map(field => ({field, owner: '', group: t('app.designer.mainFields', '主表')}))
    .concat((document.details ?? []).flatMap(detail => detail.fields.map(field => ({field, owner: detail.detailId, group: detail.label}))));
  const patch = (owner: string, fieldId: string, access: FormFieldAccess) => {
    if (locked) return;
    const update = (field: DesignField): DesignField => field.fieldId === fieldId ? {...field, access: {readOnly: false, create: 'WRITE', edit: 'WRITE', ...access}} : field;
    onChange({...document, fields: owner ? document.fields : document.fields.map(update),
      details: document.details?.map(detail => detail.detailId === owner ? {...detail, fields: detail.fields.map(update)} : detail)});
  };
  const choice = (key: 'create' | 'edit', value: string, label: string) => ({key: `${key}-${value}`, title: label, width: 72,
    render: (_: unknown, row: typeof fields[number]) => <Checkbox aria-label={`${row.group} ${row.field.label} ${key === 'create' ? t('app.designer.createMode', '新增') : t('app.designer.editMode', '编辑')} ${label}`}
      disabled={locked || row.field.storageMode === 'COMPUTED' || value === 'WRITE' && row.field.access?.readOnly}
      checked={(row.field.access?.[key] ?? 'WRITE') === value}
      onChange={() => patch(row.owner, row.field.fieldId, {...row.field.access, [key]: value})}/>});
  return <ResourceDrawer rootClassName="sp-form-properties-drawer" placement="right" collapsible open={open} onClose={onClose}
    title={t('app.designer.formProperties', '表单属性')}>
    <Tabs items={[
      {key: 'basic', icon: createIcon('SettingOutlined'), label: t('app.designer.basicProperties', '基础属性'), children:
        <SForm hideSubmit disabled={locked} formContext={designerFormContext} schema={{type: 'object', properties: {
          labelWidth: {type: 'integer', title: t('app.designer.labelWidth', '标签宽度'), minimum: 60, maximum: 240, default: 140},
        }}} formData={{labelWidth: document.presentation?.labelWidth ?? 140}}
          onChange={({formData}, changed) => {if (!locked && changed && formData && Number.isInteger(formData.labelWidth)) onChange({...document,
            presentation: {...document.presentation, listFieldOrder: document.presentation?.listFieldOrder ?? [], labelWidth: formData.labelWidth}});}}/>},
      {key: 'fields', icon: createIcon('SafetyCertificateOutlined'), label: t('app.designer.fieldProperties', '字段属性'), children: <>
        <div className="sp-form-editor-help"><HelpTip>{t('app.designer.fieldAccessHint', '隐藏仅针对新增或编辑场景，并禁止该操作写入字段。只读和生命周期规则共同生效；数据可见范围仍由平台字段权限控制。')}</HelpTip></div>
        <DataTable rowKey={row => `${row.owner}:${row.field.fieldId}`} dataSource={fields} cards={false} settings={false} selectable={false}
          defaultOrder="provided" pagination={false} scroll={{x: 760, y: 'calc(100dvh - 260px)'}} columns={[
            {key: 'group', title: t('app.designer.fieldGroup', '分组'), dataIndex: 'group', width: 110},
            {key: 'name', title: t('app.designer.fieldLabel', '字段名称'), width: 150, fixed: 'left', render: (_, row) => row.field.label},
            {key: 'readOnly', title: t('app.designer.accessReadOnly', '只读'), width: 72, render: (_, row) => <Checkbox
              aria-label={`${row.group} ${row.field.label} ${t('app.designer.accessReadOnly', '只读')}`} disabled={locked || row.field.storageMode === 'COMPUTED'}
              checked={Boolean(row.field.access?.readOnly) || row.field.storageMode === 'COMPUTED'}
              onChange={event => patch(row.owner, row.field.fieldId, {...row.field.access, readOnly: event.target.checked})}/>},
            {key: 'create', title: t('app.designer.createMode', '新增'), children: [choice('create', 'WRITE', t('app.designer.accessWrite', '写')), choice('create', 'HIDDEN', t('app.designer.accessHidden', '隐藏'))]},
            {key: 'edit', title: t('app.designer.editMode', '编辑'), children: [choice('edit', 'READ', t('app.designer.accessRead', '读')), choice('edit', 'WRITE', t('app.designer.accessWrite', '写')), choice('edit', 'HIDDEN', t('app.designer.accessHidden', '隐藏'))]},
          ]}/></>},
      {key: 'list', icon: createIcon('OrderedListOutlined'), label: t('app.designer.listDefaults', '列表默认顺序'), children:
        <ListOrder document={document} disabled={locked} onChange={onChange}/>},
    ]}/>
  </ResourceDrawer>;
}
const DragHandleContext = createContext<ReactNode>(null);
function DragRow(props: HTMLAttributes<HTMLTableRowElement> & {'data-row-key': string}) {
  const {t} = useI18n();
  const {setNodeRef, setActivatorNodeRef, attributes, listeners, transform, transition, isDragging} = useSortable({id: props['data-row-key']});
  return <DragHandleContext.Provider value={<span ref={setActivatorNodeRef} {...attributes} {...listeners}
    aria-label={t('app.designer.dragListField', '拖拽调整列表列顺序')} style={{cursor: 'grab', touchAction: 'none', padding: 8}}>{createIcon('HolderOutlined')}</span>}>
    <tr {...props} ref={setNodeRef} style={{...props.style, transform: CSS.Transform.toString(transform), transition, position: 'relative', zIndex: isDragging ? 1 : undefined}}/>
  </DragHandleContext.Provider>;
}
function DragHandle() {return useContext(DragHandleContext);}
const dragComponents = {body: {row: DragRow}};
function ListOrder({document, disabled, onChange}: Pick<Props, 'document' | 'disabled' | 'onChange'>) {
  const {t} = useI18n();
  const rows = orderedListFields(document);
  const sensors = useSensors(useSensor(PointerSensor, {activationConstraint: {distance: 5}}), useSensor(KeyboardSensor, {coordinateGetter: sortableKeyboardCoordinates}));
  return <>
    <div className="sp-form-editor-help"><HelpTip>{t('app.designer.listOrderHint', '拖拽手柄调整业务列表的默认列顺序，也可使用空格和方向键。画布布局与用户已保存的列偏好保持独立。')}</HelpTip></div>
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={({active, over}) => {
      if (disabled || !over || active.id === over.id) return;
      const from = rows.findIndex(field => field.fieldId === active.id), to = rows.findIndex(field => field.fieldId === over.id);
      if (from < 0 || to < 0) return;
      onChange({...document, presentation: {...document.presentation, listFieldOrder: arrayMove(rows, from, to).map(field => field.fieldId)}});
    }}><SortableContext items={rows.map(field => field.fieldId)} strategy={verticalListSortingStrategy} disabled={disabled}>
      <DataTable rowKey="fieldId" components={dragComponents} dataSource={rows} cards={false} selectable={false} settings={false}
        defaultOrder="provided" pagination={false} scroll={{y: 'calc(100dvh - 260px)'}} columns={[
          {key: 'drag', width: 50, render: () => disabled ? null : <DragHandle/>},
          {key: 'label', dataIndex: 'label', title: t('app.designer.fieldLabel', '字段名称')},
        ]}/>
    </SortableContext></DndContext>
  </>;
}
