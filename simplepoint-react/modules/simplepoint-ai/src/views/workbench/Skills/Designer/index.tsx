import '@xyflow/react/dist/style.css';
import {scopedQueryOptions, scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import './index.css';

import {
  ArrowLeftOutlined,
  ApartmentOutlined,
  AimOutlined,
  CaretRightOutlined,
  CheckCircleOutlined,
  CodeOutlined,
  CopyOutlined,
  DoubleLeftOutlined,
  DoubleRightOutlined,
  ExperimentOutlined,
  HistoryOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  RedoOutlined,
  RocketOutlined,
  SaveOutlined,
  StopOutlined,
  UndoOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQuery, useQueryClient} from '@tanstack/react-query';
import {ReactFlowProvider} from '@xyflow/react';
import DataTable from '@simplepoint/components/DataTable';
import {Alert, App, Button, Checkbox, Descriptions, Drawer, Empty, Modal, Skeleton, Space, Tag, Tooltip, Typography} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router';
import {
  loadDraft,
  loadDraftRevisions,
  loadSkill,
  loadVersionDesigner,
  loadDraftDebugExecution,
  loadDraftDebugExecutions,
  loadDraftDebugEvents,
  loadDraftMockTestRun,
  loadDraftMockTestRuns,
  cancelDraftDebugExecution,
  continueDraftDebugExecution,
  copyVersionToDraft,
  restoreDraftRevision,
  saveDraft,
  pauseDraftDebugExecution,
  setDraftDebugBreakpoints,
  startDraftDebugExecution,
  startDraftMockTestRun,
  validateDraft,
} from './api';
import SchemaExecutionForm, {type ExecutionInput} from '../../components/SchemaExecutionForm';
import {addDesignerNode, createDesignerDocument} from './document';
import SkillDesignerCanvas, {autoLayoutDocument} from './SkillDesignerCanvas';
import SkillDesignerInspector from './SkillDesignerInspector';
import SkillDesignerPalette from './SkillDesignerPalette';
import SkillDesignerReadOnlyInspector from './SkillDesignerReadOnlyInspector';
import SkillDesignerTestCases from './SkillDesignerTestCases';
import SkillPublishDrawer from './SkillPublishDrawer';
import {
  skillDebugExecutionStatusLabel,
  skillDebugModeLabel,
  skillDebugNodeStatusLabel,
  skillDraftRevisionSourceLabel,
  skillDesignerDiagnosticLabel,
  skillDesignerDiagnosticLocationLabel,
  skillStepTypeLabel,
  skillValidationStatusLabel,
} from './labels';
import type {
  SkillDesignerCompilation,
  SkillDesignerDocument,
  SkillDraftRevision,
  SkillDraftView,
  SkillDebugExecution,
  SkillDebugExecutionEvent,
  SkillDebugNodeStatus,
  SkillMockTestRun,
} from './types';
import {INPUT_NODE_ID, OUTPUT_NODE_ID} from './types';

const {Text, Title} = Typography;

const terminalDebugStatuses: SkillDebugExecution['status'][] = [
  'SUCCEEDED',
  'FAILED',
  'REJECTED',
  'CANCELLED',
];

const debugStatusColor = (status: SkillDebugExecution['status']) => ({
  WAITING_APPROVAL: 'gold',
  PENDING: 'processing',
  RUNNING: 'blue',
  PAUSED: 'orange',
  SUCCEEDED: 'success',
  FAILED: 'error',
  REJECTED: 'error',
  CANCELLED: 'default',
}[status]);

const stepDuration = (startedAt?: string, completedAt?: string) => {
  if (!startedAt) return '-';
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '-';
  const milliseconds = end - start;
  return milliseconds < 1000
    ? `${milliseconds} ms`
    : `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 2 : 1)} s`;
};

const deriveDebugNodeStatuses = (
  execution: SkillDebugExecution,
): Record<string, SkillDebugNodeStatus> => {
  const statuses = Object.fromEntries(
    (execution.steps ?? []).map((step) => [step.stepId, step.status]),
  ) as Record<string, SkillDebugNodeStatus>;
  statuses[INPUT_NODE_ID] = execution.status === 'WAITING_APPROVAL'
    ? 'WAITING_APPROVAL'
    : execution.status === 'PAUSED'
      ? 'PAUSED'
      : execution.status === 'PENDING'
        ? 'PENDING'
        : 'SUCCEEDED';
  if (execution.status === 'SUCCEEDED') statuses[OUTPUT_NODE_ID] = 'SUCCEEDED';
  if (execution.status === 'FAILED') statuses[OUTPUT_NODE_ID] = 'FAILED';
  if (execution.status === 'REJECTED' || execution.status === 'CANCELLED') {
    statuses[OUTPUT_NODE_ID] = 'SKIPPED';
  }
  return statuses;
};

const applyDebugEvents = (
  previous: Record<string, SkillDebugNodeStatus>,
  events: SkillDebugExecutionEvent[],
) => {
  const next = {...previous};
  const stepStatuses: Partial<Record<SkillDebugExecutionEvent['type'], SkillDebugNodeStatus>> = {
    STEP_STARTED: 'RUNNING',
    STEP_SUCCEEDED: 'SUCCEEDED',
    STEP_FAILED: 'FAILED',
    STEP_SKIPPED: 'SKIPPED',
  };
  events.forEach((event) => {
    const stepStatus = stepStatuses[event.type];
    if (event.stepId && stepStatus) next[event.stepId] = stepStatus;
    if (event.type === 'APPROVAL_REQUIRED') next[INPUT_NODE_ID] = 'WAITING_APPROVAL';
    if (event.type === 'EXECUTION_PAUSED') next[INPUT_NODE_ID] = 'PAUSED';
    if (event.type === 'EXECUTION_STARTED') next[INPUT_NODE_ID] = 'SUCCEEDED';
    if (event.type === 'EXECUTION_SUCCEEDED') next[OUTPUT_NODE_ID] = 'SUCCEEDED';
    if (event.type === 'EXECUTION_FAILED') next[OUTPUT_NODE_ID] = 'FAILED';
    if (event.type === 'APPROVAL_REJECTED' || event.type === 'EXECUTION_CANCELLED') {
      next[OUTPUT_NODE_ID] = 'SKIPPED';
    }
  });
  return next;
};

const SkillDesigner = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const skillId = searchParams.get('skillId')?.trim() ?? '';
  const versionId = searchParams.get('versionId')?.trim() ?? '';
  const readOnly = Boolean(versionId);
  const {ensure, t} = useI18n();
  const {message, modal} = App.useApp();
  const queryClient = useQueryClient();
  const queryScope = useQueryScope();
  const [document, setDocument] = useState<SkillDesignerDocument>();
  const [draft, setDraft] = useState<SkillDraftView>();
  const [compilation, setCompilation] = useState<SkillDesignerCompilation>();
  const [selectedNodeId, setSelectedNodeId] = useState<string>();
  const [past, setPast] = useState<SkillDesignerDocument[]>([]);
  const [future, setFuture] = useState<SkillDesignerDocument[]>([]);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [manifestOpen, setManifestOpen] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [copyingVersion, setCopyingVersion] = useState(false);
  const [preparingDebug, setPreparingDebug] = useState(false);
  const [debugInputOpen, setDebugInputOpen] = useState(false);
  const [debugRevision, setDebugRevision] = useState<number>();
  const [debugSubmitting, setDebugSubmitting] = useState(false);
  const [debugExecution, setDebugExecution] = useState<SkillDebugExecution>();
  const [debugPolling, setDebugPolling] = useState(false);
  const [debugHistoryOpen, setDebugHistoryOpen] = useState(false);
  const [debugHistoryLoadingId, setDebugHistoryLoadingId] = useState<string>();
  const [testCasesOpen, setTestCasesOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [preparingPublish, setPreparingPublish] = useState(false);
  const [mockTestRun, setMockTestRun] = useState<SkillMockTestRun>();
  const [mockTestRunPolling, setMockTestRunPolling] = useState(false);
  const [debugControlLoading, setDebugControlLoading] = useState(false);
  const [breakpointsOpen, setBreakpointsOpen] = useState(false);
  const [selectedBreakpointIds, setSelectedBreakpointIds] = useState<string[]>([]);
  const [debugNodeStatuses, setDebugNodeStatuses] = useState<Record<string, SkillDebugNodeStatus>>({});
  const [autoSaveError, setAutoSaveError] = useState<string>();
  const [toolbarCollapsed, setToolbarCollapsed] = useState(false);
  const [paletteCollapsed, setPaletteCollapsed] = useState(false);
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false);
  const [diagnosticsCollapsed, setDiagnosticsCollapsed] = useState(false);
  const loadedKey = useRef<string | undefined>(undefined);
  const editSequence = useRef(0);
  const failedAutoSaveSequence = useRef<number | undefined>(undefined);
  const debugWatchToken = useRef(0);
  const mockTestRunWatchToken = useRef(0);

  useEffect(() => {
    if (selectedNodeId) setInspectorCollapsed(false);
  }, [selectedNodeId]);

  useEffect(() => {
    if ((compilation?.diagnostics.length ?? 0) > 0) {
      setDiagnosticsCollapsed(false);
    }
  }, [compilation]);

  useEffect(() => {
    void ensure(['ai-skills']);
  }, [ensure]);

  const skillQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['ai-skill-designer-skill', skillId], ({signal}) => loadSkill(skillId, signal)),
    enabled: Boolean(skillId),
  });
  const draftQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['ai-skill-designer-draft', skillId], ({signal}) => loadDraft(skillId, signal)),
    enabled: Boolean(skillId),
  });
  const versionQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['ai-skill-version-designer', skillId, versionId], ({signal}) => loadVersionDesigner(skillId, versionId, signal)),
    enabled: Boolean(skillId && versionId),
  });
  const debugHistoryQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['ai-skill-designer-debug-history', skillId], ({signal}) => loadDraftDebugExecutions(skillId, signal)),
    enabled: Boolean(skillId && debugHistoryOpen),
  });
  const mockTestRunHistoryQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['ai-skill-designer-mock-test-runs', skillId], ({signal}) => loadDraftMockTestRuns(skillId, signal)),
    enabled: Boolean(skillId && testCasesOpen),
  });

  useEffect(() => {
    if (readOnly) {
      if (!skillQuery.data || versionQuery.isLoading) return;
      const versionView = versionQuery.data;
      const key = `${skillId}:version:${versionId}`;
      if (loadedKey.current === key) return;
      loadedKey.current = key;
      setDocument(versionView?.document ?? undefined);
      setCompilation(undefined);
      setSelectedNodeId(undefined);
      setPast([]);
      setFuture([]);
      setDirty(false);
      return;
    }
    if (!skillQuery.data || draftQuery.isLoading) return;
    const nextDraft = draftQuery.data;
    const key = `${skillId}:${nextDraft?.revision ?? 0}`;
    if (loadedKey.current === key) return;
    loadedKey.current = key;
    setDraft(nextDraft ?? undefined);
    setDocument(nextDraft?.document ?? createDesignerDocument(skillQuery.data));
    setCompilation(nextDraft?.compilation);
    setSelectedNodeId(undefined);
    setPast([]);
    setFuture([]);
    editSequence.current = 0;
    failedAutoSaveSequence.current = undefined;
    setAutoSaveError(undefined);
    setDirty(false);
  }, [draftQuery.data, draftQuery.isLoading, readOnly, skillId, skillQuery.data, versionId, versionQuery.data, versionQuery.isLoading]);

  const changeDocument = useCallback((next: SkillDesignerDocument) => {
    if (readOnly) return;
    if (document) setPast((items) => [...items.slice(-99), document]);
    setDocument(next);
    setFuture([]);
    setCompilation(undefined);
    editSequence.current += 1;
    failedAutoSaveSequence.current = undefined;
    setAutoSaveError(undefined);
    setDirty(true);
  }, [document, readOnly]);

  const undo = useCallback(() => {
    const previous = past[past.length - 1];
    if (!previous) return;
    if (document) setFuture((items) => [document, ...items].slice(0, 100));
    setPast(past.slice(0, -1));
    setDocument(previous);
    setCompilation(undefined);
    editSequence.current += 1;
    failedAutoSaveSequence.current = undefined;
    setAutoSaveError(undefined);
    setDirty(true);
  }, [document, past]);

  const redo = useCallback(() => {
    const next = future[0];
    if (!next) return;
    if (document) setPast((items) => [...items.slice(-99), document]);
    setFuture(future.slice(1));
    setDocument(next);
    setCompilation(undefined);
    editSequence.current += 1;
    failedAutoSaveSequence.current = undefined;
    setAutoSaveError(undefined);
    setDirty(true);
  }, [document, future]);

  const addNodeFromPalette = useCallback((
    nodeType: 'TOOL' | 'PROMPT' | 'RESOURCE' | 'CONDITION' | 'PARALLEL',
  ) => {
    if (readOnly || !document) return;
    const nodeIndex = document.nodes.length - 2;
    const added = addDesignerNode(document, nodeType, {
      x: 180 + (nodeIndex % 5) * 36,
      y: 120 + (nodeIndex % 6) * 105,
    });
    changeDocument(added.document);
    setSelectedNodeId(added.nodeId);
  }, [changeDocument, document, readOnly]);

  const persist = useCallback(async (
    silent = false,
    sourceDocument: SkillDesignerDocument | undefined = document,
  ) => {
    if (readOnly || !skillId || !sourceDocument) return undefined;
    const savingSequence = editSequence.current;
    setSaving(true);
    try {
      const saved = await saveDraft(skillId, draft?.revision ?? 0, sourceDocument);
      loadedKey.current = `${skillId}:${saved.revision}`;
      setDraft(saved);
      if (editSequence.current === savingSequence) {
        setDocument(saved.document);
        setCompilation(saved.compilation);
        setDirty(false);
      }
      queryClient.setQueryData(scopedQueryKey(queryScope, ['ai-skill-designer-draft', skillId]), saved);
      setAutoSaveError(undefined);
      if (!silent) message.success(t(
        'ai.skills.designer.message.saved',
        '草稿 Revision {revision} 已保存',
        {revision: saved.revision},
      ));
      return saved;
    } catch (error) {
      if (isHttpError(error) && error.status === 409) {
        modal.confirm({
          title: t('ai.skills.designer.conflict.title', '草稿已被其他会话更新'),
          content: t(
            'ai.skills.designer.conflict.description',
            '当前页面仍保留你的编辑。重新加载会放弃这些未保存修改并读取服务器最新 Revision。',
          ),
          okText: t('ai.skills.designer.action.reload', '重新加载'),
          cancelText: t('ai.skills.designer.action.keepEditing', '保留当前编辑'),
          onOk: async () => {
            loadedKey.current = undefined;
            await queryClient.invalidateQueries({queryKey: scopedQueryKey(queryScope, ['ai-skill-designer-draft', skillId])});
          },
        });
      } else {
        if (!silent) message.error(resolveApiErrorMessage(
          error,
          t('ai.skills.designer.error.save', 'Skill 草稿保存失败'),
        ));
      }
      if (silent) {
        failedAutoSaveSequence.current = savingSequence;
        setAutoSaveError(resolveApiErrorMessage(
          error,
          t('ai.skills.designer.error.autoSave', '自动保存失败'),
        ));
      }
      return undefined;
    } finally {
      setSaving(false);
    }
  }, [document, draft?.revision, message, modal, queryClient, queryScope, readOnly, skillId, t]);

  useEffect(() => {
    if (!dirty || saving || validating || !document) return;
    if (failedAutoSaveSequence.current === editSequence.current) return;
    const timer = window.setTimeout(() => void persist(true), 1500);
    return () => window.clearTimeout(timer);
  }, [dirty, document, persist, saving, validating]);

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirty) return;
      event.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return;
      if (event.key.toLowerCase() === 'z' && !event.shiftKey) {
        event.preventDefault();
        undo();
      } else if (event.key.toLowerCase() === 'y' || event.key.toLowerCase() === 'z' && event.shiftKey) {
        event.preventDefault();
        redo();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [redo, undo]);

  const validate = useCallback(async () => {
    setValidating(true);
    try {
      const saved = dirty || !draft ? await persist(true) : draft;
      if (!saved) return;
      const result = await validateDraft(skillId);
      setCompilation(result);
      setDraft({...saved, compilation: result});
      if (result.valid) {
        message.success(t(
          'ai.skills.designer.validation.success',
          '校验通过，可编译为 Skill Manifest',
        ));
      } else {
        message.warning(t(
          'ai.skills.designer.validation.failed',
          '发现 {count} 个错误',
          {count: result.diagnostics.filter((item) => item.severity === 'ERROR').length},
        ));
      }
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.error.validate', 'Skill 草稿校验失败'),
      ));
    } finally {
      setValidating(false);
    }
  }, [dirty, draft, message, persist, skillId, t]);

  const openPublish = useCallback(async () => {
    if (readOnly || saving || !document) return;
    setPreparingPublish(true);
    try {
      const saved = dirty || !draft ? await persist(true) : draft;
      if (!saved) return;
      const result = await validateDraft(skillId);
      setCompilation(result);
      setDraft({...saved, compilation: result});
      if (!result.valid || !result.manifest || !result.contentHash) {
        message.warning(t(
          'ai.skills.designer.publish.invalidDraft',
          '发布前必须修复全部校验错误，当前编辑内容已保留',
        ));
        return;
      }
      setPublishOpen(true);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.publish.prepareFailed', '发布准备失败，当前编辑内容已保留'),
      ));
    } finally {
      setPreparingPublish(false);
    }
  }, [dirty, document, draft, message, persist, readOnly, saving, skillId, t]);

  const revisionsQuery = useQuery({
    ...scopedQueryOptions(queryScope, ['ai-skill-designer-revisions', skillId], ({signal}) => loadDraftRevisions(skillId, signal)),
    enabled: historyOpen && Boolean(draft),
  });

  const restoreRevision = useCallback((revision: SkillDraftRevision) => {
    if (!draft) return;
    modal.confirm({
      title: t(
        'ai.skills.designer.restore.title',
        '恢复 Revision {revision}？',
        {revision: revision.revision},
      ),
      content: t(
        'ai.skills.designer.restore.description',
        '历史快照会作为一个新的 Revision 保存，当前 Draft 不会被直接覆盖而丢失。',
      ),
      okText: t('ai.skills.designer.action.restore', '恢复'),
      onOk: async () => {
        setRestoring(true);
        try {
          const restored = await restoreDraftRevision(skillId, revision.revision, draft.revision);
          loadedKey.current = `${skillId}:${restored.revision}`;
          editSequence.current = 0;
          failedAutoSaveSequence.current = undefined;
          setAutoSaveError(undefined);
          setDraft(restored);
          setDocument(restored.document);
          setCompilation(restored.compilation);
          setPast([]);
          setFuture([]);
          setDirty(false);
          setHistoryOpen(false);
          queryClient.setQueryData(scopedQueryKey(queryScope, ['ai-skill-designer-draft', skillId]), restored);
          await queryClient.invalidateQueries({queryKey: scopedQueryKey(queryScope, ['ai-skill-designer-revisions', skillId])});
          message.success(t(
            'ai.skills.designer.restore.success',
            '已恢复为新 Revision {revision}',
            {revision: restored.revision},
          ));
        } catch (error) {
          message.error(resolveApiErrorMessage(
            error,
            t('ai.skills.designer.error.restore', '历史 Revision 恢复失败'),
          ));
        } finally {
          setRestoring(false);
        }
      },
    });
  }, [draft, message, modal, queryClient, queryScope, skillId, t]);

  const copyCurrentVersion = useCallback(() => {
    if (!versionId || !versionQuery.data?.compatible) return;
    const expectedRevision = draftQuery.data?.revision ?? 0;
    modal.confirm({
      title: t(
        'ai.skills.designer.copy.title',
        '基于版本 {version} 创建 Draft？',
        {version: versionQuery.data.version},
      ),
      content: draftQuery.data
        ? t(
          'ai.skills.designer.copy.description.existing',
          '当前 Draft Revision {revision} 会保留在历史中，版本内容将保存为新的 Revision。',
          {revision: expectedRevision},
        )
        : t(
          'ai.skills.designer.copy.description.new',
          '该版本内容会转换成一个新的可编辑 Draft Revision。',
        ),
      okText: t('ai.skills.designer.action.createDraft', '创建 Draft'),
      onOk: async () => {
        setCopyingVersion(true);
        try {
          const copied = await copyVersionToDraft(skillId, versionId, expectedRevision);
          queryClient.setQueryData(scopedQueryKey(queryScope, ['ai-skill-designer-draft', skillId]), copied);
          loadedKey.current = undefined;
          message.success(t(
            'ai.skills.designer.copy.success',
            '已创建 Draft Revision {revision}',
            {revision: copied.revision},
          ));
          navigate(`/ai/workbench/skill-designer?skillId=${encodeURIComponent(skillId)}`);
        } catch (error) {
          if (isHttpError(error) && error.status === 409) {
            await queryClient.invalidateQueries({queryKey: scopedQueryKey(queryScope, ['ai-skill-designer-draft', skillId])});
            message.warning(t(
              'ai.skills.designer.copy.conflict',
              'Draft 已被其他会话更新，请重新执行复制操作',
            ));
          } else {
            message.error(resolveApiErrorMessage(
              error,
              t('ai.skills.designer.error.copy', '基于版本创建 Draft 失败'),
            ));
          }
        } finally {
          setCopyingVersion(false);
        }
      },
    });
  }, [draftQuery.data, message, modal, navigate, queryClient, queryScope, skillId, t, versionId, versionQuery.data]);

  const openDebugInput = useCallback(async () => {
    if (readOnly || saving || !document) return;
    setPreparingDebug(true);
    try {
      const saved = dirty || !draft ? await persist(true) : draft;
      if (!saved) return;
      const result = await validateDraft(skillId);
      setCompilation(result);
      setDraft({...saved, compilation: result});
      if (!result.valid) {
        message.warning(t(
          'ai.skills.designer.debug.invalid',
          '请先修复草稿校验错误，再启动调试',
        ));
        return;
      }
      setDebugRevision(saved.revision);
      setDebugInputOpen(true);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.prepareFailed', '调试准备失败'),
      ));
    } finally {
      setPreparingDebug(false);
    }
  }, [dirty, document, draft, message, persist, readOnly, saving, skillId, t]);

  const watchDebugExecution = useCallback(async (initial: SkillDebugExecution) => {
    let current = initial;
    let cursor = 0;
    let consecutiveErrors = 0;
    const watchToken = ++debugWatchToken.current;
    setDebugNodeStatuses(deriveDebugNodeStatuses(initial));
    setDebugPolling(true);
    try {
      for (let index = 0; index < 300 && watchToken === debugWatchToken.current; index += 1) {
        try {
          let hasMore = false;
          do {
            const feed = await loadDraftDebugEvents(skillId, current.id, cursor);
            if (watchToken !== debugWatchToken.current) return current;
            cursor = feed.nextSequence;
            hasMore = feed.hasMore;
            if (feed.events.length > 0) {
              setDebugNodeStatuses((statuses) => applyDebugEvents(statuses, feed.events));
            }
          } while (hasMore && watchToken === debugWatchToken.current);
          current = await loadDraftDebugExecution(skillId, current.id);
          if (watchToken !== debugWatchToken.current) return current;
          setDebugExecution(current);
          setDebugNodeStatuses((statuses) => ({...statuses, ...deriveDebugNodeStatuses(current)}));
          consecutiveErrors = 0;
        } catch (error) {
          consecutiveErrors += 1;
          if (consecutiveErrors >= 5) {
            message.warning(resolveApiErrorMessage(
              error,
              t('ai.skills.designer.debug.refreshFailed', '调试状态自动刷新已暂停，可从调试记录重新打开'),
            ));
            break;
          }
        }
        if (terminalDebugStatuses.includes(current.status)
          || current.status === 'WAITING_APPROVAL'
          || current.status === 'PAUSED') break;
        await new Promise((resolve) => window.setTimeout(resolve, consecutiveErrors > 0 ? 1200 : 500));
      }
      return current;
    } finally {
      if (watchToken === debugWatchToken.current) setDebugPolling(false);
    }
  }, [message, skillId, t]);

  const showDebugExecution = useCallback((started: SkillDebugExecution) => {
    setDebugExecution(started);
    setDebugNodeStatuses(deriveDebugNodeStatuses(started));
    void watchDebugExecution(started).then((completed) => {
      if (completed.status === 'SUCCEEDED') {
        message.success(completed.debugMode === 'MOCK'
          ? t('ai.skills.designer.debug.mockSucceeded', 'MOCK 测试执行成功')
          : t('ai.skills.designer.debug.succeeded', 'Draft 调试执行成功'));
      } else if (completed.status === 'FAILED') {
        message.error(completed.errorCode === 'SKILL_MOCK_ASSERTION_FAILED'
          ? t('ai.skills.designer.debug.assertionFailed', '一个或多个 MOCK 测试断言未通过，请查看下方明细')
          : completed.errorMessage
            ?? t('ai.skills.designer.debug.failed', 'Draft 调试执行失败'));
      } else if (completed.status === 'WAITING_APPROVAL') {
        message.info(t(
          'ai.skills.designer.debug.awaitingApproval',
          '调试执行正在等待审批，可在 Skill 执行记录中处理',
        ));
      }
    });
  }, [message, t, watchDebugExecution]);

  const runDebugExecution = useCallback(async (input: ExecutionInput) => {
    if (!debugRevision) return;
    setDebugSubmitting(true);
    try {
      const started = await startDraftDebugExecution(skillId, debugRevision, input);
      setDebugInputOpen(false);
      showDebugExecution(started);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.submitFailed', 'Draft 调试提交失败'),
      ));
    } finally {
      setDebugSubmitting(false);
    }
  }, [debugRevision, message, showDebugExecution, skillId, t]);

  const runMockTestCase = useCallback(async (
    nextDocument: SkillDesignerDocument,
    testCaseId: string,
  ) => {
    setPreparingDebug(true);
    try {
      const saved = await persist(true, nextDocument);
      if (!saved) return;
      const result = await validateDraft(skillId);
      setCompilation(result);
      setDraft({...saved, compilation: result});
      if (!result.valid) {
        message.warning(t(
          'ai.skills.designer.debug.invalid',
          '请先修复草稿校验错误，再启动调试',
        ));
        return;
      }
      const started = await startDraftDebugExecution(
        skillId,
        saved.revision,
        {},
        'MOCK',
        testCaseId,
      );
      setTestCasesOpen(false);
      showDebugExecution(started);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.mockSubmitFailed', 'MOCK 测试提交失败'),
      ));
    } finally {
      setPreparingDebug(false);
    }
  }, [message, persist, showDebugExecution, skillId, t]);

  const watchMockTestRun = useCallback(async (initial: SkillMockTestRun) => {
    let current = initial;
    let consecutiveErrors = 0;
    const watchToken = ++mockTestRunWatchToken.current;
    setMockTestRunPolling(true);
    try {
      for (let index = 0; index < 600 && watchToken === mockTestRunWatchToken.current; index += 1) {
        if (current.status !== 'RUNNING') break;
        await new Promise((resolve) => window.setTimeout(
          resolve,
          consecutiveErrors > 0 ? 1200 : 500,
        ));
        try {
          current = await loadDraftMockTestRun(skillId, current.id);
          if (watchToken !== mockTestRunWatchToken.current) return current;
          setMockTestRun(current);
          consecutiveErrors = 0;
        } catch (error) {
          consecutiveErrors += 1;
          if (consecutiveErrors >= 5) {
            message.warning(resolveApiErrorMessage(
              error,
              t('ai.skills.designer.tests.runAll.refreshFailed', '批量回归状态自动刷新已暂停，可从最近批次重新打开'),
            ));
            break;
          }
        }
      }
      if (current.status !== 'RUNNING') {
        await queryClient.invalidateQueries({
          queryKey: scopedQueryKey(queryScope, ['ai-skill-designer-mock-test-runs', skillId]),
        });
      }
      return current;
    } finally {
      if (watchToken === mockTestRunWatchToken.current) setMockTestRunPolling(false);
    }
  }, [message, queryClient, queryScope, skillId, t]);

  const runAllMockTestCases = useCallback(async (
    nextDocument: SkillDesignerDocument,
  ) => {
    setPreparingDebug(true);
    try {
      const saved = await persist(true, nextDocument);
      if (!saved) return;
      const result = await validateDraft(skillId);
      setCompilation(result);
      setDraft({...saved, compilation: result});
      if (!result.valid) {
        message.warning(t(
          'ai.skills.designer.debug.invalid',
          '请先修复草稿校验错误，再启动调试',
        ));
        return;
      }
      const started = await startDraftMockTestRun(skillId, saved.revision);
      setMockTestRun(started);
      await queryClient.invalidateQueries({
        queryKey: scopedQueryKey(queryScope, ['ai-skill-designer-mock-test-runs', skillId]),
      });
      message.success(t(
        'ai.skills.designer.tests.runAll.started',
        '已提交 {count} 个启用用例',
        {count: started.totalCount},
      ));
      void watchMockTestRun(started).then((completed) => {
        if (completed.status === 'PASSED') {
          message.success(t(
            'ai.skills.designer.tests.runAll.passed',
            '批量回归通过：{passed}/{total}',
            {passed: completed.passedCount, total: completed.totalCount},
          ));
        } else if (completed.status === 'FAILED') {
          message.error(t(
            'ai.skills.designer.tests.runAll.failed',
            '批量回归完成，{failed} 个用例未通过',
            {failed: completed.failedCount},
          ));
        }
      });
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.tests.runAll.submitFailed', '批量回归提交失败'),
      ));
    } finally {
      setPreparingDebug(false);
    }
  }, [message, persist, queryClient, queryScope, skillId, t, watchMockTestRun]);

  const openMockTestRun = useCallback(async (testRunId: string) => {
    try {
      const run = await loadDraftMockTestRun(skillId, testRunId);
      setMockTestRun(run);
      if (run.status === 'RUNNING') void watchMockTestRun(run);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.tests.runAll.loadFailed', '无法加载批量回归记录'),
      ));
    }
  }, [message, skillId, t, watchMockTestRun]);

  const openMockTestRunExecution = useCallback(async (executionId: string) => {
    try {
      const detail = await loadDraftDebugExecution(skillId, executionId);
      setDebugExecution(detail);
      setDebugNodeStatuses(deriveDebugNodeStatuses(detail));
      if (!terminalDebugStatuses.includes(detail.status)) void watchDebugExecution(detail);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.loadFailed', '无法加载调试执行详情'),
      ));
    }
  }, [message, skillId, t, watchDebugExecution]);

  const pauseDebugExecution = useCallback(async () => {
    if (!debugExecution) return;
    setDebugControlLoading(true);
    try {
      const next = await pauseDraftDebugExecution(skillId, debugExecution.id);
      setDebugExecution(next);
      setDebugNodeStatuses(deriveDebugNodeStatuses(next));
      message.success(next.status === 'PAUSED'
        ? t('ai.skills.designer.debug.control.paused', '调试已在安全检查点暂停')
        : t('ai.skills.designer.debug.control.pauseRequested', '暂停请求已提交，将在当前外部调用完成后的安全检查点生效'));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.control.pauseFailed', '暂停调试失败'),
      ));
    } finally {
      setDebugControlLoading(false);
    }
  }, [debugExecution, message, skillId, t]);

  const continueDebugExecution = useCallback(async () => {
    if (!debugExecution) return;
    setDebugControlLoading(true);
    try {
      const next = await continueDraftDebugExecution(skillId, debugExecution.id);
      setDebugExecution(next);
      setDebugNodeStatuses(deriveDebugNodeStatuses(next));
      void watchDebugExecution(next);
      message.success(t('ai.skills.designer.debug.control.continued', '调试已继续执行'));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.control.continueFailed', '继续调试失败'),
      ));
    } finally {
      setDebugControlLoading(false);
    }
  }, [debugExecution, message, skillId, t, watchDebugExecution]);

  const requestCancelDebugExecution = useCallback(() => {
    if (!debugExecution) return;
    modal.confirm({
      title: t('ai.skills.designer.debug.control.cancelTitle', '取消本次调试？'),
      content: t(
        'ai.skills.designer.debug.control.cancelDescription',
        '未开始的步骤会被跳过；正在进行的外部调用不会被强制中断，将在返回后的安全检查点终止。',
      ),
      okText: t('ai.skills.designer.debug.control.cancel', '取消调试'),
      okButtonProps: {danger: true},
      onOk: async () => {
        setDebugControlLoading(true);
        try {
          const next = await cancelDraftDebugExecution(skillId, debugExecution.id);
          setDebugExecution(next);
          setDebugNodeStatuses(deriveDebugNodeStatuses(next));
          if (next.status === 'RUNNING') void watchDebugExecution(next);
          message.success(next.status === 'CANCELLED'
            ? t('ai.skills.designer.debug.control.cancelled', '调试已取消')
            : t('ai.skills.designer.debug.control.cancelRequested', '取消请求已提交，等待安全检查点'));
        } catch (error) {
          message.error(resolveApiErrorMessage(
            error,
            t('ai.skills.designer.debug.control.cancelFailed', '取消调试失败'),
          ));
          throw error;
        } finally {
          setDebugControlLoading(false);
        }
      },
    });
  }, [debugExecution, message, modal, skillId, t, watchDebugExecution]);

  const openBreakpoints = useCallback(() => {
    if (!debugExecution) return;
    setSelectedBreakpointIds(debugExecution.breakpointStepIds ?? []);
    setBreakpointsOpen(true);
  }, [debugExecution]);

  const saveBreakpoints = useCallback(async () => {
    if (!debugExecution) return;
    setDebugControlLoading(true);
    try {
      const next = await setDraftDebugBreakpoints(
        skillId,
        debugExecution.id,
        selectedBreakpointIds,
      );
      setDebugExecution(next);
      setBreakpointsOpen(false);
      message.success(t(
        'ai.skills.designer.debug.control.breakpointsSaved',
        '已保存 {count} 个安全断点',
        {count: selectedBreakpointIds.length},
      ));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.control.breakpointsFailed', '保存断点失败'),
      ));
    } finally {
      setDebugControlLoading(false);
    }
  }, [debugExecution, message, selectedBreakpointIds, skillId, t]);

  const openDebugHistoryExecution = useCallback(async (execution: SkillDebugExecution) => {
    setDebugHistoryLoadingId(execution.id);
    try {
      const detail = await loadDraftDebugExecution(skillId, execution.id);
      setDebugHistoryOpen(false);
      setDebugExecution(detail);
      setDebugNodeStatuses(deriveDebugNodeStatuses(detail));
      void watchDebugExecution(detail);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.debug.loadFailed', '无法加载调试执行详情'),
      ));
    } finally {
      setDebugHistoryLoadingId(undefined);
    }
  }, [message, skillId, t, watchDebugExecution]);

  const selectedNode = useMemo(
    () => document?.nodes.find((node) => node.id === selectedNodeId),
    [document?.nodes, selectedNodeId],
  );
  const assertionMessage = useCallback((value: string) => {
    const messages: Record<string, [string, string]> = {
      'Assertion passed': ['ai.skills.designer.tests.assertion.result.passed', '断言通过'],
      'Path does not exist': ['ai.skills.designer.tests.assertion.result.pathMissing', '字段路径不存在'],
      'Path exists': ['ai.skills.designer.tests.assertion.result.pathExists', '字段路径实际存在'],
      'Actual value does not equal expected value': ['ai.skills.designer.tests.assertion.result.notEqual', '实际值与期望值不相等'],
      'Actual value equals the excluded value': ['ai.skills.designer.tests.assertion.result.equalExcluded', '实际值等于排除值'],
      'Actual value does not contain expected value': ['ai.skills.designer.tests.assertion.result.notContains', '实际值不包含期望值'],
      'MATCHES requires string actual and expected values': ['ai.skills.designer.tests.assertion.result.matchesString', 'MATCHES 的实际值和期望值必须都是字符串'],
      'Regular expression is invalid or unsafe': ['ai.skills.designer.tests.assertion.result.regexUnsafe', '正则表达式无效或不安全'],
      'Actual value does not match the regular expression': ['ai.skills.designer.tests.assertion.result.notMatches', '实际值与正则表达式不匹配'],
    };
    const localized = messages[value];
    return localized ? t(localized[0], localized[1]) : value;
  }, [t]);
  const diagnostics = compilation?.diagnostics ?? [];
  const errorCount = diagnostics.filter((item) => item.severity === 'ERROR').length;
  const displayedManifest = readOnly ? versionQuery.data?.manifest : compilation?.manifest;

  if (!skillId) {
    return (
      <Empty description={t('ai.skills.designer.missingSkill', '缺少 skillId，请从 Skill 列表进入可视化设计器')}>
        <Button type="primary" onClick={() => navigate('/ai/workbench/skills')}>{t('ai.skills.designer.action.backToList', '返回 Skill 列表')}</Button>
      </Empty>
    );
  }

  if (skillQuery.isLoading || (readOnly ? versionQuery.isLoading : draftQuery.isLoading || !document)) {
    return <Skeleton active paragraph={{rows: 12}} />;
  }

  if (skillQuery.isError || (readOnly ? versionQuery.isError : draftQuery.isError)) {
    const error = skillQuery.error ?? (readOnly ? versionQuery.error : draftQuery.error);
    return (
      <Alert
        type="error"
        showIcon
        message={t('ai.skills.designer.loadFailed', 'Skill Designer 加载失败')}
        description={resolveApiErrorMessage(
          error,
          t('ai.skills.designer.error.load', '无法加载 Skill 或草稿'),
        )}
        action={<Button onClick={() => void Promise.all([
          skillQuery.refetch(),
          readOnly ? versionQuery.refetch() : draftQuery.refetch(),
        ])}>{t('action.retry', '重试')}</Button>}
      />
    );
  }

  if (readOnly && versionQuery.data && (!versionQuery.data.compatible || !versionQuery.data.document)) {
    return (
      <div style={{padding: 24}}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/ai/workbench/skills')}>{t('ai.skills.designer.action.backToList', '返回 Skill 列表')}</Button>
        <Alert
          type="warning"
          showIcon
          style={{marginTop: 16}}
          message={t('ai.skills.designer.compatibility.title', '版本 {version} 暂不兼容当前 Designer', {version: versionQuery.data.version})}
          description={versionQuery.data.compatibilityMessage ?? t('ai.skills.designer.compatibility.description', '该 Manifest 使用了当前设计器未知的结构，平台不会静默丢失字段。')}
        />
        <Typography.Paragraph copyable={{text: JSON.stringify(versionQuery.data.manifest, null, 2)}} style={{marginTop: 16}}>
          {t('ai.skills.designer.compatibility.preserved', '原始不可变 Manifest 已完整保留，可复制查看；升级 Designer 适配器后即可生成只读画布。')}
        </Typography.Paragraph>
        <pre style={{padding: 12, borderRadius: 8, overflow: 'auto', background: '#f5f5f5'}}>
          {JSON.stringify(versionQuery.data.manifest, null, 2)}
        </pre>
      </div>
    );
  }

  if (!document) return <Skeleton active paragraph={{rows: 12}} />;

  return (
    <ReactFlowProvider>
      <div className="skill-designer-page">
        {toolbarCollapsed ? (
          <Tooltip title={t('ai.skills.designer.layout.expandToolbar', '展开顶部工具栏')}>
            <Button
              className="skill-designer-toolbar-restore"
              type="primary"
              shape="circle"
              icon={<MenuUnfoldOutlined />}
              aria-label={t('ai.skills.designer.layout.expandToolbar', '展开顶部工具栏')}
              onClick={() => setToolbarCollapsed(false)}
            />
          </Tooltip>
        ) : <div className="skill-designer-toolbar">
          <Space wrap>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/ai/workbench/skills')}>{t('ai.skills.designer.action.backToList', '返回 Skill 列表')}</Button>
            <span>
              <Title level={5} style={{margin: 0}}>{skillQuery.data?.name}</Title>
              <Text type="secondary">
                {readOnly
                  ? t(
                    'ai.skills.designer.header.version',
                    '{code} · 版本 {version}',
                    {code: skillQuery.data?.code, version: versionQuery.data?.version},
                  )
                  : t(
                    'ai.skills.designer.header.revision',
                    '{code} · 修订 {revision}',
                    {code: skillQuery.data?.code, revision: draft?.revision ?? 0},
                  )}
              </Text>
            </span>
            <span aria-live="polite" aria-atomic="true">
              {readOnly ? <Tag color="blue">{t('ai.skills.designer.status.readOnly', '不可变版本 · 只读')}</Tag> : (
                <Tag color={dirty ? 'gold' : 'green'}>{dirty ? t('ai.skills.designer.status.unsaved', '未保存') : t('ai.skills.designer.status.saved', '已保存')}</Tag>
              )}
              {!readOnly && saving && <Tag color="processing">{t('ai.skills.designer.status.autoSaving', '自动保存中')}</Tag>}
              {!readOnly && autoSaveError && <Tooltip title={autoSaveError}><Tag color="error">{t('ai.skills.designer.status.autoSaveFailed', '自动保存失败')}</Tag></Tooltip>}
              {!readOnly && compilation && (
                <Tag color={compilation.valid ? 'success' : 'error'} icon={compilation.valid ? <CheckCircleOutlined /> : <WarningOutlined />}>
                  {compilation.valid
                    ? t('ai.skills.designer.status.valid', '校验通过')
                    : t('ai.skills.designer.status.errorCount', '{count} 个错误', {count: errorCount})}
                </Tag>
              )}
            </span>
          </Space>
          <Space wrap className="skill-designer-toolbar-actions">
            {!readOnly && (
              <>
                <Tooltip title={t('common.undo', '撤销')}>
                  <Button aria-label={t('common.undo', '撤销')} icon={<UndoOutlined />} disabled={past.length === 0} onClick={undo} />
                </Tooltip>
                <Tooltip title={t('common.redo', '重做')}>
                  <Button aria-label={t('common.redo', '重做')} icon={<RedoOutlined />} disabled={future.length === 0} onClick={redo} />
                </Tooltip>
                <Tooltip title={dirty ? t('ai.skills.designer.history.waitForSave', '请等待自动保存完成后查看历史') : undefined}>
                  <Button icon={<HistoryOutlined />} disabled={!draft || dirty} onClick={() => setHistoryOpen(true)}>{t('ai.skills.designer.history.title', 'Draft Revision 历史')}</Button>
                </Tooltip>
                <Button icon={<ExperimentOutlined />} onClick={() => setTestCasesOpen(true)}>
                  {t('ai.skills.designer.tests.toolbar', '测试用例')}
                </Button>
                <Button onClick={() => setDebugHistoryOpen(true)}>
                  {t('ai.skills.designer.debug.history', '调试记录')}
                </Button>
              </>
            )}
            <Button
              icon={<CodeOutlined />}
              disabled={!displayedManifest}
              onClick={() => setManifestOpen(true)}
            >
              {t('ai.skills.designer.manifest.open', '查看 Manifest')}
            </Button>
            <Button
              icon={<ApartmentOutlined />}
              onClick={() => void autoLayoutDocument(document).then(readOnly ? setDocument : changeDocument)}
            >
              {t('ai.skills.designer.action.autoLayout', '自动布局')}
            </Button>
            {readOnly ? (
              <Button
                type="primary"
                icon={<CopyOutlined />}
                loading={copyingVersion}
                disabled={draftQuery.isLoading || !versionQuery.data?.compatible}
                onClick={copyCurrentVersion}
              >{t('ai.skills.designer.action.createDraftFromVersion', '基于此版本创建 Draft')}</Button>
            ) : (
              <>
                <Button icon={<SaveOutlined />} loading={saving} disabled={!dirty && Boolean(draft)} onClick={() => void persist()}>
                  {t('ai.skills.designer.action.saveDraft', '保存草稿')}
                </Button>
                <Button loading={validating} onClick={() => void validate()}>
                  {t('ai.skills.designer.action.saveValidate', '保存并校验')}
                </Button>
                <Button
                  icon={<RocketOutlined />}
                  loading={preparingPublish}
                  disabled={saving || validating || preparingDebug}
                  onClick={() => void openPublish()}
                >
                  {t('ai.skills.designer.publish.action', '发布')}
                </Button>
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  loading={preparingDebug}
                  disabled={saving || validating}
                  onClick={() => void openDebugInput()}
                >{t('ai.skills.designer.action.liveDebug', 'LIVE 调试')}</Button>
              </>
            )}
            <Tooltip title={t('ai.skills.designer.layout.collapseToolbar', '收起顶部工具栏')}>
              <Button
                icon={<MenuFoldOutlined />}
                aria-label={t('ai.skills.designer.layout.collapseToolbar', '收起顶部工具栏')}
                onClick={() => setToolbarCollapsed(true)}
              />
            </Tooltip>
          </Space>
        </div>}

        <main className="skill-designer-canvas" aria-label={t('ai.skills.designer.canvas.title', 'Skill 工作流画布')}>
            <SkillDesignerCanvas
              document={document}
              diagnostics={diagnostics}
              readOnly={readOnly}
              selectedNodeId={selectedNodeId}
              debugNodeStatuses={debugNodeStatuses}
              onChange={changeDocument}
              onSelect={setSelectedNodeId}
            />
        </main>

        {!readOnly && (paletteCollapsed ? (
          <Tooltip title={t('ai.skills.designer.layout.expandPalette', '展开节点列表')} placement="right">
            <Button
              className="skill-designer-palette-restore"
              type="primary"
              shape="circle"
              icon={<DoubleRightOutlined />}
              aria-label={t('ai.skills.designer.layout.expandPalette', '展开节点列表')}
              onClick={() => setPaletteCollapsed(false)}
            />
          </Tooltip>
        ) : (
          <aside className="skill-designer-palette" aria-label={t('ai.skills.designer.palette.title', '节点面板')}>
            <div className="skill-designer-floating-panel-header">
              <Text strong>{t('ai.skills.designer.palette.nodes', '节点')}</Text>
              <Tooltip title={t('ai.skills.designer.layout.collapsePalette', '收起节点列表')}>
                <Button
                  type="text"
                  size="small"
                  icon={<DoubleLeftOutlined />}
                  aria-label={t('ai.skills.designer.layout.collapsePalette', '收起节点列表')}
                  onClick={() => setPaletteCollapsed(true)}
                />
              </Tooltip>
            </div>
            <div className="skill-designer-floating-panel-body">
              <SkillDesignerPalette onAdd={addNodeFromPalette} />
            </div>
          </aside>
        ))}

        {inspectorCollapsed ? (
          <Tooltip title={t('ai.skills.designer.layout.expandInspector', '展开节点配置')} placement="left">
            <Button
              className="skill-designer-inspector-restore"
              type="primary"
              shape="circle"
              icon={<DoubleLeftOutlined />}
              aria-label={t('ai.skills.designer.layout.expandInspector', '展开节点配置')}
              onClick={() => setInspectorCollapsed(false)}
            />
          </Tooltip>
        ) : (
          <aside className="skill-designer-inspector" aria-label={t('ai.skills.designer.inspector.title', '节点属性面板')}>
            <div className="skill-designer-floating-panel-header">
              <Text strong>{t('ai.skills.designer.inspector.title', '节点属性面板')}</Text>
              <Tooltip title={t('ai.skills.designer.layout.collapseInspector', '收起节点配置')}>
                <Button
                  type="text"
                  size="small"
                  icon={<DoubleRightOutlined />}
                  aria-label={t('ai.skills.designer.layout.collapseInspector', '收起节点配置')}
                  onClick={() => setInspectorCollapsed(true)}
                />
              </Tooltip>
            </div>
            <div className="skill-designer-floating-panel-body">
            {readOnly ? (
              <SkillDesignerReadOnlyInspector document={document} selectedNode={selectedNode} />
            ) : (
              <SkillDesignerInspector
                document={document}
                selectedNode={selectedNode}
                onChange={changeDocument}
                onClearSelection={() => setSelectedNodeId(undefined)}
                onSelectNode={setSelectedNodeId}
              />
            )}
            </div>
          </aside>
        )}

        {diagnosticsCollapsed ? (
          <Tooltip title={t('ai.skills.designer.layout.expandDiagnostics', '展开诊断')}>
            <Button
              className="skill-designer-diagnostics-restore"
              icon={<WarningOutlined />}
              aria-label={t('ai.skills.designer.layout.expandDiagnostics', '展开诊断')}
              onClick={() => setDiagnosticsCollapsed(false)}
            >{diagnostics.length}</Button>
          </Tooltip>
        ) : <section className="skill-designer-diagnostics" aria-label={t('ai.skills.designer.diagnostics.title', '诊断')} aria-live="polite">
          <div className="skill-designer-floating-panel-header">
            <Space size={6}>
              <Text strong>{t('ai.skills.designer.diagnostics.title', '诊断')}</Text>
              <Tag>{diagnostics.length}</Tag>
            </Space>
            <Tooltip title={t('ai.skills.designer.layout.collapseDiagnostics', '收起诊断')}>
              <Button
                type="text"
                size="small"
                icon={<MenuFoldOutlined />}
                aria-label={t('ai.skills.designer.layout.collapseDiagnostics', '收起诊断')}
                onClick={() => setDiagnosticsCollapsed(true)}
              />
            </Tooltip>
          </div>
          <div className="skill-designer-diagnostics-body">
          {readOnly ? (
            <Alert
              type="info"
              closable
              showIcon
              message={t('ai.skills.designer.readOnly.title', '当前画布来自不可变 Skill Version，仅用于查看')}
              description={t('ai.skills.designer.readOnly.description', '移动、连线、删除和属性编辑均已禁用；创建 Draft 会生成新的可恢复 Revision，不会修改此版本。')}
            />
          ) : <>{diagnostics.length === 0 ? (
            <Text type="secondary">{t('ai.skills.designer.diagnostics.empty', '保存并校验后，这里会显示可定位到节点和字段的诊断结果。')}</Text>
          ) : diagnostics.map((item, index) => (
            <Alert
              key={`${item.code}-${item.jsonPointer ?? index}`}
              type={item.severity === 'ERROR' ? 'error' : item.severity === 'WARNING' ? 'warning' : 'info'}
              showIcon
              message={skillDesignerDiagnosticLabel(t, item.code)}
              description={[
                item.nodeId && t(
                  'ai.skills.designer.diagnostics.node',
                  '节点 {id}',
                  {id: item.nodeId},
                ),
                skillDesignerDiagnosticLocationLabel(t, item.fieldPath),
              ].filter(Boolean).join(' · ')}
              action={item.nodeId ? <Button size="small" onClick={() => setSelectedNodeId(item.nodeId ?? undefined)}>{t('ai.skills.designer.diagnostics.locate', '定位')}</Button> : undefined}
              style={{marginBottom: 6}}
            />
          ))}</>}
          </div>
        </section>}
      </div>
      <Drawer
        open={historyOpen}
        width={620}
        title={t('ai.skills.designer.history.title', 'Draft Revision 历史')}
        onClose={() => setHistoryOpen(false)}
      >
        <Alert
          type="info"
          closable
          showIcon
          message={t('ai.skills.designer.history.description', '每次保存都会产生不可变快照；恢复操作也会创建新 Revision。')}
          style={{marginBottom: 12}}
        />
        <DataTable<SkillDraftRevision>
          rowKey="id"
          size="small"
          loading={revisionsQuery.isLoading || restoring}
          dataSource={revisionsQuery.data?.content ?? []}
          pagination={false}
          columns={[
            {title: t('ai.skills.designer.common.revision', '修订'), dataIndex: 'revision', width: 90},
            {
              title: t('ai.skills.designer.history.source', '来源'),
              dataIndex: 'source',
              width: 100,
              render: (source: SkillDraftRevision['source']) => source ? (
                <Tag>{skillDraftRevisionSourceLabel(t, source)}</Tag>
              ) : '-',
            },
            {
              title: t('ai.skills.column.status', '状态'),
              dataIndex: 'validationStatus',
              width: 90,
              render: (status: SkillDraftRevision['validationStatus']) => (
                <Tag color={status === 'VALID' ? 'success' : 'error'}>
                  {skillValidationStatusLabel(t, status)}
                </Tag>
              ),
            },
            {
              title: t('ai.skills.designer.history.operator', '操作人'),
              dataIndex: 'createdBy',
              width: 120,
              render: (value?: string | null) => value || '-',
            },
            {
              title: t('ai.skills.designer.history.savedAt', '保存时间'),
              dataIndex: 'createdAt',
              render: (value: string) => new Date(value).toLocaleString(),
            },
            {
              title: t('ai.skills.column.action', '操作'),
              width: 80,
              render: (_: unknown, revision: SkillDraftRevision) => (
                <Button type="link" disabled={revision.revision === draft?.revision} onClick={() => restoreRevision(revision)}>
                  {t('ai.skills.designer.action.restore', '恢复')}
                </Button>
              ),
            },
          ]}
        />
      </Drawer>
      <Drawer
        open={manifestOpen}
        width={720}
        title={readOnly ? t('ai.skills.designer.manifest.versionTitle', '不可变 Skill Version Manifest') : t('ai.skills.designer.manifest.canonicalTitle', '服务端 Canonical Skill Manifest')}
        onClose={() => setManifestOpen(false)}
      >
        <Alert
          type={readOnly ? 'info' : 'success'}
          showIcon
          message={readOnly ? t('ai.skills.designer.manifest.readOnly', '此 Manifest 来自不可变版本，查看不会修改 Artifact。') : t('ai.skills.designer.manifest.canonical', '此预览由服务端权威编译器生成，画布不会直接作为执行协议。')}
          description={!readOnly && compilation?.contentHash ? t(
            'ai.skills.designer.manifest.contentHash',
            '内容 Hash：{hash}',
            {hash: compilation.contentHash},
          ) : undefined}
          style={{marginBottom: 12}}
        />
        <Typography.Paragraph copyable={{text: JSON.stringify(displayedManifest ?? {}, null, 2)}}>
          {t('ai.skills.designer.manifest.copy', '复制 Manifest')}
        </Typography.Paragraph>
        <pre style={{padding: 12, borderRadius: 8, overflow: 'auto', background: '#f5f5f5'}}>
          {JSON.stringify(displayedManifest ?? {}, null, 2)}
        </pre>
      </Drawer>
      <Drawer
        open={debugHistoryOpen}
        width={760}
        title={t('ai.skills.designer.debug.history', '调试记录')}
        onClose={() => setDebugHistoryOpen(false)}
      >
        {debugHistoryQuery.isError ? (
          <Alert
            type="error"
            showIcon
            message={resolveApiErrorMessage(
              debugHistoryQuery.error,
              t('ai.skills.designer.debug.historyLoadFailed', '无法加载调试记录'),
            )}
            action={<Button onClick={() => void debugHistoryQuery.refetch()}>{t('action.retry', '重试')}</Button>}
          />
        ) : (
          <DataTable<SkillDebugExecution>
            rowKey="id"
            size="small"
            loading={debugHistoryQuery.isLoading}
            dataSource={debugHistoryQuery.data?.content ?? []}
            pagination={false}
            locale={{emptyText: t('ai.skills.designer.debug.historyEmpty', '暂无调试记录')}}
            columns={[
              {title: t('ai.skills.designer.common.revision', '修订'), dataIndex: 'draftRevision', width: 90},
              {
                title: t('ai.skills.designer.debug.mode', '模式'),
                dataIndex: 'debugMode',
                width: 90,
                render: (mode: SkillDebugExecution['debugMode']) => (
                  <Tag color={mode === 'MOCK' ? 'purple' : 'blue'}>
                    {skillDebugModeLabel(t, mode)}
                  </Tag>
                ),
              },
              {
                title: t('ai.skills.column.status', '状态'),
                dataIndex: 'status',
                width: 130,
                render: (status: SkillDebugExecution['status']) => (
                  <Tag color={debugStatusColor(status)}>
                    {skillDebugExecutionStatusLabel(t, status)}
                  </Tag>
                ),
              },
              {
                title: t('ai.skills.designer.debug.startedAt', '提交时间'),
                dataIndex: 'createdAt',
                render: (value?: string) => value ? new Date(value).toLocaleString() : '-',
              },
              {
                title: t('ai.skills.designer.debug.completedAt', '完成时间'),
                dataIndex: 'completedAt',
                render: (value?: string) => value ? new Date(value).toLocaleString() : '-',
              },
              {
                title: t('ai.skills.designer.debug.action', '操作'),
                width: 90,
                render: (_: unknown, execution: SkillDebugExecution) => (
                  <Button
                    type="link"
                    loading={debugHistoryLoadingId === execution.id}
                    onClick={() => void openDebugHistoryExecution(execution)}
                  >
                    {t('ai.skills.designer.debug.view', '查看')}
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Drawer>
      <SkillDesignerTestCases
        open={testCasesOpen}
        document={document}
        running={preparingDebug}
        runPolling={mockTestRunPolling}
        testRun={mockTestRun}
        testRunHistory={mockTestRunHistoryQuery.data?.content ?? []}
        testRunHistoryLoading={mockTestRunHistoryQuery.isLoading}
        testRunHistoryError={Boolean(mockTestRunHistoryQuery.error)}
        onClose={() => setTestCasesOpen(false)}
        onChange={changeDocument}
        onRun={runMockTestCase}
        onRunAll={runAllMockTestCases}
        onOpenTestRun={openMockTestRun}
        onOpenExecution={openMockTestRunExecution}
      />
      {publishOpen && draft && compilation?.valid && compilation.manifest && compilation.contentHash && (
        <SkillPublishDrawer
          open={publishOpen}
          skillId={skillId}
          draft={draft}
          document={document}
          compilation={compilation}
          onClose={() => setPublishOpen(false)}
        />
      )}
      <Modal
        open={debugInputOpen}
        title={t(
          'ai.skills.designer.debug.inputTitle',
          'LIVE 调试 · Draft Revision {revision}',
          {revision: debugRevision ?? '-'},
        )}
        footer={null}
        destroyOnHidden
        onCancel={() => {
          if (!debugSubmitting) setDebugInputOpen(false);
        }}
      >
        <Alert
          type="info"
          showIcon
          closable
          message={t(
            'ai.skills.designer.debug.liveNotice',
            'LIVE 调试会调用当前 Revision 固定的真实 MCP 能力',
          )}
          description={t(
            'ai.skills.designer.debug.pinnedNotice',
            '执行启动后会固化能力快照和描述哈希；后续修改 Draft 不会改变本次执行。',
          )}
          style={{marginBottom: 16}}
        />
        <SchemaExecutionForm
          schema={document.inputSchema}
          submitting={debugSubmitting}
          submitText={t('ai.skills.designer.debug.submit', '启动 LIVE 调试')}
          onSubmit={runDebugExecution}
        />
      </Modal>
      <Drawer
        open={Boolean(debugExecution)}
        width={760}
        title={t('ai.skills.designer.debug.details', 'Draft 调试执行详情')}
        extra={debugPolling ? <Tag color="processing">{t('ai.skills.designer.tests.runAll.refreshing', '正在刷新执行状态…')}</Tag> : undefined}
        onClose={() => {
          debugWatchToken.current += 1;
          setDebugPolling(false);
          setDebugExecution(undefined);
          setDebugNodeStatuses({});
        }}
      >
        {debugExecution && (
          <>
            <Space wrap style={{marginBottom: 16}}>
              <Button
                icon={<AimOutlined />}
                loading={debugControlLoading && breakpointsOpen}
                disabled={terminalDebugStatuses.includes(debugExecution.status)}
                onClick={openBreakpoints}
              >
                {t('ai.skills.designer.debug.control.breakpoints', '安全断点')}
                {debugExecution.breakpointStepIds?.length
                  ? ` (${debugExecution.breakpointStepIds.length})` : ''}
              </Button>
              {(debugExecution.status === 'PENDING' || debugExecution.status === 'RUNNING') && (
                <Button
                  icon={<PauseCircleOutlined />}
                  loading={debugControlLoading}
                  disabled={Boolean(debugExecution.cancelRequested || debugExecution.pauseRequested)}
                  onClick={() => void pauseDebugExecution()}
                >
                  {t('ai.skills.designer.debug.control.pause', '暂停')}
                </Button>
              )}
              {debugExecution.status === 'PAUSED' && (
                <Button
                  type="primary"
                  icon={<CaretRightOutlined />}
                  loading={debugControlLoading}
                  onClick={() => void continueDebugExecution()}
                >
                  {t('ai.skills.designer.debug.control.continue', '继续')}
                </Button>
              )}
              {!terminalDebugStatuses.includes(debugExecution.status) && (
                <Button
                  danger
                  icon={<StopOutlined />}
                  loading={debugControlLoading}
                  disabled={Boolean(debugExecution.cancelRequested)}
                  onClick={requestCancelDebugExecution}
                >
                  {t('ai.skills.designer.debug.control.cancel', '取消调试')}
                </Button>
              )}
            </Space>
            {debugExecution.cancelRequested && debugExecution.status === 'RUNNING' && (
              <Alert
                type="info"
                showIcon
                closable
                message={t('ai.skills.designer.debug.control.cancelPending', '正在等待安全取消点')}
                description={t(
                  'ai.skills.designer.debug.control.cancelPendingDescription',
                  '当前外部调用不会被强制中断；调用完成后执行将转为 CANCELLED，后续步骤不会启动。',
                )}
                style={{marginBottom: 16}}
              />
            )}
            {debugExecution.status === 'WAITING_APPROVAL' && (
              <Alert
                type="warning"
                showIcon
                closable
                message={t(
                  'ai.skills.designer.debug.awaitingApproval',
                  '调试执行正在等待审批，可在 Skill 执行记录中处理',
                )}
                description={debugExecution.approvalInstructions}
                action={(
                  <Button onClick={() => navigate('/ai/workbench/skills')}>
                    {t('ai.skills.designer.debug.openExecutions', '前往执行记录')}
                  </Button>
                )}
                style={{marginBottom: 16}}
              />
            )}
            {debugExecution.errorMessage && (
              <Alert
                type="error"
                showIcon
                closable
                message={debugExecution.errorCode ?? t(
                  'ai.skills.designer.debug.failed',
                  'Draft 调试执行失败',
                )}
                description={debugExecution.errorCode === 'SKILL_MOCK_ASSERTION_FAILED'
                  ? t('ai.skills.designer.debug.assertionFailed', '一个或多个 MOCK 测试断言未通过，请查看下方明细')
                  : debugExecution.errorMessage}
                style={{marginBottom: 16}}
              />
            )}
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.skills.execution.id', '执行 ID')} span={2}>
                <Text copyable>{debugExecution.id}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.common.revision', '修订')}>
                {debugExecution.draftRevision}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.debug.mode', '模式')}>
                <Tag color={debugExecution.debugMode === 'MOCK' ? 'purple' : 'blue'}>
                  {skillDebugModeLabel(t, debugExecution.debugMode)}
                </Tag>
              </Descriptions.Item>
              {debugExecution.testCaseId && (
                <Descriptions.Item label={t('ai.skills.designer.debug.testCase', '测试用例')}>
                  <Text copyable>{debugExecution.testCaseId}</Text>
                </Descriptions.Item>
              )}
              <Descriptions.Item label={t('ai.skills.column.status', '状态')}>
                <Tag color={debugStatusColor(debugExecution.status)}>
                  {skillDebugExecutionStatusLabel(t, debugExecution.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.currentStep', '当前步骤')}>
                {debugExecution.currentStepId ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.attempts', '执行次数')}>
                {debugExecution.attemptCount}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.common.contentHash', '内容 Hash')} span={2}>
                <Text copyable>{debugExecution.draftContentHash}</Text>
              </Descriptions.Item>
              {debugExecution.mockConfigHash && (
                <Descriptions.Item label={t('ai.skills.designer.common.mockConfigHash', 'Mock 配置 Hash')} span={2}>
                  <Text copyable>{debugExecution.mockConfigHash}</Text>
                </Descriptions.Item>
              )}
            </Descriptions>
            {debugExecution.debugMode === 'MOCK' && debugExecution.assertionsPassed !== null
              && debugExecution.assertionsPassed !== undefined && (
              <Alert
                type={debugExecution.assertionsPassed ? 'success' : 'error'}
                showIcon
                message={debugExecution.assertionsPassed
                  ? t('ai.skills.designer.tests.assertion.allPassed', '全部断言通过')
                  : t('ai.skills.designer.tests.assertion.someFailed', '存在未通过的断言')}
                description={t(
                  'ai.skills.designer.tests.assertion.summary',
                  '共 {count} 条，{passed} 条通过',
                  {
                    count: debugExecution.assertionResults?.length ?? 0,
                    passed: debugExecution.assertionResults?.filter((item) => item.passed).length ?? 0,
                  },
                )}
                style={{marginTop: 16}}
              />
            )}
            {debugExecution.assertionResults && debugExecution.assertionResults.length > 0 && (
              <DataTable
                rowKey="id"
                size="small"
                pagination={false}
                style={{marginTop: 16}}
                dataSource={debugExecution.assertionResults}
                columns={[
                  {title: t('ai.skills.designer.tests.assertion.id', '断言 ID'), dataIndex: 'id', width: 120},
                  {
                    title: t('ai.skills.designer.tests.assertion.source', '结果来源'),
                    render: (_: unknown, result) => (
                      <Text>{result.sourceNodeId}{result.fieldPath ? ` · ${result.fieldPath}` : ''}</Text>
                    ),
                  },
                  {title: t('ai.skills.designer.tests.assertion.operator', '运算符'), dataIndex: 'operator', width: 110},
                  {
                    title: t('ai.skills.designer.tests.assertion.result', '结果'),
                    width: 220,
                    render: (_: unknown, result) => (
                      <Space direction="vertical" size={2}>
                        <Tag color={result.passed ? 'success' : 'error'}>
                          {result.passed
                            ? t('ai.skills.designer.tests.assertion.result.pass', '通过')
                            : t('ai.skills.designer.tests.assertion.result.fail', '未通过')}
                        </Tag>
                        <Text type="secondary">{assertionMessage(result.message)}</Text>
                      </Space>
                    ),
                  },
                ]}
                expandable={{
                  expandedRowRender: (result) => (
                    <Space direction="vertical" style={{width: '100%'}}>
                      <Text strong>{t('ai.skills.designer.tests.assertion.expected', '期望值（JSON）')}</Text>
                      <pre style={{margin: 0, whiteSpace: 'pre-wrap'}}>{JSON.stringify(result.expected, null, 2)}</pre>
                      <Text strong>{t('ai.skills.designer.tests.assertion.actual', '实际值（JSON）')}</Text>
                      <pre style={{margin: 0, whiteSpace: 'pre-wrap'}}>{JSON.stringify(result.actual, null, 2)}</pre>
                    </Space>
                  ),
                }}
              />
            )}
            <Space wrap style={{marginTop: 12}}>
              <Text type="secondary">{t('ai.skills.designer.debug.canvasLegend', '画布状态')}</Text>
              <Tag color="default">{skillDebugNodeStatusLabel(t, 'PENDING')}</Tag>
              <Tag color="processing">{skillDebugNodeStatusLabel(t, 'RUNNING')}</Tag>
              <Tag color="success">{skillDebugNodeStatusLabel(t, 'SUCCEEDED')}</Tag>
              <Tag color="error">{skillDebugNodeStatusLabel(t, 'FAILED')}</Tag>
              <Tag color="orange">{skillDebugNodeStatusLabel(t, 'WAITING_APPROVAL')}</Tag>
              <Tag color="purple">{skillDebugNodeStatusLabel(t, 'PAUSED')}</Tag>
              <Tag>{skillDebugNodeStatusLabel(t, 'SKIPPED')}</Tag>
            </Space>
            <DataTable
              rowKey="id"
              size="small"
              pagination={false}
              style={{marginTop: 16}}
              dataSource={debugExecution.steps ?? []}
              columns={[
                {title: t('ai.skills.execution.step', '步骤'), dataIndex: 'stepId'},
                {
                  title: t('ai.skills.execution.capabilityType', '能力类型'),
                  dataIndex: 'stepType',
                  width: 110,
                  render: (stepType: string) => skillStepTypeLabel(t, stepType),
                },
                {title: t('ai.skills.execution.capability', 'MCP 能力'), dataIndex: 'capabilityName'},
                {
                  title: t('ai.skills.column.status', '状态'),
                  dataIndex: 'status',
                  width: 110,
                  render: (status: SkillDebugNodeStatus) => (
                    <Tag>{skillDebugNodeStatusLabel(t, status)}</Tag>
                  ),
                },
                {
                  title: t('ai.skills.designer.debug.stepDuration', '耗时'),
                  width: 90,
                  render: (_: unknown, step) => stepDuration(step.startedAt, step.completedAt),
                },
                {
                  title: t('ai.skills.execution.attempts', '执行次数'),
                  dataIndex: 'attemptCount',
                  width: 90,
                },
              ]}
              expandable={{
                expandedRowRender: (step) => (
                  <Space direction="vertical" size={12} style={{width: '100%'}}>
                    {step.errorMessage && (
                      <Alert
                        type="error"
                        showIcon
                        message={step.errorCode ?? t('ai.skills.execution.error', '错误')}
                        description={step.errorMessage}
                      />
                    )}
                    <Descriptions bordered size="small" column={2}>
                      <Descriptions.Item label={t('ai.skills.designer.debug.stepAlias', '能力别名')}>
                        {step.capabilityAlias ?? '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.debug.stepBinding', '固定绑定')}>
                        <Text copyable={Boolean(step.bindingId)}>{step.bindingId ?? '-'}</Text>
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.capability.server', 'MCP Server')}>
                        <Text copyable={Boolean(step.mcpServerId)}>{step.mcpServerId ?? '-'}</Text>
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.capability.snapshot', '能力快照')}>
                        <Text copyable={Boolean(step.capabilitySnapshotId)}>
                          {step.capabilitySnapshotId ?? '-'}
                        </Text>
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.capability.schemaHash', 'Schema Hash')} span={2}>
                        <Text copyable={Boolean(step.capabilitySchemaHash)}>
                          {step.capabilitySchemaHash ?? '-'}
                        </Text>
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.debug.stepStartedAt', '开始时间')}>
                        {step.startedAt ? new Date(step.startedAt).toLocaleString() : '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.debug.stepCompletedAt', '结束时间')}>
                        {step.completedAt ? new Date(step.completedAt).toLocaleString() : '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.debug.stepDuration', '耗时')}>
                        {stepDuration(step.startedAt, step.completedAt)}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.skills.designer.debug.stepSchemaValidation', 'Schema 校验')}>
                        <Tag color={step.status === 'SUCCEEDED'
                          ? 'success'
                          : step.status === 'FAILED' ? 'error' : 'default'}>
                          {step.status === 'SUCCEEDED'
                            ? t('ai.skills.designer.debug.stepSchemaValid', '已通过')
                            : step.status === 'FAILED'
                              ? t('ai.skills.designer.debug.stepSchemaFailed', '未通过或执行失败')
                              : step.status === 'SKIPPED'
                                ? t('ai.skills.designer.debug.stepSchemaSkipped', '未执行')
                                : t('ai.skills.designer.debug.stepSchemaPending', '尚未校验')}
                        </Tag>
                      </Descriptions.Item>
                      {step.capabilityTokenIdHash && (
                        <Descriptions.Item label={t('ai.skills.designer.debug.stepTokenFingerprint', '能力令牌审计指纹')} span={2}>
                          <Text copyable>{step.capabilityTokenIdHash}</Text>
                        </Descriptions.Item>
                      )}
                    </Descriptions>
                    {[
                      [t('ai.skills.designer.debug.stepTemplate', '参数模板'), step.inputTemplate],
                      [t('ai.skills.designer.debug.stepInput', '解析后的实际输入'), step.input],
                      [t('ai.skills.designer.debug.stepOutput', 'MCP 响应 / Structured Content'), step.output],
                    ].map(([label, value]) => value === undefined || value === null ? null : (
                      <div key={String(label)}>
                        <Typography.Paragraph
                          copyable={{text: JSON.stringify(value, null, 2)}}
                          style={{marginBottom: 6}}
                        >
                          <Text strong>{String(label)}</Text>
                        </Typography.Paragraph>
                        <pre style={{padding: 12, borderRadius: 8, overflow: 'auto', background: '#f5f5f5'}}>
                          {JSON.stringify(value, null, 2)}
                        </pre>
                      </div>
                    ))}
                  </Space>
                ),
              }}
            />
            <Typography.Paragraph
              copyable={{text: JSON.stringify(debugExecution.output ?? {}, null, 2)}}
              style={{marginTop: 16, marginBottom: 8}}
            >
              {t('ai.skills.execution.output', '执行输出')}
            </Typography.Paragraph>
            <pre style={{padding: 12, borderRadius: 8, overflow: 'auto', background: '#f5f5f5'}}>
              {JSON.stringify(debugExecution.output ?? {}, null, 2)}
            </pre>
          </>
        )}
      </Drawer>
      <Modal
        open={breakpointsOpen}
        title={t('ai.skills.designer.debug.control.breakpointsTitle', '配置安全断点')}
        okText={t('ai.skills.designer.debug.control.breakpointsSave', '保存断点')}
        confirmLoading={debugControlLoading}
        onOk={() => void saveBreakpoints()}
        onCancel={() => {
          if (!debugControlLoading) setBreakpointsOpen(false);
        }}
      >
        <Alert
          type="info"
          showIcon
          message={t('ai.skills.designer.debug.control.breakpointsNotice', '断点只在步骤开始前暂停')}
          description={t(
            'ai.skills.designer.debug.control.breakpointsDescription',
            '继续后只跳过当前命中的断点一次；断点列表仍会保留，已经完成的步骤不会重新执行。',
          )}
          style={{marginBottom: 16}}
        />
        <Checkbox.Group
          style={{display: 'flex', flexDirection: 'column', gap: 10}}
          value={selectedBreakpointIds}
          options={(debugExecution?.steps ?? []).map((step) => ({
            label: `${step.stepId} · ${step.capabilityName ?? step.stepType}`,
            value: step.stepId,
            disabled: step.status === 'SUCCEEDED' || step.status === 'SKIPPED',
          }))}
          onChange={(values) => setSelectedBreakpointIds(values.map(String))}
        />
      </Modal>
    </ReactFlowProvider>
  );
};

export default SkillDesigner;
