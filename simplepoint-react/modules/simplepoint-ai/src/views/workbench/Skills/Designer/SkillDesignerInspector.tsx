import {DeleteOutlined, PlusOutlined} from '@ant-design/icons';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueries, useQuery} from '@tanstack/react-query';
import {Alert, Button, Card, Collapse, Divider, Empty, Form, Input, InputNumber, Select, Space, Switch, Tag, Typography} from 'antd';
import type {
  DesignerNode,
  McpToolDescriptor,
  PromptBinding,
  ResourceBinding,
  SkillDesignerDocument,
  ToolBinding,
} from './types';
import {
  promptBindingFor,
  addParallelBranch,
  removeNode,
  removeParallelBranch,
  renameParallelBranch,
  resourceBindingFor,
  toolBindingFor,
  updateNodeArguments,
  updateNodeConfiguration,
  updatePromptBinding,
  updateResourceBinding,
  updateToolBinding,
} from './document';
import {loadMcpServerCapabilities, loadMcpServers} from './api';
import OutputMappingEditor, {pruneWorkflowOutput} from './OutputMappingEditor';
import SchemaValueEditor, {defaultValueForSchema} from './SchemaValueEditor';
import ConditionExpressionEditor from './ConditionExpressionEditor';

const {Text, Title} = Typography;

type SchemaType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array';

type SchemaField = {
  name: string;
  type: SchemaType;
  required: boolean;
  definition: Record<string, unknown>;
};

type Props = {
  document: SkillDesignerDocument;
  selectedNode?: DesignerNode;
  onChange: (document: SkillDesignerDocument) => void;
  onClearSelection: () => void;
  onSelectNode: (nodeId: string) => void;
};

const readSchemaFields = (schema: Record<string, unknown>): SchemaField[] => {
  const properties = schema.properties && typeof schema.properties === 'object'
    ? schema.properties as Record<string, unknown>
    : {};
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  return Object.entries(properties).map(([name, raw]) => {
    const definition = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    const candidate = String(definition.type ?? 'string');
    const type = ['string', 'number', 'integer', 'boolean', 'object', 'array'].includes(candidate)
      ? candidate as SchemaType
      : 'string';
    return {name, type, required: required.has(name), definition};
  });
};

const writeSchemaFields = (
  fields: SchemaField[],
  previous: Record<string, unknown> = {},
): Record<string, unknown> => ({
  ...(typeof previous.title === 'string' ? {title: previous.title} : {}),
  ...(typeof previous.description === 'string' ? {description: previous.description} : {}),
  ...(Object.prototype.hasOwnProperty.call(previous, 'enum') ? {enum: previous.enum} : {}),
  ...(Object.prototype.hasOwnProperty.call(previous, 'default') ? {default: previous.default} : {}),
  ...(Object.prototype.hasOwnProperty.call(previous, 'examples') ? {examples: previous.examples} : {}),
  type: 'object',
  properties: Object.fromEntries(fields
    .filter((field) => field.name.trim())
    .map((field) => [field.name.trim(), {...field.definition, type: field.type}])),
  required: fields.filter((field) => field.required && field.name.trim()).map((field) => field.name.trim()),
  additionalProperties: false,
});

const schemaDefinitionForType = (
  type: SchemaType,
  previous: Record<string, unknown> = {},
): Record<string, unknown> => {
  const common = {
    ...(typeof previous.title === 'string' ? {title: previous.title} : {}),
    ...(typeof previous.description === 'string' ? {description: previous.description} : {}),
  };
  if (type === 'object') return {...common, type, properties: {}, required: [], additionalProperties: false};
  if (type === 'array') return {...common, type, items: {type: 'string'}};
  return {...common, type};
};

const withoutSchemaAnnotations = (schema: Record<string, unknown>) => Object.fromEntries(
  Object.entries(schema).filter(([key]) => !['enum', 'default', 'examples'].includes(key)),
);

const sameJsonValue = (left: unknown, right: unknown) => {
  if (Object.is(left, right)) return true;
  try {
    return JSON.stringify(left) === JSON.stringify(right);
  } catch {
    return false;
  }
};

