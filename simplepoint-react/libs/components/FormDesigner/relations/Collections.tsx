import {Alert, Modal, Tabs} from 'antd';
import {useContext, useEffect, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {localizedDraft, localizedTextSchema} from '@simplepoint/shared/i18n/localizedSchema';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import {createIcon} from '@simplepoint/shared/types/icon';
import DataTable from '../../DataTable';
import EntitySelect from '../../EntitySelect';
import SForm, {type SFormHandle} from '../../SForm';
import TableActions from '../../Table/Actions';
import type {QueryNode} from '../../Table/query';
import type {DesignDocument} from '../contracts';
import type {FormCollectionRelation as Collection, FormRelationDefinition, FormRelationTarget} from '../relationContracts';
import {designerFormContext} from '../presentation';
import {sameDesignValue} from '../draftState';
import {compileCondition, ruleCondition} from '../ruleModel';
import {DesignerRelationContext} from './Context';
import {relationTargetIdentity, type DesignerRelationDirectory} from './catalog';
import {maxCollectionMappingTextLength, parseCollectionMapping} from './collectionReplacement';
import {relationConditionDocument} from './edit';
import RelationCondition from './Condition';

type Props = {document: DesignDocument; disabled: boolean; onChange: (document: DesignDocument) => boolean;
  onInteractionLockChange: (locked: boolean) => void};

export default function Collections(props: Props) {
  const directory = useContext(DesignerRelationContext);
  return directory && <CollectionList key={directory.key} {...props} directory={directory}/>;
}

function CollectionList({document, disabled, onChange, onInteractionLockChange, directory}: Props & {
  directory: DesignerRelationDirectory;
}) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  const [editing, setEditing] = useState<{base?: Collection; replacing?: Collection}>();
  const relations = (document.relations ?? []).filter((relation): relation is Collection => relation.cardinality === 'MANY_TO_MANY');
  const published = directory.publishedCollections ?? [];
  const isPublished = (id: string) => published.some(relation => relation.relationId === id);
  const hasPendingRebind = relations.some(relation => relation.collectionReplacement && !isPublished(relation.relationId));
  const locked = disabled || !selected || !!editing;
  useEffect(() => {onInteractionLockChange(!!editing);}, [editing, onInteractionLockChange]);
  useEffect(() => () => onInteractionLockChange(false), [onInteractionLockChange]);
  return <>
    <DataTable rowKey="relationId" dataSource={relations} cards={false} settings={false} selectable={false}
      pagination={false} defaultOrder="provided" scroll={{x: 465, y: 'calc(100dvh - 300px)'}}
      toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'add', icon: 'PlusOutlined', sort: 0, text: t('app.relation.collection.add', '新增多对多关联'),
          disabled: locked || (document.relations?.length ?? 0) >= 50},
      ]} onButtonEvents={{add: () => setEditing({})}}/>}
      columns={[
        {key: 'label', title: t('app.designer.label', '标题'), width: 190, render: (_, row) =>
          resolveLocalizedText(row.collectionLabel, locale, undefined, 'TENANT',
            t('app.relation.collection.untitled', '多对多关联')).text},
        {key: 'actions', title: t('table.actions', '操作'), width: 250, fixed: 'right', render: (_, row) => {
          const undo = !isPublished(row.relationId) ? row.collectionReplacement : undefined;
          const restore = undo ? published.find(relation => relation.relationId === undo.sourceRelationId) : undefined;
          return <TableActions appearance="row" selectedKeys={[row.relationId]} selectedRows={[row]} buttons={[
            {key: 'edit', icon: 'EditOutlined', sort: 0, text: t('table.button.edit', '编辑'), disabled: locked},
            ...(isPublished(row.relationId) ? [{key: 'rebind', icon: 'SwapOutlined', sort: 1,
              text: t('app.relation.collection.rebind', '更换目标表单'), disabled: locked || hasPendingRebind}] : []),
            {key: 'remove', icon: undo ? 'UndoOutlined' : 'DeleteOutlined', sort: 2,
              text: undo ? t('app.relation.collection.undoRebind', '撤销改绑') : t('table.button.delete', '删除'),
              disabled: locked || !!undo && !restore},
          ]} onButtonEvents={{edit: () => setEditing({base: structuredClone(row)}), rebind: () => {
            if (!locked && !hasPendingRebind && isPublished(row.relationId)) setEditing({replacing: structuredClone(row)});
          }, remove: () => {
            if (locked || undo && !restore) return;
            const next = {...document, relations: document.relations?.flatMap(item => {
              if (item.relationId === row.relationId) return restore ? [restore] : [];
              if (item.retiredByCollectionId === row.relationId && directory.convertibleRelationIds?.includes(item.relationId)) {
                const {retiredByCollectionId, ...active} = item;
                return [{...active, contractVersion: '1' as const}];
              }
              return [item];
            })};
            if (!next.relations?.length) delete next.relations;
            onChange(next);
          }}}/>;
        }},
      ]}/>
    {editing && <CollectionEditor document={document} directory={directory} {...editing}
      disabled={disabled} onChange={onChange} onClose={() => setEditing(undefined)}/>}
  </>;
}

