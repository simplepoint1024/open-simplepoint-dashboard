import {
  CodeOutlined,
  CopyOutlined,
  DeleteOutlined,
  ExperimentOutlined,
  FormOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueries} from '@tanstack/react-query';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {
  App,
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  List,
  Progress,
  Radio,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {loadMcpCapabilitySnapshot} from './api';
import {toolBindingFor} from './document';
import {
  skillDebugExecutionStatusLabel,
  skillMockTestRunStatusLabel,
  skillNodeTypeLabel,
} from './labels';
import SchemaValueEditor, {defaultValueForSchema} from './SchemaValueEditor';
import type {
  DesignerNode,
  SkillDesignerDocument,
  SkillDesignerNodeMock,
  SkillDesignerTestAssertion,
  SkillDesignerTestCase,
  SkillMockTestRun,
} from './types';

const {Text, Title} = Typography;

type MockDraft = {
  nodeId: string;
  mode: 'SUCCESS' | 'ERROR';
  outputJson: string;
  errorCode: string;
  errorMessage: string;
};

type TestCaseDraft = {
  id: string;
  name: string;
  enabled: boolean;
  inputJson: string;
  mocks: MockDraft[];
  assertions: AssertionDraft[];
};

type AssertionDraft = {
  id: string;
  sourceNodeId: string;
  fieldPath: string;
  operator: SkillDesignerTestAssertion['operator'];
  expectedJson: string;
};

const MCP_NODE_TYPES = new Set<DesignerNode['type']>(['TOOL', 'PROMPT', 'RESOURCE']);
const TEST_CASE_ID = /^[A-Za-z][A-Za-z0-9._-]{0,63}$/;

const runCaseStatusColor = (status: SkillMockTestRun['cases'][number]['status']) => ({
  WAITING_APPROVAL: 'gold',
  PENDING: 'processing',
  RUNNING: 'blue',
  PAUSED: 'orange',
  SUCCEEDED: 'success',
  FAILED: 'error',
  REJECTED: 'error',
  CANCELLED: 'default',
}[status]);
const VISUAL_SCHEMA_KEYWORDS = new Set([
  '$id', '$schema', 'additionalProperties', 'const', 'default', 'description',
  'enum', 'examples', 'items', 'maximum', 'maxItems', 'maxLength', 'minimum',
  'minItems', 'minLength', 'properties', 'required', 'title', 'type',
]);
const VISUAL_SCHEMA_TYPES = new Set(['array', 'boolean', 'integer', 'number', 'object', 'string']);

const executableNodes = (document: SkillDesignerDocument) => document.nodes
  .filter((node) => MCP_NODE_TYPES.has(node.type))
  .sort((left, right) => left.order - right.order || left.id.localeCompare(right.id));

const capabilityLabel = (node: DesignerNode) => {
  const key = node.type === 'TOOL' ? 'tool' : node.type === 'PROMPT' ? 'prompt' : 'resource';
  const alias = String(node.configuration[key] ?? '').trim();
  return alias && alias !== node.id ? `${node.id} · ${alias}` : node.id;
};

const prettyJson = (value: unknown) => JSON.stringify(value ?? {}, null, 2);

const visualSchemaSupported = (
  value: unknown,
  depth = 0,
): value is Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth > 8) return false;
  const schema = value as Record<string, unknown>;
  if (Object.keys(schema).some((key) => !VISUAL_SCHEMA_KEYWORDS.has(key))) return false;
  const type = typeof schema.type === 'string' ? schema.type : undefined;
  if (!type || !VISUAL_SCHEMA_TYPES.has(type)) return false;
  if (type === 'object') {
    if (!schema.properties || typeof schema.properties !== 'object' || Array.isArray(schema.properties)) return false;
    const properties = Object.values(schema.properties as Record<string, unknown>);
    return properties.length > 0 && properties.every((property) => visualSchemaSupported(property, depth + 1));
  }
  if (type === 'array') return visualSchemaSupported(schema.items, depth + 1);
  return true;
};

const jsonEquals = (left: unknown, right: unknown): boolean => {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right)
      && left.length === right.length
      && left.every((item, index) => jsonEquals(item, right[index]));
  }
  if (left && right && typeof left === 'object' && typeof right === 'object') {
    const leftObject = left as Record<string, unknown>;
    const rightObject = right as Record<string, unknown>;
    const leftKeys = Object.keys(leftObject);
    return leftKeys.length === Object.keys(rightObject).length
      && leftKeys.every((key) => Object.prototype.hasOwnProperty.call(rightObject, key)
        && jsonEquals(leftObject[key], rightObject[key]));
  }
  return false;
};

type SchemaValidationIssue = {
  code: 'additional' | 'const' | 'enum' | 'maximum' | 'maxItems' | 'maxLength'
    | 'minimum' | 'minItems' | 'minLength' | 'required' | 'type';
  path: string;
  field?: string;
  type?: string;
};

const SCHEMA_ISSUE_FALLBACKS: Record<SchemaValidationIssue['code'], string> = {
  additional: '{path} 包含未声明字段 {field}',
  const: '{path} 与固定值不一致',
  enum: '{path} 不是允许的枚举值',
  maximum: '{path} 超过最大值',
  maxItems: '{path} 数组元素过多',
  maxLength: '{path} 长度超过上限',
  minimum: '{path} 低于最小值',
  minItems: '{path} 数组元素不足',
  minLength: '{path} 长度不足',
  required: '{path} 缺少必填字段 {field}',
  type: '{path} 必须为 {type}',
};