const SchemaAnnotationsEditor = ({
  value,
  onChange,
}: {
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
}) => {
  const {t} = useI18n();
  const type = String(value.type ?? 'string');
  const allowEnum = !['object', 'array'].includes(type);
  const enumValues = Array.isArray(value.enum) ? value.enum : [];
  const examples = Array.isArray(value.examples) ? value.examples : [];
  const hasDefault = Object.prototype.hasOwnProperty.call(value, 'default');
  const valueSchema = withoutSchemaAnnotations(value);
  const removeKeyword = (keyword: string) => onChange(Object.fromEntries(
    Object.entries(value).filter(([key]) => key !== keyword),
  ));
  const updateEnum = (nextValues: unknown[]) => {
    if (nextValues.length === 0) {
      removeKeyword('enum');
      return;
    }
    const next: Record<string, unknown> = {...value, enum: nextValues};
    if (hasDefault && !nextValues.some((item) => sameJsonValue(item, value.default))) {
      next.default = nextValues[0];
    }
    onChange(next);
  };
  const defaultSchema = enumValues.length > 0 ? {...valueSchema, enum: enumValues} : valueSchema;
  return (
    <Collapse
      size="small"
      items={[{
        key: 'schema-annotations',
        label: t('ai.skills.designer.schema.annotations', '枚举、默认值与示例'),
        children: (
          <Space direction="vertical" size={10} style={{width: '100%'}}>
            {allowEnum && (
              <Card size="small" title={t('ai.skills.designer.schema.enumConstraint', '枚举约束')} styles={{body: {padding: 8}}}>
                <Space direction="vertical" size={8} style={{width: '100%'}}>
                  <Switch
                    checked={enumValues.length > 0}
                    checkedChildren={t('common.enabled', '启用')}
                    unCheckedChildren={t('common.disabled', '关闭')}
                    onChange={(enabled) => enabled
                      ? updateEnum([defaultValueForSchema(valueSchema)])
                      : removeKeyword('enum')}
                  />
                  {enumValues.map((item, index) => (
                    <Space key={index} align="start" style={{width: '100%'}}>
                      <div style={{flex: 1, minWidth: 0}}>
                        <SchemaValueEditor
                          schema={valueSchema}
                          value={item}
                          onChange={(next) => updateEnum(enumValues.map((current, currentIndex) => currentIndex === index ? next : current))}
                        />
                      </div>
                      <Button
                        danger
                        icon={<DeleteOutlined />}
                        aria-label={t('ai.skills.designer.schema.deleteEnum', '删除枚举值 {index}', {index: index + 1})}
                        onClick={() => updateEnum(enumValues.filter((_, currentIndex) => currentIndex !== index))}
                      />
                    </Space>
                  ))}
                  {enumValues.length > 0 && (
                    <Button
                      block
                      icon={<PlusOutlined />}
                      disabled={enumValues.length >= 32}
                      onClick={() => updateEnum([...enumValues, defaultValueForSchema(valueSchema)])}
                    >{t('ai.skills.designer.schema.addEnum', '添加枚举值')}</Button>
                  )}
                </Space>
              </Card>
            )}
            <Card size="small" title={t('ai.skills.designer.schema.defaultValue', '默认值')} styles={{body: {padding: 8}}}>
              <Space direction="vertical" size={8} style={{width: '100%'}}>
                <Switch
                  checked={hasDefault}
                  checkedChildren={t('ai.skills.designer.schema.configured', '已设置')}
                  unCheckedChildren={t('ai.skills.designer.schema.notConfigured', '未设置')}
                  onChange={(enabled) => enabled
                    ? onChange({...value, default: defaultValueForSchema(defaultSchema)})
                    : removeKeyword('default')}
                />
                {hasDefault && (
                  <SchemaValueEditor
                    schema={defaultSchema}
                    value={value.default}
                    onChange={(next) => onChange({...value, default: next})}
                  />
                )}
              </Space>
            </Card>
            <Card size="small" title={t('ai.skills.designer.schema.exampleValues', '示例值')} styles={{body: {padding: 8}}}>
              <Space direction="vertical" size={8} style={{width: '100%'}}>
                {examples.map((example, index) => (
                  <Card key={index} size="small" styles={{body: {padding: 8}}}>
                    <Space direction="vertical" size={6} style={{width: '100%'}}>
                      <Space style={{justifyContent: 'space-between', width: '100%'}}>
                        <Text type="secondary">{t('ai.skills.designer.schema.exampleItem', '示例 {index}', {index: index + 1})}</Text>
                        <Button
                          danger
                          type="text"
                          icon={<DeleteOutlined />}
                          aria-label={t('ai.skills.designer.schema.deleteExample', '删除示例 {index}', {index: index + 1})}
                          onClick={() => {
                            const next = examples.filter((_, currentIndex) => currentIndex !== index);
                            if (next.length === 0) removeKeyword('examples');
                            else onChange({...value, examples: next});
                          }}
                        />
                      </Space>
                      <SchemaValueEditor
                        schema={defaultSchema}
                        value={example}
                        onChange={(next) => onChange({
                          ...value,
                          examples: examples.map((current, currentIndex) => currentIndex === index ? next : current),
                        })}
                      />
                    </Space>
                  </Card>
                ))}
                <Button
                  block
                  icon={<PlusOutlined />}
                  disabled={examples.length >= 16}
                  onClick={() => onChange({
                    ...value,
                    examples: [...examples, defaultValueForSchema(defaultSchema)],
                  })}
                >{t('ai.skills.designer.schema.addExample', '添加示例')}</Button>
              </Space>
            </Card>
          </Space>
        ),
      }]}
    />
  );
};

const schemaProperties = (schema?: Record<string, unknown>) => schema?.properties
  && typeof schema.properties === 'object'
  && !Array.isArray(schema.properties)
  ? schema.properties as Record<string, unknown>
  : {};

const schemaPaths = (schema: Record<string, unknown>, prefix = ''): string[] => Object.entries(schemaProperties(schema))
  .flatMap(([name, raw]) => {
    const path = prefix ? `${prefix}.${name}` : name;
    const child = raw && typeof raw === 'object' && !Array.isArray(raw)
      ? raw as Record<string, unknown>
      : {};
    return child.type === 'object' ? schemaPaths(child, path) : [path];
  });

const schemaAtPath = (schema: Record<string, unknown>, path: string) => path
  .split('.')
  .reduce<Record<string, unknown> | undefined>((current, segment) => {
    const next = current ? schemaProperties(current)[segment] : undefined;
    return next && typeof next === 'object' && !Array.isArray(next)
      ? next as Record<string, unknown>
      : undefined;
  }, schema);

const compatibleSchemaType = (source: unknown, target: string) => {
  if (typeof source !== 'string') return true;
  if (source === target) return true;
  return target === 'number' && source === 'integer';
};

const argumentSource = (value: unknown): 'fixed' | 'input' | 'step' => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return 'fixed';
  const reference = (value as Record<string, unknown>).$ref;
  if (typeof reference !== 'string') return 'fixed';
  return reference.startsWith('input.') ? 'input' : reference.startsWith('steps.') ? 'step' : 'fixed';
};

