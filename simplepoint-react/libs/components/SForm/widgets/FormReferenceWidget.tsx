import {ReferenceMappingContext} from '../../FormRuntime/referenceMapping/Context';
import {useContext, useMemo, useState} from 'react';
import type {WidgetProps} from '@rjsf/utils';
import EntitySelect from '../../EntitySelect';
import HelpTip from '../../HelpTip';
import TableActions from '../../Table/Actions';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {ReferenceRuntimeContext} from '../../FormComponents/Reference/RuntimeContext';
import FormReferenceValue from '../../FormComponents/Reference/Value';
import {ReferenceInputContext, referenceContextReady, referenceSearchContext} from '../../FormComponents/Reference/inputs';

/** Reference values use the platform selector and the owning form's trusted source adapter. */
export default function FormReferenceWidget({id, name, value, onChange, disabled, readonly, required, schema, placeholder, registry}: WidgetProps) {
  const mapping = useContext(ReferenceMappingContext);
  const sources = useContext(ReferenceRuntimeContext);
  const {t} = useI18n();
  const inputs = useContext(ReferenceInputContext), reference = schema['x-form-reference'];
  const mapped = Boolean(reference?.mappings?.some((item: {mode: string}) => item.mode !== 'LIVE'));
  const change = (next: unknown) => {
    if (!mapped) onChange(next);
    else mapping?.change({fieldId: name, detailId: inputs.detailId, rowKey: inputs.rowKey}, typeof next === 'string' ? next : null, registry.formContext.beginPreparation);
  };
  const relationId = reference?.relationId;
  const context = reference ? referenceSearchContext(reference, inputs) : undefined;
  const contextKey = JSON.stringify(context);
  const ready = referenceContextReady(reference, context);
  const binding = useMemo(() => sources && typeof relationId === 'string' ? sources(relationId,
    contextKey ? JSON.parse(contextKey) : undefined) : undefined, [sources, relationId, contextKey]);
  const [review, setReview] = useState({sources, relationId, contextKey, value, pending: false});
  if (review.sources !== sources || review.relationId !== relationId || review.value !== value || review.contextKey !== contextKey) {
    // This is a local confirmation hint, not a write grant or a second form value buffer.
    setReview({sources, relationId, contextKey, value, pending: review.sources === sources && review.relationId === relationId
      && review.value === value && Boolean(value) && Boolean(reference?.contextual) && review.contextKey !== contextKey});
  }
  if (!binding || value === '***') return <span id={id}>{value == null || value === '' ? '—' : String(value)}</span>;
  if (readonly) return <span id={id}><FormReferenceValue value={value} relationId={relationId}/></span>;
  return <div style={{display: 'flex', alignItems: 'center', gap: 8}}><div style={{flex: 1, minWidth: 0}}>
    <EntitySelect id={id} endpoint={binding.endpoint} source={binding.source} multiple={false} status={review.pending ? 'warning' : undefined}
      value={typeof value === 'string' ? value : undefined} disabled={disabled || readonly || !ready || reference?.searchable === false || reference?.mappingWritable === false || mapped && (!mapping || mapping.busy)} allowClear={!required}
      renderSelected={option => <FormReferenceValue value={option.value} relationId={relationId} presentation="label"/>}
      placeholder={placeholder} onChange={change}/>
    </div>{reference?.searchable === false ? <HelpTip>{t('app.relation.contextUnavailable', '联动条件字段不可读，暂不能搜索；已有引用保持不变。')}</HelpTip>
      : !ready && <HelpTip>{t('app.relation.contextIncomplete', '请先填写有效的联动条件字段，再选择关联记录。')}</HelpTip>}
    {review.pending && !disabled && <TableActions appearance="toolbar" selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'retain', icon: 'CheckOutlined', iconOnly: true, type: 'text', sort: 0, text: t('app.relation.retainSelection', '保留关联'),
        title: t('app.relation.contextReview', '联动条件已变化，请核对或重新选择关联记录。保留原关联不会跳过服务端保存校验。')},
    ]} onButtonEvents={{retain: () => setReview(current => ({...current, pending: false}))}}/>}
    {mapped && !disabled && reference?.mappingWritable !== false && <TableActions appearance="toolbar" selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'refreshMapping', sort: 0, icon: 'SyncOutlined', iconOnly: true, type: 'text', disabled: !mapping || mapping.busy,
        text: t('app.relation.mappingRefresh', '重新带入关联值')},
    ]} onButtonEvents={{refreshMapping: () => change(value)}}/>}
    {!disabled && <FormReferenceValue value={value} relationId={relationId} presentation="action"/>}
  </div>;
}
