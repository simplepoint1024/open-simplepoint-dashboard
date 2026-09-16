import {DeleteOutlined, PlusOutlined} from '@ant-design/icons';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Button, Card, Input, InputNumber, Select, Space, Switch, Tag, Typography} from 'antd';

const {Text} = Typography;

type SchemaNode = Record<string, unknown>;

const schemaType = (schema: SchemaNode) => String(schema.type ?? 'string');

const childSchema = (value: unknown): SchemaNode => value && typeof value === 'object' && !Array.isArray(value)
  ? value as SchemaNode
  : {type: 'string'};

const propertiesOf = (schema: SchemaNode) => schema.properties
  && typeof schema.properties === 'object'
  && !Array.isArray(schema.properties)
  ? schema.properties as Record<string, unknown>
  : {};

export const defaultValueForSchema = (schema: SchemaNode): unknown => {
  if (schema.const !== undefined) return schema.const;
  if (schema.default !== undefined) return schema.default;
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return schema.enum[0];
  const type = schemaType(schema);
  if (type === 'boolean') return false;
  if (type === 'number' || type === 'integer') {
    if (typeof schema.minimum === 'number') return schema.minimum;
    if (typeof schema.maximum === 'number' && schema.maximum < 0) return schema.maximum;
    return 0;
  }
  if (type === 'array') {
    const count = typeof schema.minItems === 'number' ? Math.max(0, schema.minItems) : 0;
    const items = childSchema(schema.items);
    return Array.from({length: count}, () => defaultValueForSchema(items));
  }
  if (type === 'object') {
    const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
    return Object.fromEntries(Object.entries(propertiesOf(schema))
      .filter(([name]) => required.has(name))
      .map(([name, raw]) => [name, defaultValueForSchema(childSchema(raw))]));
  }
  const minimumLength = typeof schema.minLength === 'number' ? Math.max(0, schema.minLength) : 0;
  return 'a'.repeat(minimumLength);
};

const SchemaValueEditor = ({
  schema,
  value,
  onChange,
  depth = 0,
}: {
  schema: SchemaNode;
  value: unknown;
  onChange: (value: unknown) => void;
  depth?: number;
}) => {
  const {t} = useI18n();
  const type = schemaType(schema);
  if (depth >= 8) {
    return (
      <Alert
        type="warning"
        message={t('ai.skills.designer.schemaValue.depthLimit', '固定值最多可视化编辑 8 层，请改用 JSON、Input 或上游输出映射')}
      />
    );
  }
  if (schema.const !== undefined) {
    return (
      <Input.TextArea
        value={typeof schema.const === 'string' ? schema.const : JSON.stringify(schema.const, null, 2)}
        autoSize={{minRows: 1, maxRows: 6}}
        disabled
      />
    );
  }
  if (Array.isArray(schema.enum)) {
    return (
      <Select
        value={value}
        style={{width: '100%'}}
        options={schema.enum.map((item) => ({label: String(item), value: item}))}
        onChange={onChange}
      />
    );
  }
  if (type === 'boolean') {
    return <Switch checked={Boolean(value)} onChange={onChange} />;
  }
  if (type === 'number' || type === 'integer') {
    return (
      <InputNumber
        value={typeof value === 'number' ? value : undefined}
        precision={type === 'integer' ? 0 : undefined}
        min={typeof schema.minimum === 'number' ? schema.minimum : undefined}
        max={typeof schema.maximum === 'number' ? schema.maximum : undefined}
        style={{width: '100%'}}
        onChange={(next) => onChange(next ?? defaultValueForSchema(schema))}
      />
    );
  }
  if (type === 'array') {
    const values = Array.isArray(value) ? value : [];
    const items = childSchema(schema.items);
    const maximum = typeof schema.maxItems === 'number' ? schema.maxItems : undefined;
    return (
      <Space direction="vertical" size={8} style={{width: '100%'}}>
        {values.map((item, index) => (
          <Card key={index} size="small" styles={{body: {padding: 8}}}>
            <Space direction="vertical" size={6} style={{width: '100%'}}>
              <Space style={{justifyContent: 'space-between', width: '100%'}}>
                <Text type="secondary">
                  {t('ai.skills.designer.schemaValue.item', '元素 {index}', {index: index + 1})}
                </Text>
                <Button
                  danger
                  type="text"
                  icon={<DeleteOutlined />}
                  disabled={typeof schema.minItems === 'number' && values.length <= schema.minItems}
                  onClick={() => onChange(values.filter((_, current) => current !== index))}
                />
              </Space>
              <SchemaValueEditor
                schema={items}
                value={item}
                depth={depth + 1}
                onChange={(next) => onChange(values.map((current, currentIndex) => currentIndex === index ? next : current))}
              />
            </Space>
          </Card>
        ))}
        <Button
          block
          icon={<PlusOutlined />}
          disabled={maximum !== undefined && values.length >= maximum}
          onClick={() => onChange([...values, defaultValueForSchema(items)])}
        >
          {t('ai.skills.designer.schemaValue.addItem', '添加数组元素')}
        </Button>
      </Space>
    );
  }
  if (type === 'object') {
    const object = value && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown>
      : {};
    const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
    const properties = propertiesOf(schema);
    if (Object.keys(properties).length === 0) {
      return (
        <Alert
          type="warning"
          message={t('ai.skills.designer.schemaValue.emptyObject', '该 object 没有字段定义，请改用 JSON、Input 或上游输出映射')}
        />
      );
    }
    return (
      <Space direction="vertical" size={8} style={{width: '100%'}}>
        {Object.entries(properties).map(([name, raw]) => {
          const propertySchema = childSchema(raw);
          const configured = Object.prototype.hasOwnProperty.call(object, name);
          return (
            <Card key={name} size="small" styles={{body: {padding: 8}}}>
              <Space direction="vertical" size={6} style={{width: '100%'}}>
                <Space wrap>
                  <Text strong>{name}</Text>
                  <Tag>{schemaType(propertySchema)}</Tag>
                  {required.has(name) && (
                    <Tag color="red">{t('ai.skills.designer.schemaValue.required', '必填')}</Tag>
                  )}
                  {!required.has(name) && configured && (
                    <Button
                      size="small"
                      type="link"
                      danger
                      onClick={() => {
                        const next = {...object};
                        delete next[name];
                        onChange(next);
                      }}
                    >{t('ai.skills.designer.schemaValue.clear', '清除')}</Button>
                  )}
                </Space>
                <SchemaValueEditor
                  schema={propertySchema}
                  value={configured ? object[name] : defaultValueForSchema(propertySchema)}
                  depth={depth + 1}
                  onChange={(next) => onChange({...object, [name]: next})}
                />
              </Space>
            </Card>
          );
        })}
      </Space>
    );
  }
  return (
    <Input
      value={typeof value === 'string' ? value : ''}
      minLength={typeof schema.minLength === 'number' ? schema.minLength : undefined}
      maxLength={typeof schema.maxLength === 'number' ? schema.maxLength : undefined}
      onChange={(event) => onChange(event.target.value)}
    />
  );
};

export default SchemaValueEditor;
