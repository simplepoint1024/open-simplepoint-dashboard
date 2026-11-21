import {Alert, Modal} from 'antd';
import {useContext, useEffect, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {createIcon} from '@simplepoint/shared/types/icon';
import {changedLocalizedDraft, localizedDraft, localizedTextSchema} from '@simplepoint/shared/i18n/localizedSchema';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import DataTable from '../../DataTable';
import TableActions from '../../Table/Actions';
import SForm, {type SFormHandle} from '../../SForm';
import EntitySelect from '../../EntitySelect';
import HelpTip from '../../HelpTip';
import type {DesignDocument} from '../contracts';
import {designerFormContext} from '../presentation';
import {sameDesignValue} from '../draftState';
import {DesignerRelationContext} from './Context';
import {relationTargetIdentity, type DesignerRelationDirectory} from './catalog';
import type {FormInverseRelationView} from './inverseContracts';
import {applyInverseView} from './inverseEdit';
import {RelationEditError} from './model';

type Props = {document: DesignDocument; disabled: boolean; onChange: (document: DesignDocument) => void;
  onInteractionLockChange: (locked: boolean) => void};

/** A host-injected directory and independent editor keep inverse authoring outside the designer controller. */
export default function InverseViews(props: Props) {
  const directory = useContext(DesignerRelationContext);
  return directory?.inverseSource && <InverseViewList key={directory.key} {...props} directory={directory}/>;
}

function InverseViewList({document, disabled, onChange, onInteractionLockChange, directory}: Props & {directory: DesignerRelationDirectory}) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  const [editing, setEditing] = useState<{base?: FormInverseRelationView}>(), [failure, setFailure] = useState('');
  const views = document.presentation?.inverseViews ?? [], locked = disabled || !selected || !!editing;
  useEffect(() => {onInteractionLockChange(!!editing);}, [editing, onInteractionLockChange]);
  useEffect(() => () => onInteractionLockChange(false), [onInteractionLockChange]);
  const labels = {allowCreate: t('app.relation.allowCreate', '新增'), allowEdit: t('app.relation.allowEdit', '编辑与提交审批'),
    allowDelete: t('app.relation.allowDelete', '删除业务记录'), allowUnlink: t('app.runtime.relation.unlink', '解除关联')};
  return <>
    <div className="sp-form-editor-help"><HelpTip>{t('app.relation.inverseHint', '从已关联当前表单的业务中展示记录。操作仍需来源页面授权；解除关联保留原记录。')}</HelpTip></div>
    {failure && <Alert type="error" showIcon title={failure}/>}
    <DataTable rowKey="viewId" dataSource={views} cards={false} settings={false} selectable={false} defaultOrder="provided"
      pagination={false} scroll={{x: 680, y: 'calc(100dvh - 300px)'}} toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]}
        buttons={[{key: 'add', icon: 'PlusOutlined', sort: 0, text: t('table.button.add', '新增'), disabled: locked || views.length >= 20}]}
        onButtonEvents={{add: () => {if (!locked && views.length < 20) setEditing({});}}}/>}
      columns={[
        {key: 'label', title: t('app.designer.label', '标题'), width: 160, render: (_, row) =>
          resolveLocalizedText(row.labelLocalization, locale, undefined, 'TENANT', row.label).text},
        ...Object.entries(labels).map(([key, title]) => ({key, title, width: 100,
          render: (_: unknown, row: FormInverseRelationView) => createIcon(row[key as keyof typeof labels] ? 'CheckOutlined' : 'MinusOutlined')})),
        {key: 'actions', title: t('table.actions', '操作'), width: 180, fixed: 'right', render: (_, row) => <TableActions appearance="row"
          selectedKeys={[row.viewId]} selectedRows={[row]} buttons={[
            {key: 'edit', icon: 'EditOutlined', sort: 0, text: t('table.button.edit', '编辑'), disabled: locked},
            {key: 'delete', icon: 'DeleteOutlined', sort: 1, text: t('table.button.delete', '删除'), disabled: locked},
          ]} onButtonEvents={{edit: () => {if (!locked) setEditing({base: structuredClone(row)});}, delete: () => {
            if (locked) return;
            try {onChange(applyInverseView(document, row, undefined)); setFailure('');}
            catch {setFailure(t('app.designer.definitionChanged', '定义已变更，请重新打开后再修改。'));}
          }}}/>},
      ]}/>
    {editing && <InverseViewEditor document={document} base={editing.base} directory={directory} disabled={disabled}
      onChange={onChange} onClose={() => setEditing(undefined)}/>}
  </>;
}

