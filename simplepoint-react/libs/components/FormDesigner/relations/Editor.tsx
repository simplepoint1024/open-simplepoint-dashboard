import {useContext, useRef, useState} from 'react';
import {Alert, Modal, Tabs} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {createIcon} from '@simplepoint/shared/types/icon';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import EntitySelect from '../../EntitySelect';
import SForm, {type SFormHandle} from '../../SForm';
import TableActions from '../../Table/Actions';
import HelpTip from '../../HelpTip';
import type {DesignDocument} from '../contracts';
import type {FormRelationField} from '../relationContracts';
import {designerFormContext} from '../presentation';
import {sameDesignValue} from '../draftState';
import {DesignerRelationContext} from './Context';
import {relationTargetIdentity, type DesignerRelationDirectory} from './catalog';
import {applyRelationEdit, fieldRelation, relationSourceField, RelationEditError} from './model';
import {changeRelationTarget, compileRelationDraft, relationDraft} from './edit';
import RelationCondition from './Condition';
import ContextFilters from './ContextFilters';
import Mappings from './Mappings';

type Props = {document: DesignDocument; source: FormRelationField; disabled: boolean;
  onChange: (document: DesignDocument) => void; onClose: () => void};

/** The owning document receives one immutable command; cancel and obsolete scopes never write. */
export default function RelationEditor(props: Props) {
  const directory = useContext(DesignerRelationContext);
  return directory && !fieldRelation(props.document, props.source)?.retiredByCollectionId
    && <RelationEditorSession key={JSON.stringify([directory.key, props.source])} {...props} directory={directory}/>;
}

