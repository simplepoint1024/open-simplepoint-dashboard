import api from '@/api';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {isHttpError} from '@simplepoint/shared/api/client';
import type {Page} from '@simplepoint/shared/types/request';
import type {
  McpCapabilitySnapshotSummary,
  McpCapabilitySnapshotDetails,
  McpServerCapabilities,
  McpServerSummary,
  McpToolDescriptor,
  McpPromptDescriptor,
  McpResourceDescriptor,
  McpResourceTemplateDescriptor,
  SkillDesignerCompilation,
  SkillDesignerDocument,
  SkillDraftView,
  SkillDraftRevision,
  SkillDebugExecution,
  SkillDebugExecutionEventFeed,
  SkillMockTestRun,
  SkillManagedRegistryStatus,
  SkillPublishTask,
  SkillSummary,
  SkillVersionSummary,
  SkillVersionDesignerView,
} from './types';

const skillsApi = api['ai-workbench.skills'];
const mcpServersApi = api['ai-workbench.mcp-servers'];

export const skillUrl = (skillId: string) => `${skillsApi.baseUrl}/${skillId}`;
export const draftUrl = (skillId: string) => `${skillUrl(skillId)}/draft`;

export const loadSkill = (skillId: string, signal?: AbortSignal) => get<SkillSummary>(skillUrl(skillId), undefined, {signal});

// React Query treats an `undefined` query result as an invalid query function
// result. A missing draft is a valid state for a newly-created Skill, so use
// null as the explicit cache value and let the Designer create a local draft.
export const loadDraft = async (skillId: string, signal?: AbortSignal): Promise<SkillDraftView | null> => {
  try {
    // Some gateways normalize an empty 2xx body to `undefined`. Query
    // functions must never resolve undefined, so normalize both missing
    // drafts and empty successful responses to the explicit null state.
    return (await get<SkillDraftView>(draftUrl(skillId), undefined, {signal})) ?? null;
  } catch (error) {
    if (isHttpError(error) && error.status === 404) return null;
    throw error;
  }
};

export const saveDraft = (
  skillId: string,
  expectedRevision: number,
  document: SkillDesignerDocument,
) => put<SkillDraftView>(draftUrl(skillId), {expectedRevision, document});

export const validateDraft = (skillId: string) => post<SkillDesignerCompilation>(
  `${draftUrl(skillId)}/validate`,
  {},
);

export const loadDraftRevisions = (skillId: string, signal?: AbortSignal) => get<Page<SkillDraftRevision>>(
  `${draftUrl(skillId)}/revisions`,
  {page: 0, size: 100},
  {signal},
);

export const restoreDraftRevision = (
  skillId: string,
  revision: number,
  expectedRevision: number,
) => post<SkillDraftView>(
  `${draftUrl(skillId)}/revisions/${revision}/restore`,
  {expectedRevision},
);

export const startDraftDebugExecution = (
  skillId: string,
  revision: number,
  input: Record<string, unknown>,
  mode: 'LIVE' | 'MOCK' = 'LIVE',
  testCaseId?: string,
) => post<SkillDebugExecution>(
  `${draftUrl(skillId)}/debug-executions`,
  {
    revision,
    mode,
    testCaseId,
    idempotencyKey: globalThis.crypto?.randomUUID?.()
      ?? `${Date.now()}-${Math.random()}`,
    input,
  },
);

export const loadDraftDebugExecution = (
  skillId: string,
  executionId: string,
) => get<SkillDebugExecution>(
  `${draftUrl(skillId)}/debug-executions/${executionId}`,
);

export const loadDraftDebugExecutions = (skillId: string, signal?: AbortSignal) => get<Page<SkillDebugExecution>>(
  `${draftUrl(skillId)}/debug-executions`,
  {page: 0, size: 50, sort: 'createdAt,desc'},
  {signal},
);

export const loadDraftDebugEvents = (
  skillId: string,
  executionId: string,
  after = 0,
  limit = 100,
) => get<SkillDebugExecutionEventFeed>(
  `${draftUrl(skillId)}/debug-executions/${executionId}/events`,
  {afterSequence: after, limit},
);

export const pauseDraftDebugExecution = (
  skillId: string,
  executionId: string,
  reason?: string,
) => post<SkillDebugExecution>(
  `${draftUrl(skillId)}/debug-executions/${executionId}/pause`,
  {reason},
);

export const continueDraftDebugExecution = (
  skillId: string,
  executionId: string,
) => post<SkillDebugExecution>(
  `${draftUrl(skillId)}/debug-executions/${executionId}/continue`,
  {},
);

export const cancelDraftDebugExecution = (
  skillId: string,
  executionId: string,
  reason?: string,
) => post<SkillDebugExecution>(
  `${draftUrl(skillId)}/debug-executions/${executionId}/cancel`,
  {reason},
);

