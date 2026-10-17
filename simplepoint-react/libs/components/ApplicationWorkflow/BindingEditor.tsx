import {Alert, Space, Spin} from 'antd';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import EntitySelect, {type EntitySelectSource} from '../EntitySelect';
import SForm from '../SForm/Lazy';
import type {WorkflowFormAdapter} from '../WorkflowDesigner/contracts';

type Props = Parameters<NonNullable<WorkflowFormAdapter['renderBindingEditor']>>[0];
type Detail = {fields: Array<{fieldId: string; label: string; type: string}>;
  scenes: Array<{sceneId: string; name: string; type: string}>};
const source: EntitySelectSource = {
  key: 'app.workflow-form-versions', searchParams: (keyword, page, size) => ({keyword, page, size}),
  option: row => typeof row.id === 'string' && typeof row.name === 'string' ? {value: row.id, label: row.name} : undefined,
  resolve: async (ids, signal) => (await get<Array<{id: string; name: string}>>('/app/workflow/forms/options', {ids}, {signal}))
    .map(row => ({value: row.id, label: row.name})),
};
const compatible: Record<string, readonly string[]> = {
  decimal: ['INT32', 'INT64', 'DECIMAL'], number: ['INT32'], boolean: ['BOOLEAN'], date: ['DATE'], 'date-time': ['DATETIME'],
  user: ['STRING', 'UUID'], role: ['STRING', 'UUID'], string: ['STRING', 'TEXT', 'UUID', 'TIME'],
};

/** Published metadata only; business field values never enter the process designer. */
export default function BindingEditor({elementId, binding: saved, startBinding, variables, readOnly, onChange}: Props) {
  const {t} = useI18n();
  const task = elementId !== 'process:start';
  const binding = saved ?? (task && startBinding?.provider === 'simplepoint-app' ? startBinding : undefined);
  const identity = binding ? `${binding.formId}:${binding.version}` : undefined;
  const query = useData(['app', 'workflow-form', identity], ({signal}) =>
    get<Detail>(`/app/workflow/forms/${encodeURIComponent(identity!)}`, undefined, {signal}), {enabled: !!identity, retry: false});
  const fields = query.data?.fields ?? [], scenes = (query.data?.scenes ?? []).filter(scene => elementId !== 'process:start' || scene.type === 'MAIN');
  return <Space direction="vertical" style={{width: '100%'}}>
    <EntitySelect endpoint="/app/workflow/forms" source={source} value={identity} disabled={readOnly || task} allowClear={false}
      ariaLabel={t('app.workflow.formVersion', '已发布表单版本')} placeholder={t('app.workflow.formVersion', '已发布表单版本')}
      onChange={id => {
        if (!id) return;
        const [formId, version] = id.split(':');
        onChange({provider: 'simplepoint-app', formId, version: Number(version), variableMappings: {}});
      }}/>
    {query.isFetching && identity && <Spin size="small"/>}
    {query.error && <Alert type="error" showIcon title={t('app.workflow.metadataFailed', '表单元数据加载失败，请重新选择')}/>}
    {binding && query.data && <>
      <SForm hideSubmit disabled={readOnly} schema={{type: 'object', properties: {
        sceneId: {type: 'string', title: t('app.workflow.scene', '展示场景'), oneOf: scenes.map(scene => ({const: scene.sceneId, title: scene.name}))},
      }}} formData={{sceneId: binding.sceneId}} onChange={event => onChange({...binding, sceneId: event.formData?.sceneId})}/>
      <SForm hideSubmit disabled={readOnly || task} schema={{type: 'object', additionalProperties: false,
        properties: Object.fromEntries(fields.map(field => [field.fieldId, {type: 'string', title: field.label,
          oneOf: [{const: '', title: t('app.workflow.unmapped', '不映射')}, ...variables
            .filter(variable => ['start', 'both'].includes(variable.scope) && compatible[variable.type]?.includes(field.type))
            .map(variable => ({const: variable.name, title: `${variable.label} (${variable.name})`}))],
        }]))}} formData={binding.variableMappings} onChange={event => onChange({...binding,
        variableMappings: Object.fromEntries(Object.entries(event.formData ?? {}).filter(([, value]) => typeof value === 'string' && value)) as Record<string, string>,
      })}/>
    </>}
  </Space>;
}
