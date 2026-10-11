import {createContext, useContext, useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Modal} from 'antd';
import {Widgets} from '@rjsf/antd';
import type {ArrayFieldItemTemplateProps, ArrayFieldTemplateProps, ObjectFieldTemplateProps, WidgetProps} from '@rjsf/utils';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import TableActions from '@simplepoint/components/Table/Actions';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@/layouts/i18n/useI18n';
import {MAX_APPS, parsePreferences, validTimeZone, type HomePreferences} from './preferences';
import {appSelection, homeDraft, homeSchema} from './forms';

const GroupButtons = createContext<ArrayFieldItemTemplateProps['buttonsProps'] | null>(null);
const ConfirmRemove = createContext<(id: string) => void>(() => {});
function GroupLayout({properties}: ObjectFieldTemplateProps) {
  const {t} = useI18n(), buttons = useContext(GroupButtons), remove = useContext(ConfirmRemove);
  const id = buttons?.registry.formContext.formData.groups[buttons.index]?.id;
  return <>{properties.find(field => field.name === 'id')?.content}<div className="sp-home-group-tools">
    {properties.find(field => field.name === 'name')?.content}
    {buttons && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'up', sort: 0, icon: 'ArrowUpOutlined', iconOnly: true, text: t('home.moveUp', '上移分组'),
        disabled: buttons.disabled || !buttons.hasMoveUp, onClick: buttons.onMoveUpItem},
      {key: 'down', sort: 1, icon: 'ArrowDownOutlined', iconOnly: true, text: t('home.moveDown', '下移分组'),
        disabled: buttons.disabled || !buttons.hasMoveDown, onClick: buttons.onMoveDownItem},
      {key: 'remove', sort: 2, icon: 'DeleteOutlined', iconOnly: true, danger: true, text: t('home.deleteGroup', '删除分组'),
        disabled: buttons.disabled || !buttons.hasRemove, onClick: () => {if (id) remove(id);}},
    ]}/>}
  </div>{properties.find(field => field.name === 'paths')?.content}</>;
}
function GroupItem({children, buttonsProps}: ArrayFieldItemTemplateProps) {
  return <section className="sp-home-edit-group"><GroupButtons.Provider value={buttonsProps}>{children}</GroupButtons.Provider></section>;
}
function Groups({items, canAdd, onAddClick, disabled, readonly}: ArrayFieldTemplateProps) {
  const {t} = useI18n();
  return <>{items}<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'addGroup', sort: 0, block: true,
    type: 'dashed', icon: 'PlusOutlined', text: t('home.addGroup', '添加分组'), disabled: disabled || readonly || !canAdd, onClick: onAddClick}]}/></>;
}
function EditorLayout({properties}: ObjectFieldTemplateProps) {
  return <>{properties.find(field => field.name === 'version')?.content}{properties.find(field => field.name === 'groups')?.content}
    <div className="sp-home-location-fields">{properties.filter(field => field.name === 'region' || field.name === 'timeZone').map(field => field.content)}</div></>;
}
/** This is the finite authorized route registry, not a remote directory or a first-page-only catalog. */
function AppsWidget(props: WidgetProps) {
  const {t} = useI18n(), options = props.registry.formContext.appOptions as {label: string; value: string}[];
  const paths: string[] = props.value ?? [], allowed = new Set(options.map(option => option.value));
  const visible = paths.filter(path => allowed.has(path)), hidden = paths.length - visible.length;
  return <><Widgets.SelectWidget {...props} value={visible} multiple schema={{...props.schema, default: []}}
    options={{...props.options, enumOptions: options, enumDisabled: paths.length >= MAX_APPS ? options.filter(option => !visible.includes(option.value)).map(option => option.value) : []}}
    onChange={values => props.onChange(appSelection(paths, values, options))}/>
    {!!hidden && <p className="sp-home-muted">{t('home.unavailableApps', '保留 {count} 个当前角色不可访问的入口。', {count: hidden})}</p>}</>;
}
const widgets = {HomeApps: AppsWidget};
const uiSchema = {'ui:ObjectFieldTemplate': EditorLayout, version: {'ui:widget': 'hidden'},
  groups: {'ui:ArrayFieldTemplate': Groups, 'ui:ArrayFieldItemTemplate': GroupItem, items: {
    'ui:ObjectFieldTemplate': GroupLayout, id: {'ui:widget': 'hidden'}, name: {'ui:options': {emptyValue: ''}},
    paths: {'ui:widget': 'HomeApps'},
  }}, region: {'ui:options': {emptyValue: ''}},
};
export function HomeEditor({initial, options, saving, onSave, onClose}: {
  initial: HomePreferences; options: {label: string; value: string}[]; saving: boolean;
  onSave: (value: HomePreferences) => Promise<boolean>; onClose: () => void;
}) {
  const {t} = useI18n(), selected = usePageSelected();
  const [draft, setDraft] = useState<HomePreferences>(() => structuredClone(initial)), [error, setError] = useState('');
  const [confirmation, setConfirmation] = useState<{kind: 'discard'} | {kind: 'remove'; id: string} | null>(null);
  const form = useRef<SFormHandle>(null), flight = useRef(false), alive = useRef(true), confirmationRef = useRef<typeof confirmation>(null);
  const [submitting, setSubmitting] = useState(false), busy = saving || submitting;
  const dirty = JSON.stringify(initial) !== JSON.stringify(draft);
  const schema = useMemo(() => homeSchema(t, draft.timeZone), [t, draft.timeZone]);
  usePageRetention(!dirty && !busy && !confirmation, dirty || busy || !!confirmation);
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  const ask = (next: NonNullable<typeof confirmation>) => {if (flight.current || busy || confirmationRef.current) return;
    confirmationRef.current = next; setConfirmation(next);};
  const cancelConfirmation = () => {confirmationRef.current = null; setConfirmation(null);};
  const close = () => {if (flight.current || busy || confirmationRef.current) return; if (!dirty) onClose(); else ask({kind: 'discard'});};
  const save = async (values: HomePreferences) => {
    if (flight.current || saving || confirmationRef.current) return; flight.current = true; setSubmitting(true); setError('');
    try {
      const parsed = parsePreferences(values);
      if (await onSave(parsed) && alive.current) onClose();
    } catch (cause) {if (alive.current) setError(resolveApiErrorMessage(cause, t('home.saveError', '保存失败，设置已保留，请重试。')));}
    finally {flight.current = false; if (alive.current) setSubmitting(false);}
  };
  return <><Modal wrapClassName="host-home-editor" open={selected} title={t('home.customize', '自定义首页')} width={760} onCancel={close} destroyOnHidden
    maskClosable={!dirty && !busy} closable={!busy} keyboard={!busy}
    footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'cancel', sort: 0, text: t('action.cancel', '取消'), disabled: busy || !!confirmation, onClick: close},
      {key: 'save', sort: 1, type: 'primary', text: t('action.save', '保存'), loading: busy, disabled: busy || !!confirmation,
        onClick: () => {if (!flight.current) form.current?.submit();}},
    ]}/>}>
    <div className="sp-home-editor">
      {error && <Alert type="error" showIcon title={error}/>}
      <p className="sp-home-muted">{t('home.editorHint', '设置保存在当前账号和工作空间中。仅显示当前角色有权限的应用。')}</p>
      <ConfirmRemove.Provider value={id => ask({kind: 'remove', id})}>
        <SForm ref={form} hideSubmit schema={schema} widgets={widgets} uiSchema={uiSchema} formContext={{appOptions: options}}
          formData={draft} disabled={busy || !!confirmation} onChange={event => {if (!flight.current && !confirmationRef.current) setDraft(homeDraft(event.formData, () => crypto.randomUUID()));}}
          customValidate={(value, errors) => {
            if (new Set(value.groups.map((group: {id: string}) => group.id)).size !== value.groups.length) errors.groups?.addError(t('home.invalid', '请填写分组名称，并检查地区和时区设置。'));
            if (value.timeZone && !validTimeZone(value.timeZone)) errors.timeZone?.addError(t('home.invalid', '请填写分组名称，并检查地区和时区设置。'));
            return errors;
          }} onSubmit={event => void save(event.formData)}/>
      </ConfirmRemove.Provider>
    </div>
  </Modal><Modal wrapClassName="host-home-confirmation" open={selected && !!confirmation} title={confirmation?.kind === 'discard'
    ? t('home.discard', '放弃尚未保存的首页设置？') : t('home.deleteConfirm', '删除此分组及其快捷入口？')}
    onCancel={cancelConfirmation} onOk={() => {
      const target = confirmationRef.current; if (flight.current || busy || !target) return;
      if (target.kind === 'discard') onClose();
      else setDraft(value => ({...value, groups: value.groups.filter(group => group.id !== target.id)}));
      cancelConfirmation();
    }} destroyOnHidden/></>;
}
export default HomeEditor;
