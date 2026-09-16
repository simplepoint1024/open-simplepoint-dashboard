import {useQueries} from '@tanstack/react-query';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Card, Empty, Input, Select, Space, Tag, Typography} from 'antd';
import {loadMcpServerCapabilities} from './api';
import {toolBindingFor} from './document';
import type {DesignerNode, SkillDesignerDocument} from './types';
import SchemaValueEditor, {defaultValueForSchema} from './SchemaValueEditor';

const {Text} = Typography;

type SchemaNode = Record<string, unknown>;

type OutputField = {
  path: string;
  schema: SchemaNode;
  required: boolean;
};

type MappingSource = 'fixed' | 'input' | 'step';

const propertiesOf = (schema?: SchemaNode) => schema?.properties
  && typeof schema.properties === 'object'
  && !Array.isArray(schema.properties)
  ? schema.properties as Record<string, unknown>
  : {};

const schemaType = (schema?: SchemaNode) => String(schema?.type ?? 'string');

const childSchema = (value: unknown): SchemaNode => value && typeof value === 'object' && !Array.isArray(value)
  ? value as SchemaNode
  : {type: 'string'};

const outputFields = (schema: SchemaNode, prefix = '', parentRequired = true): OutputField[] => {
  const properties = propertiesOf(schema);
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  return Object.entries(properties).flatMap(([name, raw]) => {
    const child = childSchema(raw);
    const path = prefix ? `${prefix}.${name}` : name;
    const isRequired = parentRequired && required.has(name);
    if (schemaType(child) === 'object' && Object.keys(propertiesOf(child)).length > 0) {
      return outputFields(child, path, isRequired);
    }
    return [{path, schema: child, required: isRequired}];
  });
};

const selectableSchemaPaths = (schema: SchemaNode, prefix = ''): Array<{path: string; schema: SchemaNode}> => Object.entries(propertiesOf(schema))
  .flatMap(([name, raw]) => {
    const child = childSchema(raw);
    const path = prefix ? `${prefix}.${name}` : name;
    return [{path, schema: child}, ...selectableSchemaPaths(child, path)];
  });

const compatible = (sourceType: unknown, targetType: string) => {
  if (typeof sourceType !== 'string') return true;
  return sourceType === targetType || targetType === 'number' && sourceType === 'integer';
};

const mappingSource = (value: unknown): MappingSource => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return 'fixed';
  const reference = (value as Record<string, unknown>).$ref;
  if (typeof reference !== 'string') return 'fixed';
  return reference.startsWith('input.') ? 'input' : reference.startsWith('steps.') ? 'step' : 'fixed';
};

const getPath = (value: unknown, path: string): unknown => path.split('.').reduce<unknown>((current, segment) => (
  current && typeof current === 'object' && !Array.isArray(current)
    ? (current as Record<string, unknown>)[segment]
    : undefined
), value);

const setPath = (value: unknown, path: string, nextValue: unknown): Record<string, unknown> => {
  const root = value && typeof value === 'object' && !Array.isArray(value)
    ? {...value as Record<string, unknown>}
    : {};
  const segments = path.split('.');
  let target = root;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      target[segment] = nextValue;
      return;
    }
    const existing = target[segment];
    const child = existing && typeof existing === 'object' && !Array.isArray(existing)
      ? {...existing as Record<string, unknown>}
      : {};
    target[segment] = child;
    target = child;
  });
  return root;
};

export const pruneWorkflowOutput = (schema: SchemaNode, value: unknown): Record<string, unknown> => {
  const source = value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
  const result: Record<string, unknown> = {};
  Object.entries(propertiesOf(schema)).forEach(([name, raw]) => {
    if (!(name in source)) return;
    const child = childSchema(raw);
    const current = source[name];
    const reference = current && typeof current === 'object' && !Array.isArray(current)
      && typeof (current as Record<string, unknown>).$ref === 'string';
    result[name] = schemaType(child) === 'object'
      && Object.keys(propertiesOf(child)).length > 0
      && !reference
      ? pruneWorkflowOutput(child, current)
      : current;
  });
  return result;
};