const ToolArgumentsEditor = ({
  document,
  node,
  tool,
  onChange,
}: {
  document: SkillDesignerDocument;
  node: DesignerNode;
  tool?: McpToolDescriptor;
  onChange: (document: SkillDesignerDocument) => void;
}) => {
  const {t} = useI18n();
  const properties = schemaProperties(tool?.inputSchema);
  const required = new Set(Array.isArray(tool?.inputSchema?.required) ? tool.inputSchema.required.map(String) : []);
  const args = node.configuration.arguments && typeof node.configuration.arguments === 'object'
    && !Array.isArray(node.configuration.arguments)
    ? node.configuration.arguments as Record<string, unknown>
    : {};
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
  const upstreamBindings = upstreamNodes.map((candidate) => toolBindingFor(document, candidate));
  const queryScope = useQueryScope();
  const upstreamQueries = useQueries({
    queries: upstreamBindings.map((binding) => ({
      ...scopedQueryOptions(queryScope, ['skill-designer-mcp-capabilities', binding.serverId], ({signal}) => loadMcpServerCapabilities(binding.serverId, signal)),
      enabled: Boolean(binding.serverId),
    })),
  });
  const outputSchemaFor = (nodeId?: string) => {
    const index = upstreamNodes.findIndex((candidate) => candidate.id === nodeId);
    if (index < 0) return undefined;
    const binding = upstreamBindings[index];
    return upstreamQueries[index]?.data?.tools.find((candidate) => candidate.name === binding.name)?.outputSchema;
  };
  const setArgument = (name: string, value: unknown) => onChange(updateNodeArguments(
    document,
    node.id,
    {...args, [name]: value},
  ));

  if (!tool) {
    return <Alert type="info" closable showIcon message={t('ai.skills.designer.mapping.selectToolFirst', '选择 MCP Tool 后将按其 Input Schema 生成参数映射字段')} />;
  }
  if (Object.keys(properties).length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ai.skills.designer.mapping.toolNoInput', '该 Tool 没有输入字段')} />;
  }

  return (
    <Space direction="vertical" size={10} style={{width: '100%'}}>
      {Object.entries(properties).map(([name, raw]) => {
        const definition = raw && typeof raw === 'object' && !Array.isArray(raw)
          ? raw as Record<string, unknown>
          : {};
        const type = String(definition.type ?? 'string');
        const value = args[name];
        const source = argumentSource(value);
        const reference = value && typeof value === 'object' && !Array.isArray(value)
          ? String((value as Record<string, unknown>).$ref ?? '')
          : '';
        const stepMatch = /^steps\.([^.]+)\.(.+)$/.exec(reference);
        return (
          <Card key={name} size="small" styles={{body: {padding: 10}}}>
            <Space direction="vertical" size={7} style={{width: '100%'}}>
              <Space>
                <Text strong>{name}</Text>
                <Tag>{type}</Tag>
                {required.has(name) && <Tag color="red">{t('ai.skills.designer.schemaValue.required', '必填')}</Tag>}
              </Space>
              {typeof definition.description === 'string' && <Text type="secondary">{definition.description}</Text>}
              <Select
                value={source}
                options={[
                  {label: t('ai.skills.designer.mapping.fixedValue', '固定值'), value: 'fixed'},
                  {label: t('ai.skills.designer.mapping.skillInput', 'Skill 输入'), value: 'input'},
                  {label: t('ai.skills.designer.mapping.upstreamOutput', '上游节点输出'), value: 'step'},
                ]}
                onChange={(nextSource) => {
                  if (nextSource === 'fixed') setArgument(name, defaultValueForSchema(definition));
                  if (nextSource === 'input') setArgument(name, {$ref: `input.${inputPaths[0] ?? name}`});
                  if (nextSource === 'step') setArgument(name, {$ref: `steps.${upstreamNodes[0]?.id ?? 'step'}.value`});
                }}
              />
              {source === 'fixed' && (
                <SchemaValueEditor schema={definition} value={value} onChange={(next) => setArgument(name, next)} />
              )}
              {source === 'input' && (
                <Select
                  showSearch
                  value={reference.replace(/^input\./, '')}
                  placeholder={t('ai.skills.designer.reference.selectInputField', '选择 Input 字段')}
                  options={inputPaths.map((path) => {
                    const sourceType = schemaAtPath(document.inputSchema, path)?.type;
                    return {
                      label: `${path} · ${String(sourceType ?? t('ai.skills.designer.common.unknown', '未知'))}`,
                      value: path,
                      disabled: !compatibleSchemaType(sourceType, type),
                    };
                  })}
                  onChange={(path) => setArgument(name, {$ref: `input.${path}`})}
                />
              )}
              {source === 'step' && (
                <>
                  <Space.Compact block>
                    <Select
                      value={stepMatch?.[1]}
                      placeholder={t('ai.skills.designer.mapping.upstreamNode', '上游节点')}
                      style={{width: '45%'}}
                      options={upstreamNodes.map((candidate) => ({label: candidate.id, value: candidate.id}))}
                      onChange={(stepId) => {
                        const outputSchema = outputSchemaFor(stepId);
                        setArgument(name, {$ref: `steps.${stepId}.${outputSchema ? schemaPaths(outputSchema)[0] ?? 'value' : 'value'}`});
                      }}
                    />
                    {(() => {
                      const outputSchema = outputSchemaFor(stepMatch?.[1]);
                      const outputPaths = outputSchema ? schemaPaths(outputSchema) : [];
                      return outputPaths.length > 0 ? (
                        <Select
                          showSearch
                          value={stepMatch?.[2]}
                          placeholder={t('ai.skills.designer.reference.outputField', '输出字段')}
                          style={{width: '55%'}}
                          options={outputPaths.map((path) => {
                            const sourceType = schemaAtPath(outputSchema as Record<string, unknown>, path)?.type;
                            return {
                              label: `${path} · ${String(sourceType ?? t('ai.skills.designer.common.unknown', '未知'))}`,
                              value: path,
                              disabled: !compatibleSchemaType(sourceType, type),
                            };
                          })}
                          onChange={(path) => setArgument(name, {$ref: `steps.${stepMatch?.[1]}.${path}`})}
                        />
                      ) : (
                        <Input
                          value={stepMatch?.[2] ?? ''}
                          placeholder={t('ai.skills.designer.reference.outputFieldPath', '输出字段路径')}
                          onChange={(event) => setArgument(name, {$ref: `steps.${stepMatch?.[1] ?? upstreamNodes[0]?.id ?? 'step'}.${event.target.value}`})}
                        />
                      );
                    })()}
                  </Space.Compact>
                  {stepMatch?.[1] && !outputSchemaFor(stepMatch[1]) && (
                    <Alert type="warning" message={t('ai.skills.designer.mapping.upstreamWeakOutput', '上游 Tool 未声明 outputSchema，当前字段路径为弱类型映射，将在运行时校验。')} />
                  )}
                </>
              )}
            </Space>
          </Card>
        );
      })}
    </Space>
  );
};