function InverseViewEditor({document, base, directory, disabled, onChange, onClose}: Omit<Props, 'onInteractionLockChange'> & {
  base?: FormInverseRelationView; directory: DesignerRelationDirectory; onClose: () => void;
}) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  const [initial] = useState(() => ({viewId: base?.viewId ?? crypto.randomUUID(), source: base?.source,
    sourceRelationId: base?.sourceRelationId ?? '', labelLocalization: localizedDraft(base?.label ?? '', base?.labelLocalization, locale),
    allowCreate: base?.allowCreate ?? false, allowEdit: base?.allowEdit ?? false,
    allowDelete: base?.allowDelete ?? false, allowUnlink: base?.allowUnlink ?? false}));
  const [draft, setDraft] = useState(initial), [failure, setFailure] = useState(''), [discard, setDiscard] = useState(false);
  const form = useRef<SFormHandle>(null), locked = disabled || !selected;
  usePageRetention(false, true);
  const query = useOwnedData(['form-inverse-source', directory.key, draft.source], ({signal}) => directory.inverseSource!(draft.source!, signal),
    {enabled: selected && !!draft.source, retry: false, staleTime: 0, gcTime: 0, refetchOnMount: 'always', refetchOnWindowFocus: 'always'});
  const metadata = selected && !query.isFetching && !query.error ? query.data : undefined;
  const relation = metadata?.relations.find(value => value.relationId === draft.sourceRelationId);
  const close = () => {if (selected) sameDesignValue(initial, draft) ? onClose() : setDiscard(true);};
  const apply = () => {
    if (locked || !metadata || !draft.source) return;
    try {
      const localization = changedLocalizedDraft(base?.label ?? '', base?.labelLocalization, draft.labelLocalization, locale);
      const {labelLocalization: _draftLabel, ...values} = draft;
      onChange(applyInverseView(document, base, {...values, ...(relation?.collection ? {allowEdit: false, allowDelete: false} : {}), source: draft.source, label: draft.labelLocalization.defaultValue,
        ...(localization !== undefined ? {labelLocalization: localization} : {})}, metadata));
      onClose();
    } catch (error) {setFailure(error instanceof RelationEditError && error.code === 'conflict'
      ? t('app.designer.definitionChanged', '定义已变更，请重新打开后再修改。')
      : t('app.relation.invalidInverse', '请检查来源表单、关联字段和操作配置。'));}
  };
  return <>
    <Modal wrapClassName="sp-form-inverse-modal" open={selected} title={t('app.relation.inverseView', '关联记录')} width={680} maskClosable={false} onCancel={close}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
        {key: 'apply', icon: 'CheckOutlined', sort: 1, type: 'primary', text: t('app.designer.applyData', '应用修改'), disabled: locked || !relation},
      ]} onButtonEvents={{cancel: close, apply: () => {if (!locked && relation) form.current?.submit();}}}/>}>
      {failure && <Alert type="error" showIcon title={failure}/>}
      <div className="sp-form-relation-target"><label htmlFor="inverse_source">{t('app.relation.source', '来源业务资源')}</label>
        <EntitySelect id="inverse_source" endpoint={directory.endpoint} source={directory.source} disabled={locked}
          value={draft.source?.accessResourceId} onChange={(id, option) => {
            if (!locked) setDraft({...draft, source: id ? relationTargetIdentity(option?.metadata?.target) : undefined,
              sourceRelationId: '', allowUnlink: false});
          }}/></div>
      {query.isFetching && draft.source && <Alert type="info" showIcon title={t('loading', '加载中')}/>}
      {query.error && <Alert type="error" showIcon title={t('app.relation.metadataFailed', '目标资源定义加载失败，请重试。')}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'retry', icon: 'ReloadOutlined', sort: 0, text: t('table.retry', '重试'), disabled: locked || query.isFetching},
        ]} onButtonEvents={{retry: () => {if (!locked) void query.refetch();}}}/>}/>}
      {metadata && !metadata.relations.length && <Alert type="info" showIcon
        title={t('app.relation.noInverseSource', '该来源尚未发布指向当前表单的关联字段，请先在来源表单配置并发布关联。')}/>}
      <SForm ref={form} idPrefix="inverse_settings" hideSubmit disabled={locked || !metadata} formContext={designerFormContext}
        schema={{type: 'object', required: ['labelLocalization', 'sourceRelationId'], properties: {
          labelLocalization: localizedTextSchema(t('app.designer.label', '标题')),
          sourceRelationId: {type: 'string', title: t('app.relation.sourceField', '来源关联字段'), oneOf: metadata?.relations.map(value => ({const: value.relationId,
            title: [value.detailLabel ? resolveLocalizedText(value.detailLabelLocalization, locale, undefined, 'TENANT', value.detailLabel).text : '',
              resolveLocalizedText(value.labelLocalization, locale, undefined, 'TENANT', value.label).text].filter(Boolean).join(' / ')})) ?? []},
          allowCreate: {type: 'boolean', title: relation?.collection ? t('app.relation.collection.link', '添加关联') : t('app.relation.allowCreate', '新增')},
          ...(!relation?.collection ? {allowEdit: {type: 'boolean', title: t('app.relation.allowEdit', '编辑与提交审批')},
          allowDelete: {type: 'boolean', title: t('app.relation.allowDelete', '删除业务记录')}} : {}),
          allowUnlink: {type: 'boolean', title: t('app.runtime.relation.unlink', '解除关联'), readOnly: !relation?.nullable},
        }}} formData={{labelLocalization: draft.labelLocalization, sourceRelationId: draft.sourceRelationId,
          allowCreate: draft.allowCreate, allowEdit: draft.allowEdit, allowDelete: draft.allowDelete, allowUnlink: draft.allowUnlink}} onChange={({formData}, changed) => {
          if (!locked && metadata && changed && formData) setDraft({...draft, ...formData,
            allowUnlink: metadata.relations.find(value => value.relationId === formData.sourceRelationId)?.nullable ? Boolean(formData.allowUnlink) : false});
        }} onSubmit={apply}/>
    </Modal>
    <Modal wrapClassName="sp-form-inverse-discard" open={discard && selected} title={t('app.draft.discardTitle', '放弃未保存的修改？')}
      onOk={onClose} onCancel={() => setDiscard(false)} destroyOnHidden/>
  </>;
}
