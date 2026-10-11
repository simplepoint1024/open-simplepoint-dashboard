import type {TranslateFn} from './translator';

export interface PermissionMetadataField {title: string; type?: string; values?: string[]}
export interface PermissionMetadataPage {title: string; models: Array<{model: string; properties: Record<string, PermissionMetadataField>}>}
export interface PermissionPresentation {
  pages: Record<string, PermissionMetadataPage>;
  dataRules: Record<string, string>; fieldRules: Record<string, string>; organizations: Record<string, string>;
  userName?: string; tenantName?: string; roleName?: string;
}
const commonFields = new Set(['id', 'name', 'code', 'description', 'enabled', 'sort', 'type', 'createdAt', 'updatedAt',
  'createdBy', 'updatedBy', 'createOrgDeptId', 'tenantId', 'orgId', 'orgName', 'userId', 'revision', 'parentId', 'email', 'deletedAt']);
export const scopeTypes = ['ALL', 'SELF', 'DEPT', 'DEPT_AND_BELOW', 'CUSTOM', 'EXPRESSION', 'DENY'];
export const accessTypes = ['EDITABLE', 'VISIBLE', 'MASKED', 'HIDDEN'];
export const contextNames = ['user.id', 'tenant.id', 'user.organizationId', 'user.organizationIds'];
const safeText = (value?: string) => !!value && !value.startsWith('i18n:') && !/^[\w.$#/-]+$/.test(value);

export function permissionLabels(t: TranslateFn, data?: PermissionPresentation) {
  // Build an index only for a requested page/global snapshot, not once per displayed field row.
  const modelIndexes = new Map<string, Map<string, PermissionMetadataPage['models'][number]>>();
  const unknown = () => t('permissions.unavailable', '名称暂不可用');
  const translated = (key: string) => {const value = t(key, ''); return value && value !== key && !value.startsWith('i18n:') ? value : '';};
  const title = (value?: string, fallback = unknown()) => value?.startsWith('i18n:')
    ? translated(value.slice(5)) || fallback : value || fallback;
  const page = (code: string) => {
    const raw = data?.pages[code]?.title;
    return raw && raw !== code ? title(raw) : unknown();
  };
  const field = (name: string, metadata?: PermissionMetadataField, pageCode?: string) => {
    const raw = metadata?.title;
    if (raw?.startsWith('i18n:')) {const label = translated(raw.slice(5)); if (label) return label;}
    const namespace = pageCode?.replace(/\.view$/, '');
    if (namespace) {const label = translated(`${namespace}.title.${name}`); if (label) return label;}
    if (commonFields.has(name)) return t(`permissions.fields.${name}`, unknown());
    return raw && raw !== name && safeText(raw) ? raw : t('permissions.unnamedField', '未命名字段');
  };
  const fieldKey = (key: string, pageCode?: string) => {
    const hash = key.lastIndexOf('#');
    const model = key.slice(0, hash), name = key.slice(hash + 1);
    const indexKey = pageCode && data?.pages[pageCode] ? pageCode : '';
    let index = modelIndexes.get(indexKey);
    if (!index) {
      index = new Map(); const pages = indexKey ? [data!.pages[indexKey]] : Object.values(data?.pages ?? {});
      for (const page of pages) for (const item of page.models) {
        for (const alias of [item.model, item.model.split('.').pop()!]) if (!index.has(alias)) index.set(alias, item);
      }
      modelIndexes.set(indexKey, index);
    }
    const found = index.get(model);
    return field(name, found?.properties[name], pageCode);
  };
  return {title, page, field, fieldKey,
    scope: (value: string) => scopeTypes.includes(value) ? t(`permissions.scope.${value}`, unknown()) : unknown(),
    access: (value: string) => accessTypes.includes(value) ? t(`permissions.access.${value}`, unknown()) : unknown(),
    context: (value: string) => contextNames.includes(value) ? t(`permissions.context.${value}`, unknown()) : unknown(),
    model: (model: string, index: number) => translated(`permissions.models.${model.split('.').pop()}`)
      || t('permissions.fieldGroup', '字段组 {number}', {number: index + 1}),
    organizations: (ids?: string[]) => ids?.map(id => title(data?.organizations[id])).join('、') || '—',
    rule: (id: string | null | undefined, kind: 'data' | 'field') => id
      ? title((kind === 'data' ? data?.dataRules : data?.fieldRules)?.[id], t('permissions.configuredRule', '已配置规则')) : '—',
    option: (value: string, namespace: string, index: number) => translated(`${namespace}.${value}`)
      || translated(`${namespace.replace('.title.', '.')}.${value}`)
      || translated(`${namespace.replace('.title.', '.')}.${value.toLowerCase()}`)
      || translated(`permissions.values.${value}`) || t('permissions.unnamedOption', '未命名选项 {number}', {number: index + 1}),
  };
}

/** Translate identifiers only, never text inside JSON string literals. Brackets make labels unambiguous. */
export function expressionLabels(fields: Record<string, string>, contexts: Record<string, string>, words: Record<string, string>) {
  const unique = (items: Record<string, string>) => {
    const seen = new Map<string, number>();
    return Object.fromEntries(Object.entries(items).sort(([a], [b]) => a.localeCompare(b)).map(([key, label]) => {
      const count = (seen.get(label) ?? 0) + 1; seen.set(label, count);
      return [key, count === 1 ? label : `${label} (${count})`];
    }));
  };
  const names = unique(fields), variables = unique(contexts);
  const encode = (source: string) => source.replace(/"(?:\\.|[^"\\])*"|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|\$[A-Za-z][\w.]*|[A-Za-z_][\w]*/g, token => {
    if (token.startsWith('"') || /^-?[0-9]/.test(token)) return token;
    if (token.startsWith('$')) return `@【${variables[token.slice(1)] ?? words.UNKNOWN}】`;
    if (names[token]) return `【${names[token]}】`;
    return words[token.toUpperCase()] ?? `【${words.UNKNOWN}】`;
  });
  const decode = (source: string) => {
    const reverse = new Map<string, string>([
      ...Object.entries(names).map(([key, label]) => [`【${label}】`, key] as [string, string]),
      ...Object.entries(variables).map(([key, label]) => [`@【${label}】`, `$${key}`] as [string, string]),
      ...Object.entries(words).filter(([key]) => key !== 'UNKNOWN').map(([key, label]) => [label, key === 'TRUE' || key === 'FALSE' ? key.toLowerCase() : key] as [string, string]),
    ]);
    const labels = [...reverse.keys()].sort((a, b) => b.length - a.length);
    let result = '';
    for (let index = 0; index < source.length;) {
      if (source[index] === '"') {
        const quoted = source.slice(index).match(/^"(?:\\.|[^"\\])*"/);
        if (!quoted) throw Error('Incomplete string');
        result += quoted[0]; index += quoted[0].length; continue;
      }
      const label = labels.find(label => source.startsWith(label, index)
        && (!/\w$/.test(label) || !/\w/.test(source[index + label.length] ?? '')));
      if (label) {const decoded = reverse.get(label)!; result += /^[A-Z]+$/.test(decoded) ? ` ${decoded} ` : decoded; index += label.length; continue;}
      const scalar = source.slice(index).match(/^(?:\s+|[()[\],=<>!&|]+|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/);
      if (!scalar) throw Error('Unknown expression label');
      result += scalar[0]; index += scalar[0].length;
    }
    return result;
  };
  return {encode, decode, fields: names, contexts: variables};
}

export function expressionWords(t: TranslateFn) {
  return Object.fromEntries(['AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'CONTAINS', 'TRUE', 'FALSE', 'UNKNOWN']
    .map(word => [word, t(`permissions.expression.${word}`, '…')]));
}
