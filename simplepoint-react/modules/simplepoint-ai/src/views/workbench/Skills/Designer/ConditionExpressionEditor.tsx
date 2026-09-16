import {DeleteOutlined, PlusOutlined} from '@ant-design/icons';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueries} from '@tanstack/react-query';
import {Alert, Button, Card, Input, Select, Space, Typography} from 'antd';
import {loadMcpServerCapabilities} from './api';
import {toolBindingFor} from './document';
import SchemaValueEditor, {defaultValueForSchema} from './SchemaValueEditor';
import type {DesignerNode, SkillDesignerDocument} from './types';

const {Text} = Typography;

type SchemaNode = Record<string, unknown>;
type ConditionExpression = Record<string, unknown>;
type ConditionOperator = 'isTrue' | 'equals' | 'notEquals' | 'all' | 'any' | 'not';

type PathOption = {
  path: string;
  schema: SchemaNode;
};

type StepOption = {
  nodeId: string;
  paths: PathOption[];
};

const childSchema = (value: unknown): SchemaNode => value && typeof value === 'object' && !Array.isArray(value)
  ? value as SchemaNode
  : {type: 'string'};

const propertiesOf = (schema?: SchemaNode) => schema?.properties
  && typeof schema.properties === 'object'
  && !Array.isArray(schema.properties)
  ? schema.properties as Record<string, unknown>
  : {};

const schemaPaths = (schema: SchemaNode, prefix = ''): PathOption[] => Object.entries(propertiesOf(schema))
  .flatMap(([name, raw]) => {
    const nested = childSchema(raw);
    const path = prefix ? `${prefix}.${name}` : name;
    return [{path, schema: nested}, ...schemaPaths(nested, path)];
  });

const asExpression = (value: unknown): ConditionExpression => value && typeof value === 'object' && !Array.isArray(value)
  ? value as ConditionExpression
  : {isTrue: {$ref: 'input.enabled'}};

const operatorOf = (expression: ConditionExpression): ConditionOperator => {
  const candidate = Object.keys(expression)[0];
  return ['isTrue', 'equals', 'notEquals', 'all', 'any', 'not'].includes(candidate)
    ? candidate as ConditionOperator
    : 'isTrue';
};

const referenceOf = (value: unknown, fallback: string) => value && typeof value === 'object' && !Array.isArray(value)
  && typeof (value as Record<string, unknown>).$ref === 'string'
  ? String((value as Record<string, unknown>).$ref)
  : fallback;

const defaultExpression = (operator: ConditionOperator, reference: string): ConditionExpression => {
  if (operator === 'isTrue') return {isTrue: {$ref: reference}};
  if (operator === 'equals' || operator === 'notEquals') return {[operator]: [{$ref: reference}, '']};
  if (operator === 'not') return {not: {isTrue: {$ref: reference}}};
  return {[operator]: [{isTrue: {$ref: reference}}]};
};

