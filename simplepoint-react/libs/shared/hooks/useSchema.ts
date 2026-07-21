import { useEffect, useState } from 'react';
import { TableButtonProps } from "../../components/Table";
import { get, useData } from "../api/methods";
import { RJSFSchema } from "@rjsf/utils";
import { createIcon } from "../types/icon";
import type { UseQueryOptions } from "@tanstack/react-query";
import { getStoredContextId, getStoredRoleId, getStoredTenantId, shouldUseTenantContext } from '../api/contextId';
import { resolveClientI18nFallback } from '../i18n/fallbacks';

export type TableSchemaProps = {
  schema: RJSFSchema;
  buttons: TableButtonProps[];
};

type DictionaryOptionVo = {
  value: string | number | boolean | null;
  label: string;
};

const getGlobalT = () =>
  typeof window !== "undefined" ? (window as any)?.spI18n?.t : undefined;

const resolveI18nStr = (v: unknown): string => {
  if (typeof v !== "string" || !v.startsWith("i18n:")) return String(v);
  const key = v.slice(5);
  const fallback = resolveClientI18nFallback(key);
  return getGlobalT()?.(key, fallback ?? key) ?? fallback ?? key;
};

const normalizeSchemaI18n = (node: any): any => {
  if (!node || typeof node !== "object") return node;

  ["title", "description"].forEach((k) => {
    if (typeof node[k] === "string") node[k] = resolveI18nStr(node[k]);
  });

  if (typeof node.schema === "string" && !node.title)
    node.title = resolveI18nStr(node.schema);

  if (node.properties)
    Object.values(node.properties).forEach(normalizeSchemaI18n);

  if (node.items) normalizeSchemaI18n(node.items);
  return node;
};

const getSchemaScalarType = (type: unknown): string | undefined => {
  if (Array.isArray(type)) {
    return type.find((item): item is string => typeof item === 'string' && item !== 'null');
  }
  return typeof type === 'string' ? type : undefined;
};

const castDictionaryOptionValue = (value: unknown, schemaType: unknown) => {
  const scalarType = getSchemaScalarType(schemaType);
  if (value == null) return value;
  if (scalarType === 'integer') {
    const parsed = Number(value);
    return Number.isNaN(parsed) ? value : Math.trunc(parsed);
  }
  if (scalarType === 'number') {
    const parsed = Number(value);
    return Number.isNaN(parsed) ? value : parsed;
  }
  if (scalarType === 'boolean') {
    if (typeof value === 'boolean') return value;
    if (value === 'true') return true;
    if (value === 'false') return false;
  }
  return typeof value === 'string' ? value : String(value);
};

const applyDictionaryOptions = async (schema: any) => {
  const cache = new Map<string, Promise<DictionaryOptionVo[]>>();

  const walk = async (node: any): Promise<void> => {
    if (!node || typeof node !== 'object') return;

    const xui = node['x-ui'];
    const dictCode = typeof xui?.dictCode === 'string'
      ? xui.dictCode
      : typeof xui?.['dict-code'] === 'string'
        ? xui['dict-code']
        : undefined;

    if (dictCode) {
      let optionsPromise = cache.get(dictCode);
      if (!optionsPromise) {
        optionsPromise = get<DictionaryOptionVo[]>('/common/platform/dictionaries/options', {dictionaryCode: dictCode});
        cache.set(dictCode, optionsPromise);
      }
      const options = await optionsPromise;
      if (Array.isArray(options) && options.length > 0) {
        const isArrayDictionary = getSchemaScalarType(node.type) === 'array';
        const optionTarget = isArrayDictionary
          && node.items
          && !Array.isArray(node.items)
          ? node.items
          : node;
        optionTarget.oneOf = options.map((option) => ({
          const: castDictionaryOptionValue(option.value, optionTarget.type),
          title: resolveI18nStr(option.label),
        }));
        delete optionTarget.enum;
        // RJSF only treats an array as a multi-select when uniqueItems is true.
        // Without it, checkbox widgets receive no enumOptions and render blank.
        if (isArrayDictionary) node.uniqueItems = true;
        node['x-ui'] = {
          ...(xui || {}),
          widget: xui?.widget ?? xui?.['ui:widget'] ?? 'select',
        };
      }
    }

    if (node.properties && typeof node.properties === 'object') {
      await Promise.all(Object.values(node.properties).map(walk));
    }

    if (Array.isArray(node.items)) {
      await Promise.all(node.items.map(walk));
    } else if (node.items) {
      await walk(node.items);
    }
  };

  await walk(schema);
  return schema;
};

// Only sort by x-order; keep original relative order for ties or when x-order is undefined.
const sortByOrder = <T extends Record<string, any>>(items: T[]) =>
  [...items].sort((a, b) => {
    const o1 = typeof a["x-order"] === "number" ? a["x-order"] : Infinity;
    const o2 = typeof b["x-order"] === "number" ? b["x-order"] : Infinity;
    if (o1 < o2) return -1;
    if (o1 > o2) return 1;
    return 0; // stable sort preserves original order
  });