function RelationEditorSession({document, source, disabled, onChange, onClose, directory}: Props & {directory: DesignerRelationDirectory}) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  const [base] = useState(() => structuredClone(fieldRelation(document, source)));
  const [baseField] = useState(() => structuredClone(relationSourceField(document, source)));
  const [initial] = useState(() => relationDraft(base));
  const [value, setValue] = useState(initial), [tab, setTab] = useState('relation');
  const [failure, setFailure] = useState(''), [discard, setDiscard] = useState(false);
  const form = useRef<SFormHandle>(null), locked = disabled || !selected;
  usePageRetention(false, true);
  const query = useOwnedData(['form-relation-target', directory.key, directory.endpoint, value.target],
    ({signal}) => directory.describe(value.target!, signal), {enabled: selected && Boolean(value.target), retry: false, staleTime: 0, gcTime: 0,
      refetchOnMount: 'always', refetchOnWindowFocus: 'always'});
  // A returning tab must reauthorize before exposing target fields or enabling a commit.
  const metadata = selected && !query.isFetching && !query.error ? query.data : undefined;
  const close = () => {if (selected) sameDesignValue(value, initial) ? onClose() : setDiscard(true);};
  const update = (next: typeof value) => {if (!locked) {setValue(next); setFailure('');}};
  const options = (searchable = false) => metadata?.fields.filter(field => !searchable || field.searchable).map(field => ({const: field.fieldId,
    title: resolveLocalizedText(field.labelLocalization, locale, undefined, 'TENANT', field.label).text})) ?? [];
  const apply = () => {
    if (locked || !metadata || !baseField) return;
    try {onChange(applyRelationEdit(document, source, baseField, base, compileRelationDraft(value, source, metadata, document))); onClose();}
    catch (error) {setFailure(error instanceof RelationEditError && error.code === 'conflict'
      ? t('app.designer.dataConflict', '该定义已发生变化，请关闭后重新编辑。')
      : t('app.relation.invalid', '请检查目标资源、显示字段、筛选条件和关联校验规则。'));}
  };
  const conditionTab = (kind: 'candidate' | 'qualification') => <>
    <HelpTip>{kind === 'candidate' ? t('app.relation.candidateHint', '仅限制选择时的候选列表，不改变已有引用，也不替代保存时的关联校验。')
      : t('app.relation.qualificationHint', '由服务端检查新建或换绑的目标；可额外要求提交审批时或每次保存时重新校验。')}</HelpTip>
    <SForm formContext={designerFormContext} hideSubmit disabled={locked || !metadata} idPrefix={`relation_${kind}`}
      schema={{type: 'object', properties: {
        enabled: {type: 'boolean', title: t('app.relation.enableCondition', '启用条件')},
        ...(kind === 'qualification' && value.qualification ? {recheck: {type: 'string' as const, title: t('app.relation.recheck', '重新校验时机'), oneOf: [
          {const: 'ON_CHANGE', title: t('app.relation.onChange', '新建或变更关联时')},
          {const: 'ON_SUBMIT', title: t('app.relation.onSubmit', '新建、变更关联及提交审批时')},
          {const: 'ALWAYS', title: t('app.relation.always', '每次保存或提交时')},
        ]}} : {}),
      }}} formData={{enabled: Boolean(value[kind]), recheck: value.recheck}}
      onChange={({formData}, changed) => {if (changed && formData && !locked && metadata) update({...value,
        [kind]: formData.enabled ? value[kind] ?? {op: 'and', children: []} : undefined,
        recheck: formData.recheck ?? value.recheck});}}/>
    {metadata && value[kind] && <RelationCondition metadata={metadata} value={value[kind]} disabled={locked}
      onChange={condition => update({...value, [kind]: condition})}/>}
  </>;
  return <>
    <Modal open={selected} width={tab === 'mappings' ? 1160 : 880} wrapClassName="sp-form-relation-modal" destroyOnHidden maskClosable={false}
      title={`${baseField?.label ?? ''} · ${t('app.relation.configure', '配置关联')}`} onCancel={close}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
        {key: 'apply', icon: 'CheckOutlined', sort: 1, type: 'primary', text: t('app.designer.applyData', '应用修改'), disabled: locked || !metadata},
      ]} onButtonEvents={{cancel: close, apply: () => {if (!locked && metadata) form.current?.submit();}}}/>}
    >
      {failure && <Alert type="error" showIcon title={failure}/>}
      {value.target && query.isFetching && <Alert type="info" showIcon title={t('loading', '加载中')}/>}
      {value.target && query.error && <Alert type="error" showIcon title={t('app.relation.metadataFailed', '目标资源定义加载失败，请重试。')}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'retry', icon: 'ReloadOutlined', sort: 0, text: t('table.retry', '重试'), disabled: locked || query.isFetching},
        ]} onButtonEvents={{retry: () => {if (!locked) void query.refetch();}}}/>}/>}
      <Tabs activeKey={tab} onChange={setTab} items={[
        {key: 'relation', icon: createIcon('LinkOutlined'), label: t('app.relation.settings', '关联配置'), forceRender: true, children: <>
          <div className="sp-form-relation-target"><label htmlFor="relation_target">{t('app.relation.target', '目标业务资源')}</label>
          <div><EntitySelect id="relation_target" endpoint={directory.endpoint} source={directory.source} disabled={locked}
            value={value.target?.accessResourceId} onChange={(id, option) => {
              if (!locked) update(changeRelationTarget(value, id ? relationTargetIdentity(option?.metadata?.target) : undefined));
            }}/></div></div>
          <SForm formContext={designerFormContext} ref={form} idPrefix="relation_settings" hideSubmit disabled={locked || !metadata}
            schema={{type: 'object', required: ['cardinality', 'labelFieldId'], properties: {
              cardinality: {type: 'string', title: t('app.relation.cardinality', '关系类型'), oneOf: [
                {const: 'MANY_TO_ONE', title: t('app.relation.manyToOne', '多对一')}, {const: 'ONE_TO_ONE', title: t('app.relation.oneToOne', '一对一')},
              ]},
              labelFieldId: {type: 'string', minLength: 1, title: t('app.relation.labelField', '显示名称字段'), oneOf: [{const: '', title: t('app.relation.chooseField', '请选择字段')}, ...options()]},
              searchFieldIds: {type: 'array', title: t('app.relation.searchFields', '搜索字段'), maxItems: 8, uniqueItems: true, items: {type: 'string', oneOf: options(true)}},
              displayFieldIds: {type: 'array', title: t('app.relation.displayFields', '展示字段'), maxItems: 12, uniqueItems: true, items: {type: 'string', oneOf: options()}},
              allowDrillThrough: {type: 'boolean', title: t('app.relation.drillThrough', '允许打开关联详情'),
                description: t('app.relation.drillThroughHint', '仍需目标资源、数据及字段的有效读取权限。')},
            }}} formData={value} onError={() => setTab('relation')}
            onChange={({formData}, changed) => {if (changed && formData && !locked && metadata) update({...value,
              cardinality: formData.cardinality, labelFieldId: formData.labelFieldId,
              searchFieldIds: formData.searchFieldIds ?? [], displayFieldIds: formData.displayFieldIds ?? [], allowDrillThrough: Boolean(formData.allowDrillThrough)});}}
            onSubmit={apply}/>
          {value.cardinality === 'ONE_TO_ONE' && <HelpTip>{t('app.relation.uniqueHint', '同一租户内，一个目标只能被该关系引用一次。明细中的唯一性覆盖全部父记录。')}</HelpTip>}
        </>},
        {key: 'candidate', icon: createIcon('FilterOutlined'), label: t('app.relation.candidate', '候选筛选'), children: conditionTab('candidate')},
        {key: 'context', icon: createIcon('BranchesOutlined'), label: t('app.relation.context', '联动筛选'), children:
          <ContextFilters document={document} source={source} metadata={metadata} value={value.contextFilters} disabled={locked}
            onChange={contextFilters => update({...value, contextFilters})}/>},
        {key: 'qualification', icon: createIcon('SafetyCertificateOutlined'), label: t('app.relation.qualification', '关联校验'), children: conditionTab('qualification')},
        {key: 'mappings', icon: createIcon('SwapOutlined'), label: t('app.relation.mappings', '字段带出'), children:
          <Mappings document={document} source={source} relationId={value.relationId} metadata={metadata}
            value={value.mappings ?? []} disabled={locked} onChange={mappings => update({...value, mappings})}/>},
      ]}/>
    </Modal>
    <Modal open={discard && selected} wrapClassName="sp-form-data-discard" title={t('app.designer.discardDataEdit', '放弃尚未应用的修改？')}
      onCancel={() => setDiscard(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', icon: 'EditOutlined', sort: 0, text: t('app.designer.continueDataEdit', '继续编辑')},
        {key: 'discard', icon: 'DeleteOutlined', sort: 1, danger: true, text: t('app.designer.discardData', '放弃修改')},
      ]} onButtonEvents={{continue: () => setDiscard(false), discard: () => {if (selected) onClose();}}}/>}/>
  </>;
}