const schemaValidationError = (
  schema: Record<string, unknown>,
  value: unknown,
  path = '$',
): SchemaValidationIssue | undefined => {
  if (schema.const !== undefined && !jsonEquals(schema.const, value)) return {code: 'const', path};
  if (Array.isArray(schema.enum) && !schema.enum.some((item) => jsonEquals(item, value))) {
    return {code: 'enum', path};
  }
  const type = String(schema.type ?? '');
  const typeMatches = type === 'object'
    ? Boolean(value && typeof value === 'object' && !Array.isArray(value))
    : type === 'array'
      ? Array.isArray(value)
      : type === 'string'
        ? typeof value === 'string'
        : type === 'boolean'
          ? typeof value === 'boolean'
          : type === 'number'
            ? typeof value === 'number' && Number.isFinite(value)
            : type === 'integer'
              ? typeof value === 'number' && Number.isInteger(value)
              : true;
  if (!typeMatches) return {code: 'type', path, type};
  if (type === 'object') {
    const object = value as Record<string, unknown>;
    const properties = schema.properties as Record<string, Record<string, unknown>>;
    for (const required of Array.isArray(schema.required) ? schema.required.map(String) : []) {
      if (!Object.prototype.hasOwnProperty.call(object, required)) {
        return {code: 'required', path, field: required};
      }
    }
    for (const [key, nested] of Object.entries(object)) {
      if (!properties[key]) {
        if (schema.additionalProperties === false) return {code: 'additional', path, field: key};
        continue;
      }
      const error = schemaValidationError(properties[key], nested, `${path}.${key}`);
      if (error) return error;
    }
  }
  if (type === 'array') {
    const array = value as unknown[];
    if (typeof schema.minItems === 'number' && array.length < schema.minItems) return {code: 'minItems', path};
    if (typeof schema.maxItems === 'number' && array.length > schema.maxItems) return {code: 'maxItems', path};
    for (let index = 0; index < array.length; index += 1) {
      const error = schemaValidationError(schema.items as Record<string, unknown>, array[index], `${path}[${index}]`);
      if (error) return error;
    }
  }
  if (type === 'string') {
    const text = value as string;
    if (typeof schema.minLength === 'number' && text.length < schema.minLength) return {code: 'minLength', path};
    if (typeof schema.maxLength === 'number' && text.length > schema.maxLength) return {code: 'maxLength', path};
  }
  if (type === 'number' || type === 'integer') {
    const number = value as number;
    if (typeof schema.minimum === 'number' && number < schema.minimum) return {code: 'minimum', path};
    if (typeof schema.maximum === 'number' && number > schema.maximum) return {code: 'maximum', path};
  }
  return undefined;
};

const createDraft = (
  testCase: SkillDesignerTestCase,
  nodes: DesignerNode[],
): TestCaseDraft => {
  const byNodeId = new Map(testCase.mocks.map((mock) => [mock.nodeId, mock]));
  return {
    id: testCase.id,
    name: testCase.name,
    enabled: testCase.enabled,
    inputJson: prettyJson(testCase.input),
    mocks: nodes.map((node) => {
      const mock = byNodeId.get(node.id);
      return {
        nodeId: node.id,
        mode: mock?.mode ?? 'SUCCESS',
        outputJson: prettyJson(mock?.output),
        errorCode: mock?.errorCode ?? '',
        errorMessage: mock?.errorMessage ?? '',
      };
    }),
    assertions: (testCase.assertions ?? []).map((assertion) => ({
      id: assertion.id,
      sourceNodeId: assertion.sourceNodeId,
      fieldPath: assertion.fieldPath,
      operator: assertion.operator,
      expectedJson: JSON.stringify(assertion.expected ?? null, null, 2),
    })),
  };
};

const nextTestCaseId = (tests: SkillDesignerTestCase[]) => {
  let index = 1;
  while (tests.some((test) => test.id === `case-${index}`)) index += 1;
  return `case-${index}`;
};

const parseJsonObject = (source: string) => {
  const value: unknown = JSON.parse(source);
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('object');
  }
  return value as Record<string, unknown>;
};