const ReferenceEditor = ({
  reference,
  inputPaths,
  steps,
  onChange,
}: {
  reference: string;
  inputPaths: PathOption[];
  steps: StepOption[];
  onChange: (reference: string) => void;
}) => {
  const {t} = useI18n();
  const inputMatch = /^input\.(.+)$/.exec(reference);
  const stepMatch = /^steps\.([^.]+)\.(.+)$/.exec(reference);
  const source = stepMatch ? 'step' : 'input';
  const selectedStep = steps.find((step) => step.nodeId === stepMatch?.[1]);
  return (
    <Space direction="vertical" size={6} style={{width: '100%'}}>
      <Select
        value={source}
        options={[
          {label: t('ai.skills.designer.mapping.skillInput', 'Skill Input'), value: 'input'},
          {label: t('ai.skills.designer.reference.previousOutput', '前序节点输出'), value: 'step', disabled: steps.length === 0},
        ]}
        onChange={(next) => {
          if (next === 'input') onChange(`input.${inputPaths[0]?.path ?? 'enabled'}`);
          else onChange(`steps.${steps[0]?.nodeId ?? 'step'}.${steps[0]?.paths[0]?.path ?? 'value'}`);
        }}
      />
      {source === 'input' && (inputPaths.length > 0 ? (
        <Select
          showSearch
          value={inputMatch?.[1]}
          placeholder={t('ai.skills.designer.reference.selectInputField', '选择 Input 字段')}
          options={inputPaths.map((item) => ({
            label: `${item.path} · ${String(item.schema.type ?? t('ai.skills.designer.common.unknown', '未知'))}`,
            value: item.path,
          }))}
          onChange={(path) => onChange(`input.${path}`)}
        />
      ) : (
        <Input
          value={inputMatch?.[1] ?? ''}
          addonBefore="input."
          placeholder={t('ai.skills.designer.reference.fieldPath', '字段路径')}
          onChange={(event) => onChange(`input.${event.target.value}`)}
        />
      ))}
      {source === 'step' && (
        <Space.Compact block>
          <Select
            value={stepMatch?.[1]}
            style={{width: '45%'}}
            placeholder={t('ai.skills.designer.reference.previousNode', '前序节点')}
            options={steps.map((step) => ({label: step.nodeId, value: step.nodeId}))}
            onChange={(nodeId) => {
              const step = steps.find((candidate) => candidate.nodeId === nodeId);
              onChange(`steps.${nodeId}.${step?.paths[0]?.path ?? 'value'}`);
            }}
          />
          {selectedStep?.paths.length ? (
            <Select
              showSearch
              value={stepMatch?.[2]}
              style={{width: '55%'}}
              placeholder={t('ai.skills.designer.reference.outputField', '输出字段')}
              options={selectedStep.paths.map((item) => ({
                label: `${item.path} · ${String(item.schema.type ?? t('ai.skills.designer.common.unknown', '未知'))}`,
                value: item.path,
              }))}
              onChange={(path) => onChange(`steps.${stepMatch?.[1]}.${path}`)}
            />
          ) : (
            <Input
              value={stepMatch?.[2] ?? ''}
              placeholder={t('ai.skills.designer.reference.outputFieldPath', '输出字段路径')}
              onChange={(event) => onChange(`steps.${stepMatch?.[1] ?? steps[0]?.nodeId ?? 'step'}.${event.target.value}`)}
            />
          )}
        </Space.Compact>
      )}
      {source === 'step' && stepMatch?.[1] && !selectedStep?.paths.length && (
        <Alert type="warning" message={t('ai.skills.designer.reference.weakOutput', '该节点未声明 outputSchema，当前为弱类型路径引用')} />
      )}
    </Space>
  );
};

const ExpressionNode = ({
  value,
  inputPaths,
  steps,
  defaultReference,
  schemaForReference,
  onChange,
  depth,
}: {
  value: ConditionExpression;
  inputPaths: PathOption[];
  steps: StepOption[];
  defaultReference: string;
  schemaForReference: (reference: string) => SchemaNode;
  onChange: (value: ConditionExpression) => void;
  depth: number;
}) => {
  const {t} = useI18n();
  const operator = operatorOf(value);
  const operand = value[operator];
  const comparison = Array.isArray(operand) ? operand : [];
  const reference = referenceOf(operator === 'isTrue' ? operand : comparison[0], defaultReference);
  const changeOperator = (next: ConditionOperator) => {
    if (next === 'equals' || next === 'notEquals') {
      onChange({[next]: [{$ref: reference}, defaultValueForSchema(schemaForReference(reference))]});
      return;
    }
    onChange(defaultExpression(next, reference));
  };
  return (
    <Card size="small" styles={{body: {padding: 10}}}>
      <Space direction="vertical" size={8} style={{width: '100%'}}>
        <Select<ConditionOperator>
          value={operator}
          options={[
            {label: t('ai.skills.designer.condition.isTrue', '为真 (isTrue)'), value: 'isTrue'},
            {label: t('ai.skills.designer.condition.equals', '等于 (equals)'), value: 'equals'},
            {label: t('ai.skills.designer.condition.notEquals', '不等于 (notEquals)'), value: 'notEquals'},
            {label: t('ai.skills.designer.condition.all', '全部满足 (all)'), value: 'all'},
            {label: t('ai.skills.designer.condition.any', '任一满足 (any)'), value: 'any'},
            {label: t('ai.skills.designer.condition.not', '取反 (not)'), value: 'not'},
          ]}
          onChange={changeOperator}
        />
        {(operator === 'isTrue' || operator === 'equals' || operator === 'notEquals') && (
          <>
            <ReferenceEditor
              reference={reference}
              inputPaths={inputPaths}
              steps={steps}
              onChange={(nextReference) => onChange(operator === 'isTrue'
                ? {isTrue: {$ref: nextReference}}
                : {[operator]: [{$ref: nextReference}, defaultValueForSchema(schemaForReference(nextReference))]})}
            />
            {(operator === 'equals' || operator === 'notEquals') && (
              <div>
                <Text type="secondary">{t('ai.skills.designer.condition.compareValue', '比较值')}</Text>
                <div style={{marginTop: 6}}>
                  <SchemaValueEditor
                    schema={schemaForReference(reference)}
                    value={comparison[1]}
                    depth={depth + 1}
                    onChange={(expected) => onChange({[operator]: [{$ref: reference}, expected]})}
                  />
                </div>
              </div>
            )}
          </>
        )}
        {(operator === 'all' || operator === 'any') && (() => {
          const children = Array.isArray(operand) && operand.length > 0
            ? operand.map(asExpression)
            : [defaultExpression('isTrue', defaultReference)];
          if (depth >= 8) return <Alert type="error" message={t('ai.skills.designer.condition.maxDepth', 'Condition 最多支持 8 层组合')} />;
          return (
            <Space direction="vertical" size={8} style={{width: '100%'}}>
              {children.map((child, index) => (
                <Card
                  key={index}
                  size="small"
                  title={t('ai.skills.designer.condition.item', '条件 {index}', {index: index + 1})}
                  extra={(
                    <Button
                      danger
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={t('ai.skills.designer.condition.delete', '删除条件 {index}', {index: index + 1})}
                      disabled={children.length <= 1}
                      onClick={() => onChange({[operator]: children.filter((_, current) => current !== index)})}
                    />
                  )}
                >
                  <ExpressionNode
                    value={child}
                    inputPaths={inputPaths}
                    steps={steps}
                    defaultReference={defaultReference}
                    schemaForReference={schemaForReference}
                    depth={depth + 1}
                    onChange={(next) => onChange({[operator]: children.map((current, currentIndex) => currentIndex === index ? next : current)})}
                  />
                </Card>
              ))}
              <Button
                block
                icon={<PlusOutlined />}
                disabled={children.length >= 16}
                onClick={() => onChange({[operator]: [...children, defaultExpression('isTrue', defaultReference)]})}
              >{t('ai.skills.designer.condition.add', '添加条件')}</Button>
            </Space>
          );
        })()}
        {operator === 'not' && (
          depth >= 8
            ? <Alert type="error" message={t('ai.skills.designer.condition.maxDepth', 'Condition 最多支持 8 层组合')} />
            : (
              <ExpressionNode
                value={asExpression(operand)}
                inputPaths={inputPaths}
                steps={steps}
                defaultReference={defaultReference}
                schemaForReference={schemaForReference}
                depth={depth + 1}
                onChange={(next) => onChange({not: next})}
              />
            )
        )}
      </Space>
    </Card>
  );
};

