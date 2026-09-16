import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Button, Card, Input, Select, Space, Switch, Tabs, Typography} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';
import {
  buildJsonObjectSchema,
  hasInvalidSchemaProperties,
  parseJsonObjectSchema,
  schemaProperties,
  type SchemaProperty,
} from './jsonSchemaObject';

const {Text} = Typography;

const JsonSchemaObjectEditor = ({
  value,
  onChange,
  onValidityChange,
}: {
  value?: string;
  onChange?: (value: string) => void;
  onValidityChange?: (valid: boolean) => void;
}) => {
  const {t} = useI18n();
  const parsed = useMemo(() => parseJsonObjectSchema(value), [value]);
  const baseSchema = useRef(parsed);
  const lastEmittedValue = useRef<string | undefined>(undefined);
  const [mode, setMode] = useState('visual');
  const [properties, setProperties] = useState<SchemaProperty[]>(() =>
    schemaProperties(parsed));
  const [additionalProperties, setAdditionalProperties] = useState(() =>
    parsed?.additionalProperties !== false);

  useEffect(() => {
    onValidityChange?.(Boolean(parsed)
      && value !== '__SIMPLEPOINT_INVALID_SCHEMA_FIELDS__');
  }, [onValidityChange, parsed, value]);

  useEffect(() => {
    if (mode !== 'visual' || !parsed) return;
    baseSchema.current = parsed;
    if (value === lastEmittedValue.current) {
      lastEmittedValue.current = undefined;
      return;
    }
    setProperties(schemaProperties(parsed));
    setAdditionalProperties(parsed.additionalProperties !== false);
  }, [mode, parsed, value]);

  const emitValue = (next: string) => {
    lastEmittedValue.current = next;
    onChange?.(next);
  };

  const updateProperties = (next: SchemaProperty[]) => {
    setProperties(next);
    emitValue(hasInvalidSchemaProperties(next)
      ? '__SIMPLEPOINT_INVALID_SCHEMA_FIELDS__'
      : buildJsonObjectSchema(baseSchema.current, next, additionalProperties));
  };
  const names = properties.map((property) => property.name.trim()).filter(Boolean);
  const duplicateNames = new Set(names.filter((name, index) =>
    names.indexOf(name) !== index));

  return (
    <Tabs
      activeKey={mode}
      onChange={setMode}
      items={[
        {
          key: 'visual',
          label: t('ai.schemaEditor.visual', '可视化字段'),
          children: (
            <Space direction="vertical" size={12} style={{width: '100%'}}>
              {!parsed && value !== '__SIMPLEPOINT_INVALID_SCHEMA_FIELDS__' && (
                <Alert
                  showIcon
                  type="error"
                  message={t(
                    'ai.schemaEditor.invalidJson',
                    '当前 Schema 不是有效的 JSON 对象，请切换到 JSON 模式修正。',
                  )}
                />
              )}
              {properties.map((property, index) => (
                <Card key={property.id} size="small">
                  <Space direction="vertical" size={8} style={{width: '100%'}}>
                    <Space wrap style={{width: '100%'}}>
                      <Input
                        aria-label={t('ai.schemaEditor.fieldName', '字段名')}
                        status={!property.name.trim()
                          || duplicateNames.has(property.name.trim()) ? 'error' : undefined}
                        value={property.name}
                        placeholder={t('ai.schemaEditor.fieldName', '字段名')}
                        style={{width: 180}}
                        onChange={(event) => updateProperties(properties.map(
                          (item, current) => current === index
                            ? {...item, name: event.target.value} : item,
                        ))}
                      />
                      <Select
                        aria-label={t('ai.schemaEditor.fieldType', '字段类型')}
                        value={property.type}
                        style={{width: 140}}
                        options={['string', 'integer', 'number', 'boolean', 'object', 'array']
                          .map((type) => ({value: type, label: type}))}
                        onChange={(type) => updateProperties(properties.map(
                          (item, current) => current === index
                            ? {...item, type} : item,
                        ))}
                      />
                      <Space>
                        <Switch
                          checked={property.required}
                          onChange={(required) => updateProperties(properties.map(
                            (item, current) => current === index
                              ? {...item, required} : item,
                          ))}
                        />
                        <Text>{t('ai.schemaEditor.required', '必填')}</Text>
                      </Space>
                      <Button
                        danger
                        onClick={() => updateProperties(properties.filter(
                          (_, current) => current !== index,
                        ))}
                      >
                        {t('ai.schemaEditor.remove', '移除')}
                      </Button>
                    </Space>
                    <Input
                      value={property.description}
                      placeholder={t('ai.schemaEditor.description', '字段说明')}
                      onChange={(event) => updateProperties(properties.map(
                        (item, current) => current === index
                          ? {...item, description: event.target.value} : item,
                      ))}
                    />
                    {duplicateNames.has(property.name.trim()) && (
                      <Text type="danger">
                        {t('ai.schemaEditor.duplicateName', '字段名不能重复')}
                      </Text>
                    )}
                  </Space>
                </Card>
              ))}
              <Space wrap>
                <Button
                  type="dashed"
                  onClick={() => updateProperties([...properties, {
                    id: globalThis.crypto?.randomUUID?.() ?? `${Date.now()}`,
                    name: '',
                    type: 'string',
                    description: '',
                    required: false,
                    source: {type: 'string'},
                  }])}
                >
                  {t('ai.schemaEditor.add', '添加字段')}
                </Button>
                <Space>
                  <Switch
                    checked={additionalProperties}
                    onChange={(checked) => {
                      setAdditionalProperties(checked);
                      emitValue(buildJsonObjectSchema(baseSchema.current, properties, checked));
                    }}
                  />
                  <Text>{t('ai.schemaEditor.additionalProperties', '允许未声明字段')}</Text>
                </Space>
              </Space>
            </Space>
          ),
        },
        {
          key: 'json',
          label: t('ai.schemaEditor.json', '高级 JSON'),
          children: (
            <Input.TextArea
              value={value}
              rows={14}
              style={{fontFamily: 'monospace'}}
              status={value?.trim() && !parsed ? 'error' : undefined}
              onChange={(event) => onChange?.(event.target.value)}
            />
          ),
        },
      ]}
    />
  );
};

export default JsonSchemaObjectEditor;