const ToolCapabilityEditor = ({
  document,
  node,
  binding,
  update,
  onChange,
}: {
  document: SkillDesignerDocument;
  node: DesignerNode;
  binding: ToolBinding;
  update: (patch: Partial<ToolBinding>) => void;
  onChange: (document: SkillDesignerDocument) => void;
}) => {
  const {t} = useI18n();
  const queryScope = useQueryScope();
  const serversQuery = useQuery(scopedQueryOptions(queryScope, ['skill-designer-mcp-servers'], ({signal}) => loadMcpServers(signal)));
  const capabilitiesQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['skill-designer-mcp-capabilities', binding.serverId], ({signal}) => loadMcpServerCapabilities(binding.serverId, signal)),
    enabled: Boolean(binding.serverId),
  });
  const selectedTool = capabilitiesQuery.data?.tools.find((tool) => tool.name === binding.name);
  const activeSnapshot = capabilitiesQuery.data?.snapshot;
  return (
    <>
      <Form.Item label={t('ai.skills.designer.capability.toolAlias', 'Tool 别名')} required>
        <Input value={binding.alias} onChange={(event) => update({alias: event.target.value})} />
      </Form.Item>
      <Form.Item label={t('ai.skills.designer.capability.server', 'MCP Server')} required>
        <Select
          showSearch
          loading={serversQuery.isLoading}
          value={binding.serverId || undefined}
          optionFilterProp="label"
          placeholder={t('ai.skills.designer.capability.selectReadyServer', '选择已就绪的 MCP Server')}
          options={(serversQuery.data ?? []).map((server) => ({
            value: server.id,
            label: `${server.name ?? server.code ?? server.id} · ${server.code ?? server.id}`,
          }))}
          onChange={(serverId) => update({serverId, snapshotId: '', name: ''})}
        />
      </Form.Item>
      <Form.Item label={t('ai.skills.designer.capability.tool', 'MCP Tool')} required>
        <Select
          showSearch
          loading={capabilitiesQuery.isLoading}
          disabled={!binding.serverId || !activeSnapshot}
          value={binding.name || undefined}
          optionFilterProp="label"
          placeholder={activeSnapshot ? t('ai.skills.designer.capability.selectTool', '选择 Tool') : t('ai.skills.designer.capability.noActiveSnapshot', '该 Server 尚无活动能力快照')}
          options={(capabilitiesQuery.data?.tools ?? []).map((tool) => ({
            value: tool.name,
            label: tool.title ? `${tool.title} · ${tool.name}` : tool.name,
          }))}
          onChange={(name) => update({name, snapshotId: activeSnapshot?.id ?? ''})}
        />
      </Form.Item>
      {binding.snapshotId && (
        <Alert
          type={activeSnapshot?.id === binding.snapshotId ? 'success' : 'warning'}
          showIcon
          message={activeSnapshot?.id === binding.snapshotId
            ? t('ai.skills.designer.capability.pinnedActiveSnapshot', '已固定当前活动能力快照')
            : t('ai.skills.designer.capability.pinnedHistoricalSnapshot', '草稿固定的是历史快照')}
          description={t(
            'ai.skills.designer.capability.snapshotId',
            '能力快照 ID：{id}',
            {id: binding.snapshotId},
          )}
          style={{marginBottom: 16}}
        />
      )}
      <Divider titlePlacement="left">{t('ai.skills.designer.mapping.arguments', '参数映射')}</Divider>
      <ToolArgumentsEditor document={document} node={node} tool={selectedTool} onChange={onChange} />
    </>
  );
};

const PromptCapabilityEditor = ({
  document,
  node,
  binding,
  update,
  onChange,
}: {
  document: SkillDesignerDocument;
  node: DesignerNode;
  binding: PromptBinding;
  update: (patch: Partial<PromptBinding>) => void;
  onChange: (document: SkillDesignerDocument) => void;
}) => {
  const {t} = useI18n();
  const queryScope = useQueryScope();
  const serversQuery = useQuery(scopedQueryOptions(queryScope, ['skill-designer-mcp-servers'], ({signal}) => loadMcpServers(signal)));
  const capabilitiesQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['skill-designer-mcp-capabilities', binding.serverId], ({signal}) => loadMcpServerCapabilities(binding.serverId, signal)),
    enabled: Boolean(binding.serverId),
  });
  const activeSnapshot = capabilitiesQuery.data?.snapshot;
  const selectedPrompt = capabilitiesQuery.data?.prompts.find((prompt) => prompt.name === binding.name);
  const promptSchema: Record<string, unknown> | undefined = selectedPrompt ? {
    type: 'object',
    properties: Object.fromEntries((selectedPrompt.arguments ?? []).map((argument) => [argument.name, {
      type: 'string',
      title: argument.title,
      description: argument.description,
    }])),
    required: (selectedPrompt.arguments ?? []).filter((argument) => argument.required).map((argument) => argument.name),
    additionalProperties: false,
  } : undefined;
  return (
    <>
      <Form.Item label={t('ai.skills.designer.capability.promptAlias', 'Prompt 别名')} required>
        <Input value={binding.alias} onChange={(event) => update({alias: event.target.value})} />
      </Form.Item>
      <Form.Item label={t('ai.skills.designer.capability.server', 'MCP Server')} required>
        <Select
          showSearch
          loading={serversQuery.isLoading}
          value={binding.serverId || undefined}
          optionFilterProp="label"
          placeholder={t('ai.skills.designer.capability.selectReadyServer', '选择已就绪的 MCP Server')}
          options={(serversQuery.data ?? []).map((server) => ({
            value: server.id,
            label: `${server.name ?? server.code ?? server.id} · ${server.code ?? server.id}`,
          }))}
          onChange={(serverId) => update({serverId, snapshotId: '', name: ''})}
        />
      </Form.Item>
      <Form.Item label={t('ai.skills.designer.capability.prompt', 'MCP Prompt')} required>
        <Select
          showSearch
          loading={capabilitiesQuery.isLoading}
          disabled={!binding.serverId || !activeSnapshot}
          value={binding.name || undefined}
          optionFilterProp="label"
          placeholder={activeSnapshot ? t('ai.skills.designer.capability.selectPrompt', '选择 Prompt') : t('ai.skills.designer.capability.noActiveSnapshot', '该 Server 尚无活动能力快照')}
          options={(capabilitiesQuery.data?.prompts ?? []).map((prompt) => ({
            value: prompt.name,
            label: prompt.title ? `${prompt.title} · ${prompt.name}` : prompt.name,
          }))}
          onChange={(name) => update({name, snapshotId: activeSnapshot?.id ?? ''})}
        />
      </Form.Item>
      {binding.snapshotId && (
        <Alert
          type="success"
          showIcon
          message={t('ai.skills.designer.capability.pinnedSnapshot', '已固定能力快照')}
          description={t(
            'ai.skills.designer.capability.snapshotId',
            '能力快照 ID：{id}',
            {id: binding.snapshotId},
          )}
        />
      )}
      <Divider titlePlacement="left">{t('ai.skills.designer.mapping.promptArguments', 'Prompt 参数映射')}</Divider>
      <ToolArgumentsEditor
        document={document}
        node={node}
        tool={selectedPrompt ? {name: selectedPrompt.name, inputSchema: promptSchema} : undefined}
        onChange={onChange}
      />
    </>
  );
};