function CollectionEditor({document, directory, base, replacing, disabled, onChange, onClose}: Pick<Props, 'document' | 'disabled' | 'onChange'> & {
  directory: DesignerRelationDirectory; base?: Collection; replacing?: Collection; onClose: () => void;
}) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  usePageRetention(false, true);
  const form = useRef<SFormHandle>(null);
  const [target, setTarget] = useState<FormRelationTarget | undefined>(base?.target);
  const originalLabel = (base ?? replacing)?.collectionLabel;
  const [label, setLabel] = useState(() => localizedDraft(originalLabel?.defaultValue ?? '', originalLabel, locale));
  const [qualification, setQualification] = useState<QueryNode | undefined>(() =>
    base?.qualification ? ruleCondition(base.qualification.condition) : undefined);
  const [conversionSource, setConversionSource] = useState('');
  const [mappingText, setMappingText] = useState(() => Object.entries(base?.collectionReplacement?.targetRecords ?? {})
    .map(([source, destination]) => source + ',' + destination).join('\n'));
  const [failure, setFailure] = useState<'definition' | 'mapping' | 'sameForm' | 'differentForm'>();
  const locked = disabled || !selected;
  const query = useOwnedData(['form-collection-target', directory.key, target], ({signal}) =>
    directory.describe(target!, signal), {enabled: selected && !!target, staleTime: 0, gcTime: 0, retry: false});
  const metadata = selected && !query.isFetching && !query.isError ? query.data : undefined;
  const published = directory.convertibleRelationIds ?? [];
  const current = document.relations ?? [];
  const pendingRebind = replacing?.relationId ?? (base?.collectionReplacement &&
    directory.publishedCollections?.some(relation => relation.relationId === base.collectionReplacement?.sourceRelationId)
    ? base.collectionReplacement.sourceRelationId : undefined);
  const conversionOptions = !base && !replacing && target && !current.some(relation => relation.retiredByCollectionId && published.includes(relation.relationId))
    ? current.filter(relation => relation.contractVersion === '1' && relation.cardinality === 'MANY_TO_ONE'
      && relation.sourceField.detailId == null && published.includes(relation.relationId)
      && relation.target.applicationId === target.applicationId && relation.target.formId === target.formId)
    : [];
  const apply = () => {
    if (locked || !target || !metadata) return;
    let replacement = base?.collectionReplacement;
    if (pendingRebind) {
      try {
        replacement = {sourceRelationId: pendingRebind, targetRecords: parseCollectionMapping(mappingText)};
      } catch {
        setFailure('mapping');
        return;
      }
    }
    try {
      const edited = base ?? replacing;
      if (edited && !sameDesignValue(edited, current.find(relation => relation.relationId === edited.relationId)))
        throw new Error('Stale document');
      const relation: Collection = {contractVersion: replacement ? '3' : '1', relationId: base?.relationId ?? crypto.randomUUID(),
        cardinality: 'MANY_TO_MANY', target, collectionLabel: label,
        ...(replacement ? {collectionReplacement: replacement} : {}),
        ...(qualification ? {qualification: {recheck: 'ON_CHANGE',
          condition: compileCondition(qualification, relationConditionDocument(metadata))}} : {}),
        ...(base?.referenceView ? {referenceView: base.referenceView} : {})};
      const source = conversionOptions.find(item => item.relationId === conversionSource);
      if (conversionSource && (!source || source.contractVersion !== '1' || source.cardinality !== 'MANY_TO_ONE'))
        throw new Error('Unavailable conversion source');
      const updated = source?.cardinality === 'MANY_TO_ONE' ? current.map((item): FormRelationDefinition =>
        item.relationId === source.relationId
          ? {...source, contractVersion: '2', cardinality: 'MANY_TO_ONE', retiredByCollectionId: relation.relationId} : item)
        : current;
      const relations = edited ? current.map(item => item.relationId === edited.relationId ? relation : item) : [...updated, relation];
      if (onChange({...document, relations})) onClose();
      else setFailure('definition');
    } catch {
      setFailure('definition');
    }
  };
  const failures = {
    definition: t('app.relation.invalid', '请检查目标资源、显示字段、筛选条件和关联校验规则。'),
    mapping: t('app.relation.collection.rebindMappingInvalid', '每行应包含两个有效的记录 ID，原记录 ID 只能填写一次。'),
    sameForm: t('app.relation.collection.rebindDifferentTarget', '请选择另一张目标表单。'),
    differentForm: t('app.relation.collection.sameTargetForm', '请选择同一目标表单的访问入口。'),
  };
  return <Modal rootClassName="sp-form-collection-modal" title={pendingRebind
    ? t('app.relation.collection.rebind', '更换目标表单') : t('app.relation.collection.untitled', '多对多关联')} open={selected} width={760}
    maskClosable={false} onCancel={onClose} destroyOnHidden
    footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
      {key: 'apply', icon: 'CheckOutlined', sort: 1, text: t('app.designer.applyData', '应用修改'),
        type: 'primary', disabled: locked || !metadata},
    ]} onButtonEvents={{cancel: onClose, apply: () => form.current?.submit()}}/>}>
    {failure && <Alert type="error" showIcon title={failures[failure]}/>}
    {query.isError && <Alert type="error" title={t('app.relation.metadataFailed', '目标资源定义加载失败，请重试。')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'retry', icon: 'ReloadOutlined', sort: 0, text: t('table.retry', '重试'), disabled: locked},
      ]} onButtonEvents={{retry: () => {if (!locked) void query.refetch();}}}/>}/>}
    <Tabs items={[
      {key: 'definition', icon: createIcon('LinkOutlined'), label: t('app.relation.settings', '关联配置'), forceRender: true,
        children: <>
          <div className="sp-form-relation-target">
            <label htmlFor="collection_target">{t('app.relation.target', '目标业务资源')}</label>
            <EntitySelect id="collection_target" endpoint={directory.endpoint} source={directory.source} disabled={locked}
              value={target?.accessResourceId} onChange={(id, option) => {
                if (locked) return;
                const next = id ? relationTargetIdentity(option?.metadata?.target) : undefined;
                if (replacing && next && replacing.target.applicationId === next.applicationId
                  && replacing.target.formId === next.formId) {
                  setFailure('sameForm');
                  return;
                }
                if (base && next && (base.target.applicationId !== next.applicationId || base.target.formId !== next.formId)) {
                  setFailure('differentForm');
                  return;
                }
                setTarget(next);
                setConversionSource('');
                if (replacing && target && (!next || next.applicationId !== target.applicationId || next.formId !== target.formId))
                  setMappingText('');
                setFailure(undefined);
                if (!base) setQualification(undefined);
              }}/>
          </div>
          <SForm ref={form} idPrefix="collection_definition" hideSubmit disabled={locked} formContext={designerFormContext}
            schema={{type: 'object', required: ['label'], properties: {
              label: localizedTextSchema(t('app.designer.label', '标题')),
              ...(pendingRebind ? {mappingText: {type: 'string' as const, maxLength: maxCollectionMappingTextLength,
                title: t('app.relation.collection.rebindMapping', '记录映射'),
                description: t('app.relation.collection.rebindMappingHint',
                  '每行填写原记录 ID、新记录 ID，用英文逗号隔开。原关联没有记录时可留空。')}} : {}),
              ...(conversionOptions.length ? {conversionSource: {type: 'string' as const,
                title: t('app.relation.collection.conversionSource', '迁移已有多对一关系'),
                oneOf: [{const: '', title: t('app.relation.collection.emptyStart', '从空集合开始')},
                  ...conversionOptions.map(item => {
                    const field = document.fields.find(value => value.fieldId === item.sourceField?.fieldId);
                    return {const: item.relationId, title: field ? resolveLocalizedText(field.labelLocalization, locale, undefined, 'TENANT', field.label).text : item.relationId};
                  })]}} : {}),
            }}}
            uiSchema={{mappingText: {'ui:widget': 'textarea', 'ui:options': {rows: 7}}}}
            formData={{label, conversionSource, mappingText}} onChange={({formData}, changed) => {
              if (!locked && changed && formData?.label) {
                setLabel(formData.label);
                setConversionSource(formData.conversionSource ?? '');
                if (pendingRebind) setMappingText(formData.mappingText ?? '');
              }
            }}
            onSubmit={apply}/>
          {conversionSource && <Alert type="info" showIcon title={t('app.relation.collection.conversionEffect',
            '发布会把现有引用迁入新集合，并停用原引用字段。历史记录保留。')}/>}
          {pendingRebind && <Alert type="info" showIcon title={t('app.relation.collection.rebindEffect',
            '发布会按映射迁移现有关联，切换后停用旧关联，并保留两张表单的业务记录。')}/>}
        </>},
      {key: 'qualification', icon: createIcon('SafetyCertificateOutlined'), label: t('app.relation.qualification', '关联校验'),
        children: <>
          <SForm hideSubmit disabled={locked || !metadata} formContext={designerFormContext}
            schema={{type: 'object', properties: {enabled: {type: 'boolean', title: t('app.relation.enableCondition', '启用条件')}}}}
            formData={{enabled: !!qualification}} onChange={({formData}, changed) => {
              if (!locked && changed) setQualification(formData?.enabled ? qualification ?? {op: 'and', children: []} : undefined);
            }}/>
          {metadata && qualification && <RelationCondition metadata={metadata} value={qualification}
            disabled={locked} onChange={setQualification}/>}
        </>},
    ]}/>
  </Modal>;
}
