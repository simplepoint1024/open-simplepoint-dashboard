import type {WorkflowVariable} from '../WorkflowDesigner/contracts';
import {reservedVariable} from '../WorkflowDesigner/contracts.ts';
import {tenantRoleAuthoritySelectable} from '../TenantDirectorySelect/query.ts';

/** Produces the same bounded scalar/directory form for starters, tasks and variable defaults. */
export function variableSchema(variable: WorkflowVariable): Record<string, unknown> {
  const base = {title: variable.label};
  switch (variable.type) {
    case 'number': return {...base, type: 'number'};
    case 'boolean': return {...base, type: 'boolean'};
    case 'date': return {...base, type: 'string', format: 'date'};
    case 'date-time': return {...base, type: 'string', format: 'date-time'};
    case 'user': case 'role': return {...base, type: 'string', maxLength: 100,
      'x-ui': {widget: 'TenantDirectory', options: {kind: variable.type === 'user' ? 'users' : 'roles'}}};
    case 'users': case 'roles': return {...base, type: 'array', maxItems: 50, uniqueItems: true,
      items: {type: 'string', maxLength: 100},
      'x-ui': {widget: 'TenantDirectory', options: {kind: variable.type, multiple: true}}};
    default: return {...base, type: 'string', maxLength: 4000};
  }
}

export function runtimeSchema(variables: readonly WorkflowVariable[], stage: 'start' | 'task') {
  const visible = variables.filter(variable => variable.scope === stage || variable.scope === 'both');
  return {type: 'object' as const, additionalProperties: false,
    required: visible.filter(variable => variable.required).map(variable => variable.name),
    properties: Object.fromEntries(visible.map(variable => [variable.name, variableSchema(variable)]))};
}

/** A JSON editor must never evaluate input or accept arrays/null as a variable object. */
export function parseVariables(text: string): Record<string, unknown> {
  if (text.length > 128 * 1024 || new TextEncoder().encode(text).length > 128 * 1024) {
    throw Error('流程变量超过数量或大小限制');
  }
  return validateVariablesValue(JSON.parse(text || '{}'));
}

/** Ordinary controls and registered providers must obey the same pre-serialization value limits. */
export function validateVariablesValue(parsed: unknown): Record<string, unknown> {
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw Error('流程变量必须是 JSON 对象');
  if (Object.keys(parsed).length > 100) {
    throw Error('流程变量超过数量或大小限制');
  }
  for (const name of Object.keys(parsed)) {
    if (!/^[A-Za-z_][A-Za-z0-9_.-]{0,79}$/.test(name) || reservedVariable(name)) throw Error(`变量标识无效或被保留: ${name}`);
  }
  const check = (value: unknown, depth: number) => {
    if (typeof value === 'number' && !Number.isFinite(value)) throw Error('流程变量数值必须是有限数值');
    if (value === null || typeof value !== 'object') return;
    if (depth >= 8) throw Error('流程变量嵌套不能超过 8 层');
    if (Array.isArray(value)) {
      if (value.length > 500) throw Error('流程变量数组不能超过 500 项');
      value.forEach(item => check(item, depth + 1));
    } else {
      if (Object.keys(value).length > 200 || Object.keys(value).some(name => name.length > 100)) throw Error('流程变量对象超过字段数量或长度限制');
      Object.values(value).forEach(item => check(item, depth + 1));
    }
  };
  Object.values(parsed).forEach(value => check(value, 0));
  return parsed as Record<string, unknown>;
}

export function variablesIssue(variables: readonly WorkflowVariable[], value: Record<string, unknown>, stage: 'start' | 'task') {
  if (!variables.length) return undefined;
  const visible = variables.filter(variable => variable.scope === stage || variable.scope === 'both');
  for (const key of Object.keys(value)) if (!visible.some(variable => variable.name === key)) return `当前阶段不允许填写变量: ${key}`;
  for (const variable of visible) {
    const item = value[variable.name];
    if (variable.required && (item == null || typeof item === 'string' && !item.trim()
      || Array.isArray(item) && item.length === 0)) return `请填写: ${variable.label}`;
    if (item == null) continue;
    const valid = variable.type === 'number' ? typeof item === 'number' && Number.isFinite(item)
      : variable.type === 'boolean' ? typeof item === 'boolean'
        : ['users', 'roles'].includes(variable.type) ? Array.isArray(item) && item.length <= 50
          && item.every(entry => typeof entry === 'string' && (variable.type === 'roles' ? roleValue(entry) : userValue(entry)))
          && new Set(item).size === item.length
          : variable.type === 'role' ? typeof item === 'string' && roleValue(item)
            : variable.type === 'user' ? typeof item === 'string' && userValue(item)
              : typeof item === 'string' && item.length <= (variable.type === 'string' ? 4000 : 100);
    if (!valid) return `类型或长度不正确: ${variable.label}`;
  }
  return undefined;
}

const userValue = (value: string) => /^[A-Za-z0-9_][A-Za-z0-9_.:@-]{0,99}$/.test(value);
const roleValue = tenantRoleAuthoritySelectable;
