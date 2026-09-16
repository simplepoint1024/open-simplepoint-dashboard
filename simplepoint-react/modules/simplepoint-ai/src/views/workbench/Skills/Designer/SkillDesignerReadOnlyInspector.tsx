import {Card, Descriptions, Empty, Space, Tag, Typography} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {promptBindingFor, resourceBindingFor, toolBindingFor} from './document';
import {skillNodeTypeLabel} from './labels';
import type {DesignerNode, SkillDesignerDocument} from './types';

const {Text, Title} = Typography;

const propertiesOf = (schema: Record<string, unknown>) => schema.properties
  && typeof schema.properties === 'object'
  && !Array.isArray(schema.properties)
  ? schema.properties as Record<string, unknown>
  : {};

const SchemaSummary = ({schema, depth = 0}: {schema: Record<string, unknown>; depth?: number}) => {
  const {t} = useI18n();
  const properties = propertiesOf(schema);
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  if (Object.keys(properties).length === 0) {
    return <Text type="secondary">{t('ai.skills.designer.schema.noFields', '无字段')}</Text>;
  }
  return (
    <Space direction="vertical" size={6} style={{width: '100%'}}>
      {Object.entries(properties).map(([name, raw]) => {
        const definition = raw && typeof raw === 'object' && !Array.isArray(raw)
          ? raw as Record<string, unknown>
          : {};
        const type = String(definition.type ?? 'string');
        return (
          <Card key={name} size="small" styles={{body: {padding: 8}}}>
            <Space wrap>
              <Text strong>{name}</Text>
              <Tag>{type}</Tag>
              {required.has(name) && <Tag color="red">{t('ai.skills.designer.schemaValue.required', '必填')}</Tag>}
              {Array.isArray(definition.enum) && <Tag color="blue">{t('ai.skills.designer.schema.enumCount', '{count} 个枚举值', {count: definition.enum.length})}</Tag>}
              {Object.prototype.hasOwnProperty.call(definition, 'default') && <Tag color="green">{t('ai.skills.designer.schema.hasDefault', '有默认值')}</Tag>}
              {Array.isArray(definition.examples) && <Tag color="purple">{t('ai.skills.designer.schema.exampleCount', '{count} 个示例', {count: definition.examples.length})}</Tag>}
            </Space>
            {typeof definition.description === 'string' && (
              <Text type="secondary" style={{display: 'block', marginTop: 4}}>{definition.description}</Text>
            )}
            {type === 'object' && depth < 8 && (
              <div style={{marginTop: 8}}><SchemaSummary schema={definition} depth={depth + 1} /></div>
            )}
            {type === 'array' && Boolean(definition.items) && typeof definition.items === 'object' && !Array.isArray(definition.items) && (
              <Text type="secondary" style={{display: 'block', marginTop: 4}}>
                {t('ai.skills.designer.schema.itemType', '元素类型：{type}', {type: String((definition.items as Record<string, unknown>).type ?? 'string')})}
              </Text>
            )}
          </Card>
        );
      })}
    </Space>
  );
};

const ReferenceLabel = ({value}: {value: unknown}) => {
  const {t} = useI18n();
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const reference = (value as Record<string, unknown>).$ref;
    if (typeof reference === 'string') return <>{t('ai.skills.designer.reference.path', '引用 {path}', {path: reference})}</>;
    return <>{t('ai.skills.designer.reference.fixedObject', '固定对象')}</>;
  }
  if (Array.isArray(value)) return <>{t('ai.skills.designer.reference.fixedArray', '固定数组（{count} 项）', {count: value.length})}</>;
  return <>{t('ai.skills.designer.reference.fixedValue', '固定值 · {value}', {value: String(value ?? 'null')})}</>;
};

const ArgumentsSummary = ({configuration}: {configuration: Record<string, unknown>}) => {
  const {t} = useI18n();
  const args = configuration.arguments && typeof configuration.arguments === 'object'
    && !Array.isArray(configuration.arguments)
    ? configuration.arguments as Record<string, unknown>
    : {};
  if (Object.keys(args).length === 0) return <Text type="secondary">{t('ai.skills.designer.mapping.noArguments', '无参数映射')}</Text>;
  return (
    <Descriptions bordered size="small" column={1}>
      {Object.entries(args).map(([name, value]) => (
        <Descriptions.Item key={name} label={name}><ReferenceLabel value={value} /></Descriptions.Item>
      ))}
    </Descriptions>
  );
};