const SkillDesignerTestCases = ({
  open,
  document,
  running,
  runPolling,
  testRun,
  testRunHistory,
  testRunHistoryLoading,
  testRunHistoryError,
  onClose,
  onChange,
  onRun,
  onRunAll,
  onOpenTestRun,
  onOpenExecution,
}: {
  open: boolean;
  document: SkillDesignerDocument;
  running: boolean;
  runPolling: boolean;
  testRun?: SkillMockTestRun;
  testRunHistory: SkillMockTestRun[];
  testRunHistoryLoading: boolean;
  testRunHistoryError: boolean;
  onClose: () => void;
  onChange: (document: SkillDesignerDocument) => void;
  onRun: (document: SkillDesignerDocument, testCaseId: string) => Promise<void>;
  onRunAll: (document: SkillDesignerDocument) => Promise<void>;
  onOpenTestRun: (testRunId: string) => Promise<void>;
  onOpenExecution: (executionId: string) => Promise<void>;
}) => {
  const {t} = useI18n();
  const {message, modal} = App.useApp();
  const nodes = useMemo(() => executableNodes(document), [document]);
  const toolBindings = useMemo(() => nodes.map((node) => (
    node.type === 'TOOL' ? toolBindingFor(document, node) : undefined
  )), [document, nodes]);
  const snapshotBindings = useMemo(() => {
    const unique = new Map<string, NonNullable<(typeof toolBindings)[number]>>();
    toolBindings.forEach((binding) => {
      if (binding?.serverId && binding.snapshotId) {
        unique.set(`${binding.serverId}:${binding.snapshotId}`, binding);
      }
    });
    return [...unique.values()];
  }, [toolBindings]);
  const queryScope = useQueryScope();
  const snapshotQueries = useQueries({
    queries: snapshotBindings.map((binding) => ({
      ...scopedQueryOptions(queryScope, ['skill-designer-mcp-snapshot', binding.serverId, binding.snapshotId], ({signal}) => loadMcpCapabilitySnapshot(binding.serverId, binding.snapshotId, signal)),
      staleTime: Infinity,
    })),
  });
  const [selectedId, setSelectedId] = useState<string>();
  const [draft, setDraft] = useState<TestCaseDraft>();
  const [dirty, setDirty] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [mockEditorModes, setMockEditorModes] = useState<Record<string, 'SCHEMA' | 'JSON'>>({});

  const snapshotQueryFor = useCallback((nodeId: string) => {
    const index = nodes.findIndex((node) => node.id === nodeId);
    if (index < 0) return undefined;
    const binding = toolBindings[index];
    if (!binding) return undefined;
    const queryIndex = snapshotBindings.findIndex((candidate) => (
      candidate.serverId === binding.serverId
      && candidate.snapshotId === binding.snapshotId
    ));
    return queryIndex < 0 ? undefined : snapshotQueries[queryIndex];
  }, [nodes, snapshotBindings, snapshotQueries, toolBindings]);

  const outputSchemaFor = useCallback((nodeId: string) => {
    const index = nodes.findIndex((node) => node.id === nodeId);
    if (index < 0) return undefined;
    const binding = toolBindings[index];
    if (!binding) return undefined;
    return snapshotQueryFor(nodeId)?.data?.tools.find((tool) => tool.name === binding.name)?.outputSchema;
  }, [nodes, snapshotQueryFor, toolBindings]);

  const selectImmediately = useCallback((testCase?: SkillDesignerTestCase) => {
    setSelectedId(testCase?.id);
    setDraft(testCase ? createDraft(testCase, nodes) : undefined);
    setErrors([]);
    setMockEditorModes({});
    setDirty(false);
  }, [nodes]);

  useEffect(() => {
    if (!open || dirty) return;
    const selected = document.tests.find((test) => test.id === selectedId)
      ?? document.tests[0];
    selectImmediately(selected);
  }, [dirty, document.tests, open, selectImmediately, selectedId]);

  const requestSelection = useCallback((testCase: SkillDesignerTestCase) => {
    if (!dirty) {
      selectImmediately(testCase);
      return;
    }
    modal.confirm({
      title: t('ai.skills.designer.tests.unsaved.title', '放弃当前测试用例的未保存修改？'),
      content: t('ai.skills.designer.tests.unsaved.description', '输入和 Mock 内容会保留在当前页面，只有确认放弃后才会切换。'),
      okText: t('ai.skills.designer.tests.unsaved.discard', '放弃并切换'),
      cancelText: t('ai.skills.designer.tests.unsaved.keep', '继续编辑'),
      onOk: () => selectImmediately(testCase),
    });
  }, [dirty, modal, selectImmediately, t]);

  const requestClose = useCallback(() => {
    if (!dirty) {
      onClose();
      return;
    }
    modal.confirm({
      title: t('ai.skills.designer.tests.unsaved.title', '放弃当前测试用例的未保存修改？'),
      content: t('ai.skills.designer.tests.unsaved.closeDescription', '关闭后，本次尚未保存的输入和 Mock 修改会丢失。'),
      okText: t('ai.skills.designer.tests.unsaved.discard', '放弃修改'),
      cancelText: t('ai.skills.designer.tests.unsaved.keep', '继续编辑'),
      onOk: () => {
        setDirty(false);
        onClose();
      },
    });
  }, [dirty, modal, onClose, t]);

  const createCase = useCallback(() => {
    if (document.tests.length >= 64) {
      message.warning(t('ai.skills.designer.tests.limit', '最多可创建 64 个测试用例'));
      return;
    }
    const id = nextTestCaseId(document.tests);
    const testCase: SkillDesignerTestCase = {
      id,
      name: t('ai.skills.designer.tests.defaultName', '测试用例 {index}', {index: document.tests.length + 1}),
      enabled: true,
      input: {},
      mocks: nodes.map((node): SkillDesignerNodeMock => ({
        nodeId: node.id,
        mode: 'SUCCESS',
        output: {},
        errorCode: null,
        errorMessage: null,
      })),
      assertions: [],
    };
    onChange({...document, tests: [...document.tests, testCase]});
    selectImmediately(testCase);
  }, [document, message, nodes, onChange, selectImmediately, t]);

  const duplicateCase = useCallback(() => {
    if (!draft) return;
    if (document.tests.length >= 64) {
      message.warning(t('ai.skills.designer.tests.limit', '最多可创建 64 个测试用例'));
      return;
    }
    const source = document.tests.find((test) => test.id === draft.id);
    if (!source) return;
    const id = nextTestCaseId(document.tests);
    const copy: SkillDesignerTestCase = {
      ...source,
      id,
      name: t('ai.skills.designer.tests.copyName', '{name}（副本）', {name: source.name}),
      input: structuredClone(source.input),
      mocks: structuredClone(source.mocks),
      assertions: structuredClone(source.assertions),
    };
    onChange({...document, tests: [...document.tests, copy]});
    selectImmediately(copy);
  }, [document, draft, message, onChange, selectImmediately, t]);

  const deleteCase = useCallback(() => {
    if (!draft) return;
    modal.confirm({
      title: t('ai.skills.designer.tests.delete.title', '删除测试用例“{name}”？', {name: draft.name}),
      content: t('ai.skills.designer.tests.delete.description', '删除后仍可通过 Draft Revision 历史恢复。'),
      okText: t('ai.skills.designer.tests.delete.confirm', '删除'),
      okButtonProps: {danger: true},
      onOk: () => {
        const tests = document.tests.filter((test) => test.id !== draft.id);
        onChange({...document, tests});
        selectImmediately(tests[0]);
      },
    });
  }, [document, draft, modal, onChange, selectImmediately, t]);

  const buildTestCase = useCallback((): SkillDesignerTestCase | undefined => {
    if (!draft) return undefined;
    const nextErrors: string[] = [];
    if (!TEST_CASE_ID.test(draft.id)) {
      nextErrors.push(t('ai.skills.designer.tests.error.id', '测试用例 ID 格式无效'));
    }
    if (!draft.name.trim()) {
      nextErrors.push(t('ai.skills.designer.tests.error.name', '请输入测试用例名称'));
    }
    let input: Record<string, unknown> = {};
    try {
      input = parseJsonObject(draft.inputJson);
    } catch {
      nextErrors.push(t('ai.skills.designer.tests.error.inputJson', '测试输入必须是有效的 JSON 对象'));
    }
    const mocks = draft.mocks.map((mock): SkillDesignerNodeMock => {
      if (mock.mode === 'ERROR') {
        if (!mock.errorCode.trim()) {
          nextErrors.push(t(
            'ai.skills.designer.tests.error.errorCode',
            '节点 {nodeId} 的失败 Mock 必须填写错误码',
            {nodeId: mock.nodeId},
          ));
        }
        return {
          nodeId: mock.nodeId,
          mode: 'ERROR',
          output: null,
          errorCode: mock.errorCode.trim(),
          errorMessage: mock.errorMessage.trim() || null,
        };
      }
      let output: unknown = {};
      try {
        output = JSON.parse(mock.outputJson);
        const outputSchema = outputSchemaFor(mock.nodeId);
        if (outputSchema && visualSchemaSupported(outputSchema)) {
          const schemaError = schemaValidationError(outputSchema, output);
          if (schemaError) {
            const reason = t(
              `ai.skills.designer.tests.error.outputSchema.${schemaError.code}`,
              SCHEMA_ISSUE_FALLBACKS[schemaError.code],
              schemaError,
            );
            nextErrors.push(t(
              'ai.skills.designer.tests.error.outputSchema',
              '节点 {nodeId} 的成功结果不符合固定输出 Schema：{reason}',
              {nodeId: mock.nodeId, reason},
            ));
          }
        }
      } catch {
        nextErrors.push(t(
          'ai.skills.designer.tests.error.outputJson',
          '节点 {nodeId} 的成功结果不是有效 JSON',
          {nodeId: mock.nodeId},
        ));
      }
      return {
        nodeId: mock.nodeId,
        mode: 'SUCCESS',
        output,
        errorCode: null,
        errorMessage: null,
      };
    });
    const assertionIds = new Set<string>();
    const knownSources = new Set(['__output', ...nodes.map((node) => node.id)]);
    const assertions = draft.assertions.map((assertion): SkillDesignerTestAssertion => {
      if (!TEST_CASE_ID.test(assertion.id)) {
        nextErrors.push(t(
          'ai.skills.designer.tests.error.assertionId',
          '断言 ID“{id}”格式无效',
          {id: assertion.id || '-'},
        ));
      } else if (assertionIds.has(assertion.id)) {
        nextErrors.push(t(
          'ai.skills.designer.tests.error.assertionDuplicate',
          '断言 ID“{id}”重复',
          {id: assertion.id},
        ));
      }
      assertionIds.add(assertion.id);
      if (!knownSources.has(assertion.sourceNodeId)) {
        nextErrors.push(t(
          'ai.skills.designer.tests.error.assertionSource',
          '断言 {id} 引用了不存在的节点',
          {id: assertion.id},
        ));
      }
      let expected: unknown = null;
      if (assertion.operator !== 'EXISTS' && assertion.operator !== 'NOT_EXISTS') {
        try {
          expected = JSON.parse(assertion.expectedJson);
          if (assertion.operator === 'MATCHES' && typeof expected !== 'string') {
            nextErrors.push(t(
              'ai.skills.designer.tests.error.assertionPattern',
              '断言 {id} 的正则表达式必须是 JSON 字符串',
              {id: assertion.id},
            ));
          }
        } catch {
          nextErrors.push(t(
            'ai.skills.designer.tests.error.assertionExpected',
            '断言 {id} 的期望值不是有效 JSON',
            {id: assertion.id},
          ));
        }
      }
      return {
        id: assertion.id,
        sourceNodeId: assertion.sourceNodeId,
        fieldPath: assertion.fieldPath.trim(),
        operator: assertion.operator,
        expected,
      };
    });
    setErrors(nextErrors);
    if (nextErrors.length > 0) return undefined;
    return {
      id: draft.id,
      name: draft.name.trim(),
      enabled: draft.enabled,
      input,
      mocks,
      assertions,
    };
  }, [draft, nodes, outputSchemaFor, t]);

  const saveCase = useCallback(() => {
    const testCase = buildTestCase();
    if (!testCase) return undefined;
    const tests = document.tests.map((item) => item.id === testCase.id ? testCase : item);
    const nextDocument = {...document, tests};
    onChange(nextDocument);
    setDraft(createDraft(testCase, nodes));
    setDirty(false);
    message.success(t('ai.skills.designer.tests.saved', '测试用例已保存到 Draft'));
    return nextDocument;
  }, [buildTestCase, document, message, nodes, onChange, t]);

  const runCase = useCallback(async () => {
    const testCase = buildTestCase();
    if (!testCase) return;
    if (!testCase.enabled) {
      setErrors([t('ai.skills.designer.tests.error.disabled', '启用测试用例后才能运行 MOCK 调试')]);
      return;
    }
    if (nodes.length === 0) {
      setErrors([t('ai.skills.designer.tests.error.noNodes', '请先为工作流添加 MCP Tool、Prompt 或 Resource 节点')]);
      return;
    }
    const tests = document.tests.map((item) => item.id === testCase.id ? testCase : item);
    const nextDocument = {...document, tests};
    onChange(nextDocument);
    setDraft(createDraft(testCase, nodes));
    setDirty(false);
    await onRun(nextDocument, testCase.id);
  }, [buildTestCase, document, nodes, onChange, onRun, t]);

  const runAllCases = useCallback(async () => {
    let nextDocument = document;
    if (draft) {
      const testCase = buildTestCase();
      if (!testCase) return;
      const tests = document.tests.map((item) => item.id === testCase.id ? testCase : item);
      nextDocument = {...document, tests};
      onChange(nextDocument);
      setDraft(createDraft(testCase, nodes));
      setDirty(false);
    }
    if (!nextDocument.tests.some((testCase) => testCase.enabled)) {
      setErrors([t('ai.skills.designer.tests.runAll.noEnabled', '请至少启用一个测试用例')]);
      return;
    }
    if (nodes.length === 0) {
      setErrors([t('ai.skills.designer.tests.error.noNodes', '请先为工作流添加 MCP Tool、Prompt 或 Resource 节点')]);
      return;
    }
    await onRunAll(nextDocument);
  }, [buildTestCase, document, draft, nodes, onChange, onRunAll, t]);

  const updateDraft = useCallback((change: Partial<TestCaseDraft>) => {
    setDraft((current) => current ? {...current, ...change} : current);
    setErrors([]);
    setDirty(true);
  }, []);

  const updateMock = useCallback((nodeId: string, change: Partial<MockDraft>) => {
    setDraft((current) => current ? {
      ...current,
      mocks: current.mocks.map((mock) => mock.nodeId === nodeId ? {...mock, ...change} : mock),
    } : current);
    setErrors([]);
    setDirty(true);
  }, []);

  const selectMockEditor = useCallback((mock: MockDraft, mode: 'SCHEMA' | 'JSON') => {
    if (mode === 'SCHEMA') {
      try {
        JSON.parse(mock.outputJson);
      } catch {
        message.warning(t(
          'ai.skills.designer.tests.mock.fixJsonBeforeVisual',
          '请先修正 JSON，再切换到 Schema 表单；当前内容已保留。',
        ));
        return;
      }
    }
    setMockEditorModes((current) => ({...current, [mock.nodeId]: mode}));
  }, [message, t]);

  const replaceWithSchemaExample = useCallback((
    mock: MockDraft,
    schema: Record<string, unknown>,
  ) => {
    const replace = () => updateMock(mock.nodeId, {
      outputJson: prettyJson(defaultValueForSchema(schema)),
    });
    let current: unknown;
    try {
      current = JSON.parse(mock.outputJson);
    } catch {
      current = undefined;
    }
    if (current && typeof current === 'object' && Object.keys(current).length > 0) {
      modal.confirm({
        title: t('ai.skills.designer.tests.mock.exampleConfirmTitle', '使用 Schema 示例替换当前结果？'),
        content: t('ai.skills.designer.tests.mock.exampleConfirmDescription', '当前固定结果包含内容。确认后才会替换，取消可继续保留现有 JSON。'),
        okText: t('ai.skills.designer.tests.mock.exampleConfirm', '替换'),
        cancelText: t('ai.skills.designer.tests.mock.exampleCancel', '保留当前结果'),
        onOk: replace,
      });
      return;
    }
    replace();
  }, [modal, t, updateMock]);

  const addAssertion = useCallback(() => {
    setDraft((current) => {
      if (!current || current.assertions.length >= 128) return current;
      let index = 1;
      while (current.assertions.some((item) => item.id === `assertion-${index}`)) index += 1;
      return {
        ...current,
        assertions: [...current.assertions, {
          id: `assertion-${index}`,
          sourceNodeId: '__output',
          fieldPath: '',
          operator: 'EQUALS',
          expectedJson: '{}',
        }],
      };
    });
    setErrors([]);
    setDirty(true);
  }, []);

  const updateAssertion = useCallback((
    index: number,
    change: Partial<AssertionDraft>,
  ) => {
    setDraft((current) => current ? {
      ...current,
      assertions: current.assertions.map((assertion, currentIndex) => (
        currentIndex === index ? {...assertion, ...change} : assertion
      )),
    } : current);
    setErrors([]);
    setDirty(true);
  }, []);

  const removeAssertion = useCallback((index: number) => {
    setDraft((current) => current ? {
      ...current,
      assertions: current.assertions.filter((_, currentIndex) => currentIndex !== index),
    } : current);
    setErrors([]);
    setDirty(true);
  }, []);

  return (
    <Drawer
      open={open}
      width={980}
      title={t('ai.skills.designer.tests.title', '测试用例与 MOCK 调试')}
      onClose={requestClose}
      extra={dirty ? <Tag color="gold">{t('ai.skills.designer.tests.unsaved.tag', '未保存')}</Tag> : undefined}
    >
      <div className="skill-test-cases-layout">
        <aside className="skill-test-cases-list">
          <Space direction="vertical" size={12} style={{width: '100%'}}>
            <Button
              block
              icon={<ThunderboltOutlined />}
              loading={running || runPolling}
              disabled={!document.tests.some((testCase) => testCase.enabled)}
              onClick={() => void runAllCases()}
            >
              {t(
                'ai.skills.designer.tests.runAll.action',
                '运行全部启用用例（{count}）',
                {count: document.tests.filter((testCase) => testCase.enabled).length},
              )}
            </Button>
            <Select
              allowClear
              loading={testRunHistoryLoading}
              value={testRun?.id}
              placeholder={t('ai.skills.designer.tests.runAll.history', '最近批量回归')}
              options={testRunHistory.map((run) => ({
                value: run.id,
                label: t(
                  'ai.skills.designer.tests.runAll.historyItem',
                  '修订 {revision} · {status} · {completed}/{total}',
                  {
                    revision: run.draftRevision,
                    status: skillMockTestRunStatusLabel(t, run.status),
                    completed: run.completedCount,
                    total: run.totalCount,
                  },
                ),
              }))}
              onChange={(testRunId) => {
                if (testRunId) void onOpenTestRun(testRunId);
              }}
            />
            {testRunHistoryError && (
              <Alert
                type="warning"
                showIcon
                message={t('ai.skills.designer.tests.runAll.historyLoadFailed', '最近批量回归加载失败')}
              />
            )}
            <Button
              block
              type="primary"
              icon={<PlusOutlined />}
              disabled={document.tests.length >= 64}
              onClick={createCase}
            >
              {t('ai.skills.designer.tests.create', '新建测试用例')}
            </Button>
            <List
              size="small"
              dataSource={document.tests}
              locale={{emptyText: t('ai.skills.designer.tests.empty', '暂无测试用例')}}
              renderItem={(testCase) => (
                <List.Item
                  className={testCase.id === selectedId ? 'skill-test-case-selected' : undefined}
                  role="button"
                  tabIndex={0}
                  aria-selected={testCase.id === selectedId}
                  onClick={() => requestSelection(testCase)}
                  onKeyDown={(event) => {
                    if (event.key !== 'Enter' && event.key !== ' ') return;
                    event.preventDefault();
                    requestSelection(testCase);
                  }}
                >
                  <List.Item.Meta
                    title={<Text strong={testCase.id === selectedId}>{testCase.name}</Text>}
                    description={(
                      <Space size={4} wrap>
                        <Text type="secondary">{testCase.id}</Text>
                        <Tag color={testCase.enabled ? 'success' : 'default'}>
                          {testCase.enabled
                            ? t('ai.skills.designer.tests.enabled', '已启用')
                            : t('ai.skills.designer.tests.disabled', '已停用')}
                        </Tag>
                      </Space>
                    )}
                  />
                </List.Item>
              )}
            />
          </Space>
        </aside>
        <main className="skill-test-case-editor">
          {testRun && (
            <Card
              size="small"
              className="skill-test-run-summary"
              title={(
                <Space wrap>
                  <Text strong>{t('ai.skills.designer.tests.runAll.summary', '批量回归')}</Text>
                  <Tag>{t(
                    'ai.skills.designer.common.revisionValue',
                    '修订 {revision}',
                    {revision: testRun.draftRevision},
                  )}</Tag>
                  <Tag color={testRun.status === 'PASSED'
                    ? 'success'
                    : testRun.status === 'FAILED' ? 'error' : 'processing'}>
                    {skillMockTestRunStatusLabel(t, testRun.status)}
                  </Tag>
                </Space>
              )}
              style={{marginBottom: 16}}
            >
              <Space direction="vertical" size={12} style={{width: '100%'}}>
                <Progress
                  percent={testRun.totalCount === 0
                    ? 0
                    : Math.round((testRun.completedCount / testRun.totalCount) * 100)}
                  status={testRun.status === 'FAILED'
                    ? 'exception'
                    : testRun.status === 'PASSED' ? 'success' : 'active'}
                  format={() => `${testRun.completedCount}/${testRun.totalCount}`}
                />
                <Space wrap>
                  <Tag color="success">
                    {t('ai.skills.designer.tests.runAll.passedCount', '通过 {count}', {count: testRun.passedCount})}
                  </Tag>
                  <Tag color="error">
                    {t('ai.skills.designer.tests.runAll.failedCount', '未通过 {count}', {count: testRun.failedCount})}
                  </Tag>
                  {runPolling && <Text type="secondary">
                    {t('ai.skills.designer.tests.runAll.refreshing', '正在刷新执行状态…')}
                  </Text>}
                </Space>
                <List
                  size="small"
                  dataSource={testRun.cases}
                  renderItem={(item) => (
                    <List.Item
                      actions={[
                        <Button
                          key="detail"
                          type="link"
                          size="small"
                          onClick={() => void onOpenExecution(item.executionId)}
                        >
                          {t('ai.skills.designer.tests.runAll.view', '查看详情')}
                        </Button>,
                      ]}
                    >
                      <List.Item.Meta
                        title={<Space wrap>
                          <Text>{item.testCaseName}</Text>
                          <Tag color={runCaseStatusColor(item.status)}>
                            {skillDebugExecutionStatusLabel(t, item.status)}
                          </Tag>
                        </Space>}
                        description={item.errorMessage
                          ? `${item.errorCode ?? ''}${item.errorCode ? ' · ' : ''}${item.errorMessage}`
                          : item.testCaseId}
                      />
                    </List.Item>
                  )}
                />
              </Space>
            </Card>
          )}
          {!draft ? (
            <Empty description={t('ai.skills.designer.tests.emptyHint', '新建或选择一个测试用例开始配置')} />
          ) : (
            <Space direction="vertical" size={16} style={{width: '100%'}}>
              <Space align="start" style={{justifyContent: 'space-between', width: '100%'}}>
                <div>
                  <Title level={5} style={{margin: 0}}>{draft.name || draft.id}</Title>
                  <Text type="secondary">{draft.id}</Text>
                </div>
                <Space wrap>
                  <Button icon={<CopyOutlined />} disabled={dirty} onClick={duplicateCase}>
                    {t('ai.skills.designer.tests.duplicate', '复制')}
                  </Button>
                  <Button danger icon={<DeleteOutlined />} disabled={dirty} onClick={deleteCase}>
                    {t('ai.skills.designer.tests.delete.confirm', '删除')}
                  </Button>
                  <Button icon={<SaveOutlined />} disabled={!dirty} onClick={saveCase}>
                    {t('ai.skills.designer.tests.save', '保存用例')}
                  </Button>
                  <Button
                    type="primary"
                    icon={<ExperimentOutlined />}
                    loading={running}
                    onClick={() => void runCase()}
                  >
                    {t('ai.skills.designer.tests.run', '保存并运行 MOCK')}
                  </Button>
                </Space>
              </Space>

              {errors.length > 0 && (
                <Alert
                  type="error"
                  showIcon
                  closable
                  message={t('ai.skills.designer.tests.error.title', '请修正以下配置后再继续')}
                  description={<ul>{errors.map((error) => <li key={error}>{error}</li>)}</ul>}
                  onClose={() => setErrors([])}
                />
              )}

              <Card size="small" title={t('ai.skills.designer.tests.basic', '基本信息')}>
                <Space direction="vertical" size={12} style={{width: '100%'}}>
                  <label>
                    <Text strong>{t('ai.skills.designer.tests.name', '用例名称')}</Text>
                    <Input
                      value={draft.name}
                      maxLength={128}
                      showCount
                      onChange={(event) => updateDraft({name: event.target.value})}
                    />
                  </label>
                  <Space>
                    <Switch
                      checked={draft.enabled}
                      onChange={(enabled) => updateDraft({enabled})}
                    />
                    <Text>{t('ai.skills.designer.tests.enableHint', '启用后可用于 MOCK 调试')}</Text>
                  </Space>
                </Space>
              </Card>

              <Card
                size="small"
                title={t('ai.skills.designer.tests.input', '固定测试输入')}
                extra={<Text type="secondary">{t(
                  'ai.skills.designer.tests.inputFormat',
                  'JSON 对象',
                )}</Text>}
              >
                <Input.TextArea
                  value={draft.inputJson}
                  autoSize={{minRows: 5, maxRows: 14}}
                  spellCheck={false}
                  style={{fontFamily: 'monospace'}}
                  onChange={(event) => updateDraft({inputJson: event.target.value})}
                />
              </Card>

              <Alert
                type="info"
                showIcon
                closable
                message={t('ai.skills.designer.tests.mockSafety', 'MOCK 模式不会调用真实 MCP 服务')}
                description={t(
                  'ai.skills.designer.tests.mockSafetyDescription',
                  '每个 MCP 节点都必须有固定结果；节点变化后保存用例会自动同步，缺失结果时后端会安全失败且不会回退 LIVE。',
                )}
              />

              <Space direction="vertical" size={10} style={{width: '100%'}}>
                <Space>
                  <Title level={5} style={{margin: 0}}>{t('ai.skills.designer.tests.mocks', '逐节点 Mock')}</Title>
                  <Tag>{nodes.length}</Tag>
                </Space>
                {nodes.length === 0 ? (
                  <Empty description={t('ai.skills.designer.tests.noNodes', '添加 MCP 节点后可配置固定结果')} />
                ) : draft.mocks.map((mock) => {
                  const nodeIndex = nodes.findIndex((item) => item.id === mock.nodeId);
                  const node = nodes[nodeIndex];
                  if (!node) return null;
                  const binding = toolBindings[nodeIndex];
                  const snapshotQuery = snapshotQueryFor(mock.nodeId);
                  const outputSchema = outputSchemaFor(mock.nodeId);
                  const hasVisualSchema = visualSchemaSupported(outputSchema);
                  const editorMode = hasVisualSchema
                    ? mockEditorModes[mock.nodeId] ?? 'SCHEMA'
                    : 'JSON';
                  let parsedOutput: unknown;
                  let outputJsonValid = true;
                  try {
                    parsedOutput = JSON.parse(mock.outputJson);
                  } catch {
                    outputJsonValid = false;
                  }
                  return (
                    <Card
                      key={mock.nodeId}
                      size="small"
                      title={(
                        <Space>
                          <Tag color="blue">{skillNodeTypeLabel(t, node.type)}</Tag>
                          <Text>{capabilityLabel(node)}</Text>
                        </Space>
                      )}
                      extra={(
                        <Radio.Group
                          optionType="button"
                          buttonStyle="solid"
                          value={mock.mode}
                          options={[
                            {label: t('ai.skills.designer.tests.mock.success', '成功'), value: 'SUCCESS'},
                            {label: t('ai.skills.designer.tests.mock.error', '失败'), value: 'ERROR'},
                          ]}
                          onChange={(event) => updateMock(mock.nodeId, {mode: event.target.value})}
                        />
                      )}
                    >
                      {mock.mode === 'SUCCESS' ? (
                        <Space direction="vertical" size={10} style={{width: '100%'}}>
                          {node.type === 'TOOL' && snapshotQuery?.isLoading && (
                            <Alert
                              type="info"
                              showIcon
                              message={t('ai.skills.designer.tests.mock.schemaLoading', '正在读取固定 Snapshot 的输出 Schema')}
                            />
                          )}
                          {node.type === 'TOOL' && (!binding?.serverId || !binding.snapshotId || !binding.name) && (
                            <Alert
                              type="warning"
                              showIcon
                              message={t('ai.skills.designer.tests.mock.bindingIncomplete', '请先在节点属性中完成 Tool 与能力 Snapshot 绑定')}
                            />
                          )}
                          {node.type === 'TOOL' && snapshotQuery?.isError && (
                            <Alert
                              type="warning"
                              showIcon
                              message={t('ai.skills.designer.tests.mock.schemaLoadFailed', '固定输出 Schema 加载失败，已保留 JSON 编辑')}
                              description={t('ai.skills.designer.tests.mock.schemaLoadFailedDescription', '可继续编辑且不会清空内容；重试成功后可切换为 Schema 表单。')}
                              action={(
                                <Button
                                  size="small"
                                  icon={<ReloadOutlined />}
                                  onClick={() => void snapshotQuery.refetch()}
                                >
                                  {t('ai.skills.designer.tests.mock.retrySchema', '重试')}
                                </Button>
                              )}
                            />
                          )}
                          {node.type !== 'TOOL' && (
                            <Alert
                              type="info"
                              showIcon
                              message={t('ai.skills.designer.tests.mock.noProtocolSchema', '该能力类型没有标准输出 Schema，使用固定 JSON 结果')}
                            />
                          )}
                          {node.type === 'TOOL' && snapshotQuery?.isSuccess && !outputSchema && (
                            <Alert
                              type="info"
                              showIcon
                              message={t('ai.skills.designer.tests.mock.noSchema', '该 Tool 未声明输出 Schema，使用固定 JSON 结果')}
                              description={t('ai.skills.designer.tests.mock.noSchemaDescription', '结果仍会被固定到当前 Draft Revision，并可用于节点断言。')}
                            />
                          )}
                          {node.type === 'TOOL' && outputSchema && !hasVisualSchema && (
                            <Alert
                              type="warning"
                              showIcon
                              message={t('ai.skills.designer.tests.mock.schemaUnsupported', '该输出 Schema 含复杂结构，使用 JSON 编辑器')}
                              description={t('ai.skills.designer.tests.mock.schemaUnsupportedDescription', '不会改用最新能力描述，也不会丢弃当前结果；执行时仍固定此 Snapshot。')}
                            />
                          )}
                          {hasVisualSchema && (
                            <Space style={{justifyContent: 'space-between', width: '100%'}} wrap>
                              <Radio.Group
                                optionType="button"
                                value={editorMode}
                                options={[
                                  {
                                    label: <Space size={4}><FormOutlined />{t('ai.skills.designer.tests.mock.schemaForm', 'Schema 表单')}</Space>,
                                    value: 'SCHEMA',
                                  },
                                  {
                                    label: <Space size={4}><CodeOutlined />JSON</Space>,
                                    value: 'JSON',
                                  },
                                ]}
                                onChange={(event) => selectMockEditor(mock, event.target.value)}
                              />
                              <Button
                                size="small"
                                onClick={() => replaceWithSchemaExample(mock, outputSchema)}
                              >
                                {t('ai.skills.designer.tests.mock.generateExample', '按 Schema 生成示例')}
                              </Button>
                            </Space>
                          )}
                          {editorMode === 'SCHEMA' && hasVisualSchema ? (
                            outputJsonValid ? (
                              <SchemaValueEditor
                                schema={outputSchema}
                                value={parsedOutput}
                                onChange={(value) => updateMock(mock.nodeId, {outputJson: prettyJson(value)})}
                              />
                            ) : (
                              <Alert
                                type="error"
                                showIcon
                                message={t('ai.skills.designer.tests.mock.invalidJsonPreserved', 'JSON 格式无效，内容已保留')}
                                description={t('ai.skills.designer.tests.mock.invalidJsonPreservedDescription', '切换回 JSON 编辑器修正后，即可继续使用 Schema 表单。')}
                                action={(
                                  <Button size="small" onClick={() => selectMockEditor(mock, 'JSON')}>
                                    {t('ai.skills.designer.tests.mock.backToJson', '返回 JSON')}
                                  </Button>
                                )}
                              />
                            )
                          ) : (
                            <label>
                              <Text strong>{t('ai.skills.designer.tests.mock.output', '固定 JSON 结果')}</Text>
                              <Input.TextArea
                                value={mock.outputJson}
                                autoSize={{minRows: 4, maxRows: 12}}
                                spellCheck={false}
                                style={{fontFamily: 'monospace'}}
                                onChange={(event) => updateMock(mock.nodeId, {outputJson: event.target.value})}
                              />
                            </label>
                          )}
                        </Space>
                      ) : (
                        <Space direction="vertical" size={10} style={{width: '100%'}}>
                          <label>
                            <Text strong>{t('ai.skills.designer.tests.mock.errorCode', '稳定错误码')}</Text>
                            <Input
                              value={mock.errorCode}
                              maxLength={128}
                              placeholder="MOCK_UPSTREAM_UNAVAILABLE"
                              onChange={(event) => updateMock(mock.nodeId, {errorCode: event.target.value})}
                            />
                          </label>
                          <label>
                            <Text strong>{t('ai.skills.designer.tests.mock.errorMessage', '错误说明')}</Text>
                            <Input.TextArea
                              value={mock.errorMessage}
                              maxLength={1024}
                              autoSize={{minRows: 2, maxRows: 5}}
                              onChange={(event) => updateMock(mock.nodeId, {errorMessage: event.target.value})}
                            />
                          </label>
                        </Space>
                      )}
                    </Card>
                  );
                })}
              </Space>

              <Space direction="vertical" size={10} style={{width: '100%'}}>
                <Space style={{justifyContent: 'space-between', width: '100%'}}>
                  <Space>
                    <Title level={5} style={{margin: 0}}>
                      {t('ai.skills.designer.tests.assertions', '结果断言')}
                    </Title>
                    <Tag>{draft.assertions.length}</Tag>
                  </Space>
                  <Button
                    icon={<PlusOutlined />}
                    disabled={draft.assertions.length >= 128}
                    onClick={addAssertion}
                  >
                    {t('ai.skills.designer.tests.assertion.add', '添加断言')}
                  </Button>
                </Space>
                <Text type="secondary">
                  {t(
                    'ai.skills.designer.tests.assertion.hint',
                    '字段路径使用点号和数组下标，例如 structuredContent.items.0；留空表示比较整个来源。',
                  )}
                </Text>
                {draft.assertions.length === 0 ? (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={t('ai.skills.designer.tests.assertion.empty', '未配置断言，所有 Mock 节点成功即视为测试通过')}
                  />
                ) : draft.assertions.map((assertion, index) => {
                  const expectsValue = assertion.operator !== 'EXISTS'
                    && assertion.operator !== 'NOT_EXISTS';
                  return (
                    <Card
                      key={`${assertion.id}-${index}`}
                      size="small"
                      title={t('ai.skills.designer.tests.assertion.item', '断言 {index}', {index: index + 1})}
                      extra={(
                        <Button
                          danger
                          type="text"
                          icon={<DeleteOutlined />}
                          onClick={() => removeAssertion(index)}
                        />
                      )}
                    >
                      <div className="skill-test-assertion-grid">
                        <label>
                          <Text strong>{t('ai.skills.designer.tests.assertion.id', '断言 ID')}</Text>
                          <Input
                            value={assertion.id}
                            maxLength={64}
                            onChange={(event) => updateAssertion(index, {id: event.target.value})}
                          />
                        </label>
                        <label>
                          <Text strong>{t('ai.skills.designer.tests.assertion.source', '结果来源')}</Text>
                          <Select
                            value={assertion.sourceNodeId}
                            style={{width: '100%'}}
                            options={[
                              {label: t('ai.skills.designer.tests.assertion.workflowOutput', '工作流最终输出'), value: '__output'},
                              ...nodes.map((node) => ({
                                label: capabilityLabel(node),
                                value: node.id,
                              })),
                            ]}
                            onChange={(sourceNodeId) => updateAssertion(index, {sourceNodeId})}
                          />
                        </label>
                        <label>
                          <Text strong>{t('ai.skills.designer.tests.assertion.path', '字段路径')}</Text>
                          <Input
                            value={assertion.fieldPath}
                            maxLength={512}
                            placeholder="structuredContent.message"
                            onChange={(event) => updateAssertion(index, {fieldPath: event.target.value})}
                          />
                        </label>
                        <label>
                          <Text strong>{t('ai.skills.designer.tests.assertion.operator', '运算符')}</Text>
                          <Select
                            value={assertion.operator}
                            style={{width: '100%'}}
                            options={[
                              'EQUALS',
                              'NOT_EQUALS',
                              'EXISTS',
                              'NOT_EXISTS',
                              'CONTAINS',
                              'MATCHES',
                            ].map((operator) => ({label: operator, value: operator}))}
                            onChange={(operator) => updateAssertion(index, {
                              operator: operator as SkillDesignerTestAssertion['operator'],
                            })}
                          />
                        </label>
                        {expectsValue && (
                          <label className="skill-test-assertion-expected">
                            <Text strong>{t('ai.skills.designer.tests.assertion.expected', '期望值（JSON）')}</Text>
                            <Input.TextArea
                              value={assertion.expectedJson}
                              autoSize={{minRows: 2, maxRows: 8}}
                              spellCheck={false}
                              style={{fontFamily: 'monospace'}}
                              placeholder={assertion.operator === 'MATCHES' ? '"^ok$"' : '{"status":"ok"}'}
                              onChange={(event) => updateAssertion(index, {expectedJson: event.target.value})}
                            />
                          </label>
                        )}
                      </div>
                    </Card>
                  );
                })}
              </Space>
            </Space>
          )}
        </main>
      </div>
    </Drawer>
  );
};

export default SkillDesignerTestCases;