const ConditionExpressionEditor = ({
  document,
  node,
  onChange,
}: {
  document: SkillDesignerDocument;
  node: DesignerNode;
  onChange: (condition: ConditionExpression) => void;
}) => {
  const inputPaths = schemaPaths(document.inputSchema);
  const upstreamNodes = document.nodes
    .filter((candidate) => (
      candidate.type !== 'INPUT'
      && candidate.type !== 'OUTPUT'
      && (candidate.parentNodeId ?? null) === (node.parentNodeId ?? null)
      && (candidate.branchId ?? null) === (node.branchId ?? null)
      && candidate.order < node.order
    ))
    .sort((left, right) => left.order - right.order);
  const bindings = upstreamNodes.map((candidate) => candidate.type === 'TOOL'
    ? toolBindingFor(document, candidate)
    : {alias: '', serverId: '', snapshotId: '', name: ''});
  const queryScope = useQueryScope();
  const queries = useQueries({
    queries: bindings.map((binding) => ({
      ...scopedQueryOptions(queryScope, ['skill-designer-mcp-capabilities', binding.serverId], ({signal}) => loadMcpServerCapabilities(binding.serverId, signal)),
      enabled: Boolean(binding.serverId),
    })),
  });
  const steps: StepOption[] = upstreamNodes.map((candidate, index) => {
    const binding = bindings[index];
    const schema = queries[index]?.data?.tools.find((tool) => tool.name === binding.name)?.outputSchema;
    return {nodeId: candidate.id, paths: schema ? schemaPaths(schema) : []};
  });
  const defaultReference = inputPaths[0]
    ? `input.${inputPaths[0].path}`
    : steps[0]
      ? `steps.${steps[0].nodeId}.${steps[0].paths[0]?.path ?? 'value'}`
      : 'input.enabled';
  const schemaForReference = (reference: string): SchemaNode => {
    const inputMatch = /^input\.(.+)$/.exec(reference);
    if (inputMatch) return inputPaths.find((item) => item.path === inputMatch[1])?.schema ?? {type: 'string'};
    const stepMatch = /^steps\.([^.]+)\.(.+)$/.exec(reference);
    if (stepMatch) return steps.find((step) => step.nodeId === stepMatch[1])?.paths
      .find((item) => item.path === stepMatch[2])?.schema ?? {type: 'string'};
    return {type: 'string'};
  };
  return (
    <ExpressionNode
      value={asExpression(node.configuration.condition)}
      inputPaths={inputPaths}
      steps={steps}
      defaultReference={defaultReference}
      schemaForReference={schemaForReference}
      depth={0}
      onChange={onChange}
    />
  );
};

export default ConditionExpressionEditor;