const ResourceCapabilityEditor = ({
  document,
  node,
  binding,
  update,
  onChange,
}: {
  document: SkillDesignerDocument;
  node: DesignerNode;
  binding: ResourceBinding;
  update: (patch: Partial<ResourceBinding>) => void;
  onChange: (document: SkillDesignerDocument) => void;
}) => {
  const {t} = useI18n();
  const queryScope = useQueryScope();
  const serversQuery = useQuery(scopedQueryOptions(queryScope, ['skill-designer-mcp-servers'], ({signal}) => loadMcpServers(signal)));
  const capabilitiesQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['skill-designer-mcp-capabilities', binding.serverId], ({signal}) => loadMcpServerCapabilities(binding.serverId, signal)),
    enabled: Boolean(binding.serverId),
  });
  const activeSnapshot = capabilitiesQuery.data?.snapshot;
  const selector = binding.uriTemplate ? `template:${binding.uriTemplate}` : binding.uri ? `uri:${binding.uri}` : undefined;
  const templateVariables = binding.uriTemplate
    ? Array.from(binding.uriTemplate.matchAll(/\{([^}]+)}/g), (match) => match[1])
    : [];
  const templateSchema: Record<string, unknown> | undefined = binding.uriTemplate ? {
    type: 'object',
    properties: Object.fromEntries(templateVariables.map((name) => [name, {type: 'string'}])),
    required: templateVariables,
    additionalProperties: false,
  } : undefined;
  return (
    <>
      <Form.Item label={t('ai.skills.designer.capability.resourceAlias', 'Resource 别名')} required>
        <Input value={binding.alias} onChange={(event) => update({alias: event.target.value})} />
      </Form.Item>
      <Form.Item label={t('ai.skills.designer.capability.server', 'MCP Server')} required>
        <Select
          showSearch
          loading={serversQuery.isLoading}
          value={binding.serverId || undefined}
          optionFilterProp="label"
          placeholder={t('ai.skills.designer.capability.selectReadyServer', '选择已就绪的 MCP Server')}
          options={(serversQuery.data ?? []).map((server) => ({
            value: server.id,
            label: `${server.name ?? server.code ?? server.id} · ${server.code ?? server.id}`,
          }))}
          onChange={(serverId) => update({serverId, snapshotId: '', uri: '', uriTemplate: ''})}
        />
      </Form.Item>
      <Form.Item label={t('ai.skills.designer.capability.resourceOrTemplate', 'MCP Resource / Template')} required>
        <Select
          showSearch
          loading={capabilitiesQuery.isLoading}
          disabled={!binding.serverId || !activeSnapshot}
          value={selector}
          optionFilterProp="label"
          placeholder={activeSnapshot ? t('ai.skills.designer.capability.selectResource', '选择 Resource 或 Template') : t('ai.skills.designer.capability.noActiveSnapshot', '该 Server 尚无活动能力快照')}
          options={[
            ...(capabilitiesQuery.data?.resources ?? []).map((resource) => ({
              value: `uri:${resource.uri}`,
              label: t(
                'ai.skills.designer.capability.resourceOption',
                '[Resource] {name} · {uri}',
                {name: resource.title ?? resource.name, uri: resource.uri},
              ),
            })),
            ...(capabilitiesQuery.data?.resourceTemplates ?? []).map((resource) => ({
              value: `template:${resource.uriTemplate}`,
              label: t(
                'ai.skills.designer.capability.templateOption',
                '[Template] {name} · {uri}',
                {name: resource.title ?? resource.name, uri: resource.uriTemplate},
              ),
            })),
          ]}
          onChange={(value: string) => update(value.startsWith('template:')
            ? {uri: '', uriTemplate: value.slice('template:'.length), snapshotId: activeSnapshot?.id ?? ''}
            : {uri: value.slice('uri:'.length), uriTemplate: '', snapshotId: activeSnapshot?.id ?? ''})}
        />
      </Form.Item>
      {binding.snapshotId && (
        <Alert
          type="success"
          showIcon
          message={t('ai.skills.designer.capability.pinnedSnapshot', '已固定能力快照')}
          description={t(
            'ai.skills.designer.capability.snapshotId',
            '能力快照 ID：{id}',
            {id: binding.snapshotId},
          )}
        />
      )}
      {binding.uriTemplate && (
        <>
          <Divider titlePlacement="left">{t('ai.skills.designer.mapping.uriTemplateArguments', 'URI Template 参数映射')}</Divider>
          <ToolArgumentsEditor
            document={document}
            node={node}
            tool={{name: binding.uriTemplate, inputSchema: templateSchema}}
            onChange={onChange}
          />
        </>
      )}
    </>
  );
};