const SkillDesignerReadOnlyInspector = ({
  document,
  selectedNode,
}: {
  document: SkillDesignerDocument;
  selectedNode?: DesignerNode;
}) => {
  const {t} = useI18n();
  if (!selectedNode) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ai.skills.designer.readOnly.selectNode', '选择节点查看只读属性')} style={{marginTop: 80}} />;
  }
  const location = selectedNode.parentNodeId
    ? t('ai.skills.designer.readOnly.branchLocation', '{branch} · #{order}', {
      branch: selectedNode.branchId ?? 'branch',
      order: selectedNode.order + 1,
    })
    : selectedNode.type === 'INPUT' || selectedNode.type === 'OUTPUT'
      ? t('ai.skills.designer.readOnly.fixedBoundary', '固定边界节点')
      : t('ai.skills.designer.readOnly.topLevel', '顶层 · #{order}', {order: selectedNode.order + 1});
  const capability = selectedNode.type === 'TOOL'
    ? toolBindingFor(document, selectedNode)
    : selectedNode.type === 'PROMPT'
      ? promptBindingFor(document, selectedNode)
      : selectedNode.type === 'RESOURCE'
        ? resourceBindingFor(document, selectedNode)
        : undefined;
  const schema = selectedNode.type === 'INPUT'
    ? document.inputSchema
    : selectedNode.type === 'OUTPUT'
      ? document.outputSchema
      : undefined;
  return (
    <div style={{padding: 14}}>
      <Title level={5} style={{marginTop: 0}}>{t('ai.skills.designer.readOnly.properties', '只读节点属性')}</Title>
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label={t('ai.skills.designer.inspector.nodeId', '节点 ID')}><Text code>{selectedNode.id}</Text></Descriptions.Item>
        <Descriptions.Item label={t('common.type', '类型')}>
          <Tag>{skillNodeTypeLabel(t, selectedNode.type)}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('common.location', '位置')}>{location}</Descriptions.Item>
      </Descriptions>
      {schema && (
        <Card size="small" title={selectedNode.type === 'INPUT' ? t('ai.skills.designer.schema.input', '输入 Schema') : t('ai.skills.designer.schema.output', '输出 Schema')} style={{marginTop: 12}}>
          <SchemaSummary schema={schema} />
        </Card>
      )}
      {capability && (
        <Card size="small" title={t('ai.skills.designer.readOnly.pinnedCapability', '固定 MCP 能力')} style={{marginTop: 12}}>
          <Descriptions size="small" column={1}>
            <Descriptions.Item label={t('common.alias', '别名')}>{capability.alias}</Descriptions.Item>
            <Descriptions.Item label={t('ai.skills.designer.capability.server', 'MCP Server')}>{capability.serverId}</Descriptions.Item>
            <Descriptions.Item label={t('ai.skills.designer.capability.snapshot', '能力快照')}>{capability.snapshotId}</Descriptions.Item>
            <Descriptions.Item label={t('ai.skills.designer.readOnly.capability', '能力')}>{'name' in capability ? capability.name : capability.uriTemplate || capability.uri}</Descriptions.Item>
          </Descriptions>
          <ArgumentsSummary configuration={selectedNode.configuration} />
        </Card>
      )}
      {selectedNode.type === 'CONDITION' && (
        <Card size="small" title={t('ai.skills.designer.condition.branches', '条件分支')} style={{marginTop: 12}}>
          <Text>{t('ai.skills.designer.readOnly.conditionDescription', 'True / False 分支由结构化条件表达式控制，选择分支节点可查看其固定能力。')}</Text>
        </Card>
      )}
      {selectedNode.type === 'PARALLEL' && (
        <Card size="small" title={t('ai.skills.designer.parallel.branches', '并行分支')} style={{marginTop: 12}}>
          <Space wrap>
            {(Array.isArray(selectedNode.configuration.branches)
              ? selectedNode.configuration.branches as Array<Record<string, unknown>>
              : []).map((branch) => <Tag key={String(branch.id)}>{String(branch.id)}</Tag>)}
          </Space>
        </Card>
      )}
    </div>
  );
};

export default SkillDesignerReadOnlyInspector;