export const setDraftDebugBreakpoints = (
  skillId: string,
  executionId: string,
  stepIds: string[],
) => put<SkillDebugExecution>(
  `${draftUrl(skillId)}/debug-executions/${executionId}/breakpoints`,
  {stepIds},
);

export const startDraftMockTestRun = (
  skillId: string,
  revision: number,
) => post<SkillMockTestRun>(
  `${draftUrl(skillId)}/mock-test-runs`,
  {
    revision,
    idempotencyKey: globalThis.crypto?.randomUUID?.()
      ?? `${Date.now()}-${Math.random()}`,
  },
);

export const loadDraftMockTestRun = (
  skillId: string,
  testRunId: string,
) => get<SkillMockTestRun>(
  `${draftUrl(skillId)}/mock-test-runs/${testRunId}`,
);

export const loadDraftMockTestRuns = (skillId: string, signal?: AbortSignal) => get<Page<SkillMockTestRun>>(
  `${draftUrl(skillId)}/mock-test-runs`,
  {page: 0, size: 20},
  {signal},
);

export const loadVersionDesigner = (
  skillId: string,
  versionId: string,
  signal?: AbortSignal,
) => get<SkillVersionDesignerView>(
  `${skillUrl(skillId)}/versions/${versionId}/designer`,
  undefined,
  {signal},
);

export const loadManagedRegistry = () => get<SkillManagedRegistryStatus>(
  `${skillsApi.baseUrl}/managed-registry`,
);

export const checkManagedRegistry = () => post<SkillManagedRegistryStatus>(
  `${skillsApi.baseUrl}/managed-registry/connectivity-check`,
  {},
);

export const loadSkillVersions = (skillId: string) => get<Page<SkillVersionSummary>>(
  `${skillUrl(skillId)}/versions`,
  {page: 0, size: 100, sort: 'createdAt,desc'},
);

export const loadPublishTasks = (skillId: string) => get<Page<SkillPublishTask>>(
  `${draftUrl(skillId)}/publish-tasks`,
  {page: 0, size: 20, sort: 'createdAt,desc'},
);

export const loadPublishTask = (skillId: string, taskId: string) => get<SkillPublishTask>(
  `${draftUrl(skillId)}/publish-tasks/${taskId}`,
);

export const startPublishTask = (
  skillId: string,
  draftRevision: number,
  version: string,
  activate: boolean,
  idempotencyKey: string,
) => post<SkillPublishTask>(`${draftUrl(skillId)}/publish-tasks`, {
  draftRevision,
  version,
  activate,
  idempotencyKey,
});

export const retryPublishTask = (skillId: string, taskId: string) => post<SkillPublishTask>(
  `${draftUrl(skillId)}/publish-tasks/${taskId}/retry`,
  {},
);

export const copyVersionToDraft = (
  skillId: string,
  versionId: string,
  expectedRevision: number,
) => post<SkillDraftView>(
  `${skillUrl(skillId)}/versions/${versionId}/designer/copy-to-draft`,
  {expectedRevision},
);

export const loadMcpServers = async (signal?: AbortSignal) => {
  const page = await get<Page<McpServerSummary>>(mcpServersApi.baseUrl, {page: 0, size: 500}, {signal});
  return (page.content ?? []).filter((server) => server.enabled !== false && server.status === 'READY');
};

export const loadMcpServerCapabilities = async (serverId: string, signal?: AbortSignal): Promise<McpServerCapabilities> => {
  const [tools, prompts, resources, resourceTemplates, snapshots] = await Promise.all([
    get<McpToolDescriptor[]>(`${mcpServersApi.baseUrl}/${serverId}/tools`, undefined, {signal}),
    get<McpPromptDescriptor[]>(`${mcpServersApi.baseUrl}/${serverId}/prompts`, undefined, {signal}),
    get<McpResourceDescriptor[]>(`${mcpServersApi.baseUrl}/${serverId}/resources`, undefined, {signal}),
    get<McpResourceTemplateDescriptor[]>(`${mcpServersApi.baseUrl}/${serverId}/resource-templates`, undefined, {signal}),
    get<Page<McpCapabilitySnapshotSummary>>(
      `${mcpServersApi.baseUrl}/${serverId}/snapshots`,
      {page: 0, size: 100},
      {signal},
    ),
  ]);
  return {
    tools,
    prompts,
    resources,
    resourceTemplates,
    snapshot: snapshots.content?.find((snapshot) => snapshot.active),
  };
};

export const loadMcpCapabilitySnapshot = (
  serverId: string,
  snapshotId: string,
  signal?: AbortSignal,
) => get<McpCapabilitySnapshotDetails>(
  `${mcpServersApi.baseUrl}/${serverId}/snapshots/${snapshotId}`,
  undefined,
  {signal},
);