const OutputMappingEditor = ({
  document,
  onChange,
}: {
  document: SkillDesignerDocument;
  onChange: (document: SkillDesignerDocument) => void;
}) => {
  const {t} = useI18n();
  const fields = outputFields(document.outputSchema);
  const inputPaths = selectableSchemaPaths(document.inputSchema);
  const upstreamNodes = document.nodes
    .filter((node) => node.type !== 'INPUT' && node.type !== 'OUTPUT' && !node.parentNodeId)
    .sort((left, right) => left.order - right.order);
  const upstreamBindings = upstreamNodes.map((node) => node.type === 'TOOL'
    ? toolBindingFor(document, node)
    : {alias: '', serverId: '', snapshotId: '', name: ''});
  const queryScope = useQueryScope();
  const upstreamQueries = useQueries({
    queries: upstreamBindings.map((binding) => ({
      ...scopedQueryOptions(queryScope, ['skill-designer-mcp-capabilities', binding.serverId], ({signal}) => loadMcpServerCapabilities(binding.serverId, signal)),
      enabled: Boolean(binding.serverId),
    })),
  });
  const outputSchemaFor = (nodeId?: string) => {
    const index = upstreamNodes.findIndex((node) => node.id === nodeId);
    if (index < 0) return undefined;
    const binding = upstreamBindings[index];
    return upstreamQueries[index]?.data?.tools.find((tool) => tool.name === binding.name)?.outputSchema;
  };
  const workflowOutput = document.workflowOutput && typeof document.workflowOutput === 'object'
    && !Array.isArray(document.workflowOutput)
    ? document.workflowOutput as Record<string, unknown>
    : {};
  const updateField = (path: string, value: unknown) => onChange({
    ...document,
    workflowOutput: setPath(workflowOutput, path, value),
  });

  if (fields.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ai.skills.designer.mapping.addOutputFieldsFirst', '先在上方添加 Output Schema 字段')} />;
  }

  return (
    <Space direction="vertical" size={10} style={{width: '100%'}}>
      {fields.map((field) => {
        const value = getPath(workflowOutput, field.path);
        const source = mappingSource(value);
        const type = schemaType(field.schema);
        const reference = value && typeof value === 'object' && !Array.isArray(value)
          ? String((value as Record<string, unknown>).$ref ?? '')
          : '';
        const stepMatch = /^steps\.([^.]+)\.(.+)$/.exec(reference);
        const selectedStepOutput = outputSchemaFor(stepMatch?.[1]);
        const stepPaths = selectedStepOutput ? selectableSchemaPaths(selectedStepOutput) : [];
        return (
          <Card key={field.path} size="small" styles={{body: {padding: 10}}}>
            <Space direction="vertical" size={7} style={{width: '100%'}}>
              <Space wrap>
                <Text strong>{field.path}</Text>
                <Tag>{type}</Tag>
                {field.required && <Tag color="red">{t('ai.skills.designer.schemaValue.required', '必填')}</Tag>}
              </Space>
              <Select<MappingSource>
                value={source}
                options={[
                  {label: t('ai.skills.designer.mapping.fixedValue', '固定值'), value: 'fixed'},
                  {label: t('ai.skills.designer.mapping.skillInput', 'Skill 输入'), value: 'input'},
                  {label: t('ai.skills.designer.mapping.upstreamOutput', '上游节点输出'), value: 'step'},
                ]}
                onChange={(next) => {
                  if (next === 'fixed') updateField(field.path, defaultValueForSchema(field.schema));
                  if (next === 'input') updateField(field.path, {$ref: `input.${inputPaths.find((item) => compatible(item.schema.type, type))?.path ?? field.path}`});
                  if (next === 'step') updateField(field.path, {$ref: `steps.${upstreamNodes[0]?.id ?? 'step'}.value`});
                }}
              />
              {source === 'fixed' && (
                <SchemaValueEditor schema={field.schema} value={value} onChange={(next) => updateField(field.path, next)} />
              )}
              {source === 'input' && (
                <Select
                  showSearch
                  value={reference.replace(/^input\./, '')}
                  options={inputPaths.map((item) => ({
                    label: `${item.path} · ${schemaType(item.schema)}`,
                    value: item.path,
                    disabled: !compatible(item.schema.type, type),
                  }))}
                  onChange={(path) => updateField(field.path, {$ref: `input.${path}`})}
                />
              )}
              {source === 'step' && (
                <>
                  <Space.Compact block>
                    <Select
                      value={stepMatch?.[1]}
                      style={{width: '45%'}}
                      placeholder={t('ai.skills.designer.mapping.upstreamNode', '上游节点')}
                      options={upstreamNodes.map((node: DesignerNode) => ({label: node.id, value: node.id}))}
                      onChange={(nodeId) => {
                        const schema = outputSchemaFor(nodeId);
                        const path = schema ? selectableSchemaPaths(schema).find((item) => compatible(item.schema.type, type))?.path : undefined;
                        updateField(field.path, {$ref: `steps.${nodeId}.${path ?? 'value'}`});
                      }}
                    />
                    {stepPaths.length > 0 ? (
                      <Select
                        showSearch
                        value={stepMatch?.[2]}
                        style={{width: '55%'}}
                        placeholder={t('ai.skills.designer.reference.outputField', '输出字段')}
                        options={stepPaths.map((item) => ({
                          label: `${item.path} · ${schemaType(item.schema)}`,
                          value: item.path,
                          disabled: !compatible(item.schema.type, type),
                        }))}
                        onChange={(path) => updateField(field.path, {$ref: `steps.${stepMatch?.[1]}.${path}`})}
                      />
                    ) : (
                      <Input
                        value={stepMatch?.[2] ?? ''}
                        placeholder={t('ai.skills.designer.reference.outputFieldPath', '输出字段路径')}
                        onChange={(event) => updateField(field.path, {$ref: `steps.${stepMatch?.[1] ?? upstreamNodes[0]?.id ?? 'step'}.${event.target.value}`})}
                      />
                    )}
                  </Space.Compact>
                  {stepMatch?.[1] && !selectedStepOutput && <Alert type="warning" message={t('ai.skills.designer.mapping.weakOutput', '该节点没有 outputSchema，当前为弱类型路径映射')} />}
                </>
              )}
            </Space>
          </Card>
        );
      })}
    </Space>
  );
};

export default OutputMappingEditor;
