import api from '@/api';
import {contextPath} from '@/services';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, message} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {resolveErrorMessage} from '../shared';

const baseConfig = api['platform.dna-federation-views'];
const schemaBaseUrl = `${contextPath}/platform/dna/federation/schemas`;

type FederationSchemaOption = {
  id: string;
  code?: string;
  name?: string;
  catalogName?: string;
  enabled?: boolean;
};

const resolveSchemaLabel = (schema: FederationSchemaOption) => {
  const primary = schema.name || schema.code || schema.id;
  const secondary = schema.code && schema.code !== primary ? ` (${schema.code})` : '';
  const catalog = schema.catalogName ? ` / ${schema.catalogName}` : '';
  const disabled = schema.enabled === false ? ' - 已禁用' : '';
  return `${primary}${secondary}${catalog}${disabled}`;
};

const App = () => {
  const [schemas, setSchemas] = useState<FederationSchemaOption[]>([]);
  const [schemasLoaded, setSchemasLoaded] = useState(false);

  const loadSchemas = useCallback(async () => {
    const page = await get<Page<FederationSchemaOption>>(schemaBaseUrl, {page: 0, size: 200});
    setSchemas(page.content ?? []);
    setSchemasLoaded(true);
  }, []);

  useEffect(() => {
    void loadSchemas().catch((error) => {
      setSchemasLoaded(true);
      message.error(resolveErrorMessage(error, '逻辑 Schema 列表加载失败'));
    });
  }, [loadSchemas]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.schemaId) {
      properties.schemaId.title = '逻辑 Schema';
      properties.schemaId.oneOf = schemas.map((item) => ({
        const: item.id,
        title: resolveSchemaLabel(item),
      }));
      properties.schemaId.description = schemas.length > 0 ? '请选择已配置的逻辑 Schema' : '请先在逻辑 Schema 页面新增对象';
    }
    delete properties.schemaCode;
    delete properties.schemaName;
    return nextSchema;
  }, [schemas]);

  const columnOverrides = useMemo(() => ({
    schemaId: {
      title: '逻辑 Schema',
      width: 240,
      render: (value: string, record: {schemaName?: string; schemaCode?: string}) =>
        record.schemaName || record.schemaCode || value || '-',
    },
  }), []);

  return (
    <div>
      {schemasLoaded && schemas.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{marginBottom: 16}}
          message="当前还没有逻辑 Schema"
          description="请先到逻辑 Schema 页面新增对象，再回来配置逻辑视图。"
        />
      ) : null}
      <SimpleTable
        {...baseConfig}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
      />
    </div>
  );
};

export default App;