/**
 * Return a schema whose properties follow their x-order metadata.
 *
 * This is intentionally applied both after fetching a schema and after a page-level
 * form transform. Page transforms can inject fields (for example OAuth token settings),
 * and those fields must participate in the same ordering contract as backend fields.
 */
export const sortSchemaProperties = (schema: any): any => {
  if (Array.isArray(schema)) {
    return sortByOrder(schema).map(sortSchemaProperties);
  }
  if (!schema || typeof schema !== 'object') {
    return schema;
  }

  const result = {...schema};
  if (schema.properties && typeof schema.properties === 'object') {
    const entries = Object.entries(schema.properties) as Array<[string, Record<string, any>]>;
    entries.sort(([, left], [, right]) => {
      const leftOrder = typeof left?.['x-order'] === 'number' ? left['x-order'] : Infinity;
      const rightOrder = typeof right?.['x-order'] === 'number' ? right['x-order'] : Infinity;
      return leftOrder - rightOrder;
    });
    result.properties = Object.fromEntries(
      entries.map(([key, value]) => [key, sortSchemaProperties(value)])
    );
  }
  if (schema.items) {
    result.items = sortSchemaProperties(schema.items);
  }
  return result;
};

const normalizeButtonI18n = (btn: TableButtonProps): TableButtonProps => ({
  ...btn,
  title: resolveI18nStr(btn.title),
  text: resolveI18nStr(btn.text ?? btn.title),
  icon: typeof btn.icon === "string" ? createIcon(btn.icon) : btn.icon,
});

export function useSchema(
  baseUrl: string,
  options?: Omit<UseQueryOptions<TableSchemaProps, Error, TableSchemaProps, readonly unknown[]>, 'queryKey' | 'queryFn'>) {
  const useTenantContext = shouldUseTenantContext(baseUrl);
  const [tenantId, setTenantId] = useState(() => useTenantContext ? (getStoredTenantId() ?? "") : "");
  const [roleId, setRoleId] = useState(() => useTenantContext ? (getStoredRoleId(getStoredTenantId()) ?? "") : "");
  const [contextId, setContextId] = useState(() => useTenantContext ? (getStoredContextId(getStoredTenantId(), getStoredRoleId(getStoredTenantId())) ?? "") : "");

  useEffect(() => {
    if (!useTenantContext) {
      setTenantId("");
      setRoleId("");
      setContextId("");
      return;
    }

    const handleTenantChange = (event: Event) => {
      const nextTenantId = (event as CustomEvent<string | undefined>).detail ?? getStoredTenantId() ?? "";
      const nextRoleId = getStoredRoleId(nextTenantId) ?? "";
      setTenantId(nextTenantId);
      setRoleId(nextRoleId);
      setContextId(getStoredContextId(nextTenantId, nextRoleId) ?? "");
    };

    const handleRoleChange = (event: Event) => {
      const detail = (event as CustomEvent<{ tenantId?: string; roleId?: string }>).detail;
      const nextTenantId = detail?.tenantId ?? tenantId;
      if ((nextTenantId ?? "") !== tenantId) {
        return;
      }
      const nextRoleId = detail?.roleId ?? getStoredRoleId(nextTenantId) ?? "";
      setRoleId(nextRoleId);
      setContextId(getStoredContextId(nextTenantId, nextRoleId) ?? "");
    };

    const handleContextChange = (event: Event) => {
      const detail = (event as CustomEvent<{ tenantId?: string; roleId?: string; contextId?: string }>).detail;
      if (detail && typeof detail === "object") {
        if ((detail.tenantId ?? "") !== tenantId) {
          return;
        }
        if ((detail.roleId ?? "") !== roleId) {
          return;
        }
        setContextId(detail.contextId ?? "");
        return;
      }
      setContextId(getStoredContextId(tenantId, roleId) ?? "");
    };

    window.addEventListener("sp-set-tenant", handleTenantChange as EventListener);
    window.addEventListener("sp-set-role", handleRoleChange as EventListener);
    window.addEventListener("sp-set-context-id", handleContextChange as EventListener);

    return () => {
      window.removeEventListener("sp-set-tenant", handleTenantChange as EventListener);
      window.removeEventListener("sp-set-role", handleRoleChange as EventListener);
      window.removeEventListener("sp-set-context-id", handleContextChange as EventListener);
    };
  }, [tenantId, roleId, useTenantContext]);

  return useData([`${baseUrl}/schema`, useTenantContext ? tenantId : "", useTenantContext ? roleId : "", useTenantContext ? contextId : ""], async () => {
    const res = await get<TableSchemaProps>(`${baseUrl}/schema`);
    if (!res) return res;

    const schema = sortSchemaProperties(res.schema);

    const buttons = sortByOrder(res.buttons ?? []).map(normalizeButtonI18n);
    const normalizedSchema = normalizeSchemaI18n(schema);
    const enhancedSchema = Array.isArray(normalizedSchema)
      ? normalizedSchema
      : await applyDictionaryOptions(normalizedSchema);

    return {
      ...res,
      schema: enhancedSchema,
      buttons,
    };
  }, options);
}