const SchemaFields = ({
  value,
  onChange,
  depth = 0,
}: {
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
  depth?: number;
}) => {
  const {t} = useI18n();
  const fields = readSchemaFields(value);
  const update = (index: number, patch: Partial<SchemaField>) => {
    onChange(writeSchemaFields(
      fields.map((field, current) => current === index ? {...field, ...patch} : field),
      value,
    ));
  };
  return (
    <Space direction="vertical" size={8} style={{width: '100%'}}>
      <SchemaAnnotationsEditor value={value} onChange={onChange} />
      {fields.map((field, index) => (
        <Card key={`${field.name}-${index}`} size="small" styles={{body: {padding: 8}}}>
          <Space direction="vertical" size={6} style={{width: '100%'}}>
            <Space.Compact block>
              <Input value={field.name} placeholder={t('ai.skills.designer.schema.fieldName', '字段名')} onChange={(event) => update(index, {name: event.target.value})} />
              <Button
                danger
                icon={<DeleteOutlined />}
                aria-label={t('ai.skills.designer.schema.deleteField', '删除字段 {name}', {name: field.name || index + 1})}
                onClick={() => onChange(writeSchemaFields(fields.filter((_, current) => current !== index), value))}
              />
            </Space.Compact>
            <Space wrap>
              <Select
                value={field.type}
                style={{width: 130}}
                options={['string', 'number', 'integer', 'boolean', 'object', 'array'].map((type) => ({label: type, value: type}))}
                onChange={(type: SchemaType) => update(index, {
                  type,
                  definition: schemaDefinitionForType(type, field.definition),
                })}
              />
              <Switch checked={field.required} checkedChildren={t('ai.skills.designer.schemaValue.required', '必填')} unCheckedChildren={t('common.optional', '可选')} onChange={(required) => update(index, {required})} />
            </Space>
            <Input
              value={typeof field.definition.title === 'string' ? field.definition.title : ''}
              placeholder={t('ai.skills.designer.schema.fieldTitle', '字段标题（可选）')}
              onChange={(event) => update(index, {definition: {...field.definition, title: event.target.value || undefined}})}
            />
            <Input
              value={typeof field.definition.description === 'string' ? field.definition.description : ''}
              placeholder={t('ai.skills.designer.schema.fieldDescription', '字段说明（可选）')}
              onChange={(event) => update(index, {definition: {...field.definition, description: event.target.value || undefined}})}
            />
            {field.type !== 'object' && (
              <SchemaAnnotationsEditor
                value={field.definition}
                onChange={(definition) => update(index, {definition})}
              />
            )}
            {field.type === 'string' && (
              <Space.Compact block>
                <InputNumber
                  min={0}
                  value={typeof field.definition.minLength === 'number' ? field.definition.minLength : undefined}
                  placeholder={t('ai.skills.designer.schema.minLength', '最小长度')}
                  style={{width: '50%'}}
                  onChange={(minLength) => update(index, {definition: {...field.definition, minLength: minLength ?? undefined}})}
                />
                <InputNumber
                  min={0}
                  value={typeof field.definition.maxLength === 'number' ? field.definition.maxLength : undefined}
                  placeholder={t('ai.skills.designer.schema.maxLength', '最大长度')}
                  style={{width: '50%'}}
                  onChange={(maxLength) => update(index, {definition: {...field.definition, maxLength: maxLength ?? undefined}})}
                />
              </Space.Compact>
            )}
            {(field.type === 'number' || field.type === 'integer') && (
              <Space.Compact block>
                <InputNumber
                  value={typeof field.definition.minimum === 'number' ? field.definition.minimum : undefined}
                  placeholder={t('ai.skills.designer.schema.minimum', '最小值')}
                  precision={field.type === 'integer' ? 0 : undefined}
                  style={{width: '50%'}}
                  onChange={(minimum) => update(index, {definition: {...field.definition, minimum: minimum ?? undefined}})}
                />
                <InputNumber
                  value={typeof field.definition.maximum === 'number' ? field.definition.maximum : undefined}
                  placeholder={t('ai.skills.designer.schema.maximum', '最大值')}
                  precision={field.type === 'integer' ? 0 : undefined}
                  style={{width: '50%'}}
                  onChange={(maximum) => update(index, {definition: {...field.definition, maximum: maximum ?? undefined}})}
                />
              </Space.Compact>
            )}
            {field.type === 'object' && (
              <Card size="small" title={t('ai.skills.designer.schema.objectFields', '对象字段')} styles={{body: {padding: 8}}}>
                {depth >= 8
                  ? <Alert type="warning" message={t('ai.skills.designer.schema.maxDepth', '可视化 Schema 最多编辑 8 层嵌套')} />
                  : (
                    <SchemaFields
                      value={field.definition}
                      depth={depth + 1}
                      onChange={(definition) => update(index, {definition})}
                    />
                  )}
              </Card>
            )}
            {field.type === 'array' && (() => {
              const rawItems = field.definition.items;
              const items = rawItems && typeof rawItems === 'object' && !Array.isArray(rawItems)
                ? rawItems as Record<string, unknown>
                : {type: 'string'};
              const itemCandidate = String(items.type ?? 'string');
              const itemType = ['string', 'number', 'integer', 'boolean', 'object'].includes(itemCandidate)
                ? itemCandidate as Exclude<SchemaType, 'array'>
                : 'string';
              return (
                <Card size="small" title={t('ai.skills.designer.schema.arrayItems', '数组元素')} styles={{body: {padding: 8}}}>
                  <Space direction="vertical" size={8} style={{width: '100%'}}>
                    <Select
                      value={itemType}
                      options={['string', 'number', 'integer', 'boolean', 'object'].map((type) => ({label: type, value: type}))}
                      onChange={(type: Exclude<SchemaType, 'array'>) => update(index, {
                        definition: {...field.definition, items: schemaDefinitionForType(type, items)},
                      })}
                    />
                    <Space.Compact block>
                      <InputNumber
                        min={0}
                        value={typeof field.definition.minItems === 'number' ? field.definition.minItems : undefined}
                        placeholder={t('ai.skills.designer.schema.minItems', '最少元素')}
                        style={{width: '50%'}}
                        onChange={(minItems) => update(index, {definition: {...field.definition, minItems: minItems ?? undefined}})}
                      />
                      <InputNumber
                        min={0}
                        value={typeof field.definition.maxItems === 'number' ? field.definition.maxItems : undefined}
                        placeholder={t('ai.skills.designer.schema.maxItems', '最多元素')}
                        style={{width: '50%'}}
                        onChange={(maxItems) => update(index, {definition: {...field.definition, maxItems: maxItems ?? undefined}})}
                      />
                    </Space.Compact>
                    {itemType !== 'object' && (
                      <SchemaAnnotationsEditor
                        value={items}
                        onChange={(nextItems) => update(index, {
                          definition: {...field.definition, items: nextItems},
                        })}
                      />
                    )}
                    {itemType === 'object' && depth < 8 && (
                      <SchemaFields
                        value={items}
                        depth={depth + 1}
                        onChange={(nextItems) => update(index, {definition: {...field.definition, items: nextItems}})}
                      />
                    )}
                  </Space>
                </Card>
              );
            })()}
          </Space>
        </Card>
      ))}
      <Button
        block
        icon={<PlusOutlined />}
        onClick={() => onChange(writeSchemaFields([
          ...fields,
          {
            name: `field${fields.length + 1}`,
            type: 'string',
            required: false,
            definition: {type: 'string'},
          },
        ], value))}
      >
        {t('ai.skills.designer.schema.addField', '添加字段')}
      </Button>
    </Space>
  );
};

const SkillDesignerInspector = ({document, selectedNode, onChange, onClearSelection, onSelectNode}: Props) => {
  const {t} = useI18n();
  if (!selectedNode) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ai.skills.designer.inspector.selectNode', '选择一个节点配置属性')} style={{marginTop: 80}} />;
  }

  if (selectedNode.type === 'INPUT' || selectedNode.type === 'OUTPUT') {
    const schemaKey = selectedNode.type === 'INPUT' ? 'inputSchema' : 'outputSchema';
    return (
      <div style={{padding: 14}}>
        <Title level={5} style={{marginTop: 0}}>{selectedNode.type === 'INPUT' ? t('ai.skills.designer.schema.input', '输入 Schema') : t('ai.skills.designer.schema.output', '输出 Schema')}</Title>
        <Text type="secondary">{t('ai.skills.designer.schema.formHint', '通过字段表单维护 JSON Schema，无需手写 JSON。')}</Text>
        <Divider />
        <SchemaFields
          value={document[schemaKey]}
          onChange={(schema) => onChange({
            ...document,
            [schemaKey]: schema,
            ...(selectedNode.type === 'OUTPUT'
              ? {workflowOutput: pruneWorkflowOutput(schema, document.workflowOutput)}
              : {}),
          })}
        />
        {selectedNode.type === 'OUTPUT' && (
          <>
            <Divider titlePlacement="left">{t('ai.skills.designer.mapping.output', '输出映射')}</Divider>
            <Text type="secondary">{t('ai.skills.designer.mapping.outputHint', '逐字段选择固定值、Skill Input 或上游节点输出，无需编辑 JSON。')}</Text>
            <div style={{marginTop: 10}}>
              <OutputMappingEditor document={document} onChange={onChange} />
            </div>
          </>
        )}
      </div>
    );
  }

  if (selectedNode.type === 'TOOL') {
    const binding = toolBindingFor(document, selectedNode);
    const update = (patch: Partial<ToolBinding>) => onChange(updateToolBinding(
      document,
      selectedNode.id,
      {...binding, ...patch},
    ));
    return (
      <div style={{padding: 14}}>
        <Title level={5} style={{marginTop: 0}}>{t('ai.skills.designer.node.tool', 'MCP Tool')}</Title>
        <Alert type="info" closable showIcon message={t('ai.skills.designer.capability.toolHint', '绑定已发现能力的固定 Server、Snapshot 和 Tool 名称')} />
        <Form layout="vertical" requiredMark={false} style={{marginTop: 14}}>
          <Form.Item label={t('ai.skills.designer.inspector.nodeId', '节点 ID')}><Input value={selectedNode.id} disabled /></Form.Item>
          <ToolCapabilityEditor
            document={document}
            node={selectedNode}
            binding={binding}
            update={update}
            onChange={onChange}
          />
          <Button
            block
            danger
            icon={<DeleteOutlined />}
            onClick={() => {
              onChange(removeNode(document, selectedNode.id));
              onClearSelection();
            }}
          >
            {t('ai.skills.designer.inspector.deleteNode', '删除节点')}
          </Button>
        </Form>
      </div>
    );
  }

  if (selectedNode.type === 'PROMPT') {
    const binding = promptBindingFor(document, selectedNode);
    const update = (patch: Partial<PromptBinding>) => onChange(updatePromptBinding(
      document,
      selectedNode.id,
      {...binding, ...patch},
    ));
    return (
      <div style={{padding: 14}}>
        <Title level={5} style={{marginTop: 0}}>{t('ai.skills.designer.node.prompt', 'MCP Prompt')}</Title>
        <Alert type="info" closable showIcon message={t('ai.skills.designer.capability.promptHint', '从活动能力快照选择 Prompt 并固定其不可变 Snapshot')} />
        <Form layout="vertical" requiredMark={false} style={{marginTop: 14}}>
          <Form.Item label={t('ai.skills.designer.inspector.nodeId', '节点 ID')}><Input value={selectedNode.id} disabled /></Form.Item>
          <PromptCapabilityEditor
            document={document}
            node={selectedNode}
            binding={binding}
            update={update}
            onChange={onChange}
          />
          <Button block danger icon={<DeleteOutlined />} onClick={() => {
            onChange(removeNode(document, selectedNode.id));
            onClearSelection();
          }}>{t('ai.skills.designer.inspector.deleteNode', '删除节点')}</Button>
        </Form>
      </div>
    );
  }

  if (selectedNode.type === 'RESOURCE') {
    const binding = resourceBindingFor(document, selectedNode);
    const update = (patch: Partial<ResourceBinding>) => onChange(updateResourceBinding(
      document,
      selectedNode.id,
      {...binding, ...patch},
    ));
    return (
      <div style={{padding: 14}}>
        <Title level={5} style={{marginTop: 0}}>{t('ai.skills.designer.node.resource', 'MCP Resource')}</Title>
        <Alert type="info" closable showIcon message={t('ai.skills.designer.capability.resourceHint', '从活动快照选择固定 Resource URI 或 URI Template')} />
        <Form layout="vertical" requiredMark={false} style={{marginTop: 14}}>
          <Form.Item label={t('ai.skills.designer.inspector.nodeId', '节点 ID')}><Input value={selectedNode.id} disabled /></Form.Item>
          <ResourceCapabilityEditor
            document={document}
            node={selectedNode}
            binding={binding}
            update={update}
            onChange={onChange}
          />
          <Button block danger icon={<DeleteOutlined />} onClick={() => {
            onChange(removeNode(document, selectedNode.id));
            onClearSelection();
          }}>{t('ai.skills.designer.inspector.deleteNode', '删除节点')}</Button>
        </Form>
      </div>
    );
  }

  if (selectedNode.type === 'CONDITION') {
    return (
      <div style={{padding: 14}}>
        <Title level={5} style={{marginTop: 0}}>{t('ai.skills.designer.node.condition', '条件')}</Title>
        <Alert type="info" closable showIcon message={t('ai.skills.designer.condition.hint', 'True 与 False 分支均已创建；点击分支内节点继续配置 MCP 能力。')} />
        <Space direction="vertical" size={12} style={{width: '100%', marginTop: 14}}>
          <ConditionExpressionEditor
            document={document}
            node={selectedNode}
            onChange={(condition) => onChange(updateNodeConfiguration(
              document,
              selectedNode.id,
              {condition},
            ))}
          />
          {['then', 'else'].map((branchId) => {
            const children = document.nodes.filter((node) => node.parentNodeId === selectedNode.id && node.branchId === branchId);
            return (
              <Card key={branchId} size="small" title={branchId === 'then' ? t('ai.skills.designer.condition.trueBranch', 'True 分支') : t('ai.skills.designer.condition.falseBranch', 'False 分支')} style={{marginBottom: 10}}>
                <Space wrap>{children.map((child) => (
                  <Button key={child.id} type="link" onClick={() => onSelectNode(child.id)}>{child.id}</Button>
                ))}</Space>
              </Card>
            );
          })}
          <Button block danger icon={<DeleteOutlined />} onClick={() => {
            onChange(removeNode(document, selectedNode.id));
            onClearSelection();
          }}>{t('ai.skills.designer.inspector.deleteNodeAndBranches', '删除节点和分支')}</Button>
        </Space>
      </div>
    );
  }

  if (selectedNode.type === 'PARALLEL') {
    const branches = Array.isArray(selectedNode.configuration.branches)
      ? selectedNode.configuration.branches as Array<Record<string, unknown>>
      : [];
    return (
      <div style={{padding: 14}}>
        <Title level={5} style={{marginTop: 0}}>{t('ai.skills.designer.node.parallel', '并行')}</Title>
        <Alert type="info" closable showIcon message={t('ai.skills.designer.parallel.hint', '各分支并行执行，所有分支完成后自动汇合到后继节点。')} />
        <Space direction="vertical" size={10} style={{width: '100%', marginTop: 14}}>
          {branches.map((branch) => {
            const branchId = String(branch.id ?? '');
            const children = document.nodes.filter((node) => node.parentNodeId === selectedNode.id && node.branchId === branchId);
            return (
              <Card key={branchId} size="small" title={t('ai.skills.designer.parallel.branch', '并行分支')} styles={{body: {padding: 10}}}>
                <Space direction="vertical" size={8} style={{width: '100%'}}>
                  <Input
                    value={branchId}
                    onChange={(event) => onChange(renameParallelBranch(document, selectedNode.id, branchId, event.target.value))}
                  />
                  {children.map((child) => (
                    <Button key={child.id} block onClick={() => onSelectNode(child.id)}>{child.id}</Button>
                  ))}
                  <Button
                    danger
                    disabled={branches.length <= 2}
                    onClick={() => onChange(removeParallelBranch(document, selectedNode.id, branchId))}
                  >{t('ai.skills.designer.parallel.deleteBranch', '删除分支')}</Button>
                </Space>
              </Card>
            );
          })}
          <Button icon={<PlusOutlined />} disabled={branches.length >= 16} onClick={() => onChange(addParallelBranch(document, selectedNode.id))}>
            {t('ai.skills.designer.parallel.addBranch', '添加分支')}
          </Button>
          <Button block danger icon={<DeleteOutlined />} onClick={() => {
            onChange(removeNode(document, selectedNode.id));
            onClearSelection();
          }}>{t('ai.skills.designer.inspector.deleteNodeAndBranches', '删除节点和分支')}</Button>
        </Space>
      </div>
    );
  }

  return <Empty description={t('ai.skills.designer.inspector.notAvailable', '该节点属性将在下一阶段开放')} style={{marginTop: 80}} />;
};

export default SkillDesignerInspector;
