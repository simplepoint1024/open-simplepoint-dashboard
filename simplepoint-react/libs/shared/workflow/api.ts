import {get, post, put} from '@simplepoint/shared/api/methods';
import type {WorkflowFormContract, WorkflowRuntimeForm} from '../../components/WorkflowDesigner/contracts';

const root = '/workflow';

export type WorkflowInboxItem = {
  id: string; taskId?: string; processInstanceId: string; rowType: 'TASK' | 'PROCESS_ACTION';
  title: string; definitionName: string; taskName?: string; starterName?: string;
  assigneeName?: string; actorName?: string; outcome: string; instanceStatus: string;
  createdAt: string; dueAt?: string; handledAt?: string; priority?: number; comment?: string;
  handledByMe: boolean; assigned: boolean; claimable: boolean; actionable: boolean;
};

export type WorkflowMatter = {
  id: string; definitionId: string; title: string; definitionName: string; definitionVersion: number;
  businessKey?: string; starterName?: string; status: string; startedAt: string; endedAt?: string;
  canWithdraw: boolean; canCancel: boolean; canResubmit: boolean;
  previousInstanceId?: string; nextInstanceId?: string;
};

export type WorkflowResubmission = {source: WorkflowMatter; form: WorkflowRuntimeForm; ccUserIds: string[]};
export type WorkflowAuditEvent = {id: string; actionType: string; actorName?: string; comment?: string;
  activityId?: string; activityName?: string; occurredAt: string};
export type WorkflowAuditTrail = {events: PageResult<WorkflowAuditEvent>; recordedFromStart: boolean};
export type WorkflowStartCommand = {idempotencyKey?: string; version?: number; businessKey?: string; title?: string;
  variables?: Record<string, unknown>; ccUserIds?: string[]};

export type PageResult<T> = {content: T[]; page: number; size: number; totalElements: number};
export type WorkflowOverview = {
  tenantWorkspace: boolean; canViewTodos: boolean; canViewMatters: boolean;
  pendingTodos: number; activeMatters: number;
  todos: WorkflowInboxItem[]; matters: WorkflowMatter[];
};
export type DefinitionPermissions = {
  canCreate: boolean;
  canEdit: boolean;
  canPublish: boolean;
  canCopyVersion: boolean;
  canStart: boolean;
};
export type RuntimePermissions = {
  canClaim: boolean;
  canComplete: boolean;
  canViewAllInstances: boolean;
  canCancelInstances: boolean;
  canStartInstances: boolean;
};
export type DefinitionSummary = {
  id: string;
  key: string;
  name: string;
  category?: string;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED' | string;
  latestVersion: number;
  draftRevision: number;
  unpublishedChanges: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};
export type VersionSummary = {
  id: string;
  version: number;
  checksum: string;
  comment?: string;
  flowableProcessDefinitionId: string;
  publishedBy: string;
  publishedAt: string;
};
export type DefinitionDetail = {
  definition: DefinitionSummary;
  bpmnXml: string;
  versions: VersionSummary[];
  permissions: DefinitionPermissions;
  formContract: WorkflowFormContract;
};
export type VersionDetail = {version: VersionSummary; bpmnXml: string; name?: string;
  category?: string; description?: string; formContract: WorkflowFormContract};
export type ProcessInstance = {
  id: string;
  definitionId: string;
  definitionKey: string;
  definitionName: string;
  definitionVersion: number;
  businessKey?: string;
  title: string;
  starterId: string;
  status: 'RUNNING' | 'COMPLETED' | 'CANCELED' | string;
  startedAt: string;
  endedAt?: string;
};
export type TaskSummary = {
  id: string;
  processInstanceId: string;
  definitionKey?: string;
  definitionName?: string;
  name: string;
  taskDefinitionKey: string;
  assignee?: string;
  owner?: string;
  createdAt: string;
  dueAt?: string;
  completedAt?: string;
  priority: number;
  claimable: boolean;
  actionable: boolean;
  businessKey?: string;
  instanceTitle?: string;
};
export type ActivityState = {
  id?: string;
  taskId?: string;
  activityId: string;
  activityName?: string;
  activityType?: string;
  status: string;
  startedAt?: string;
  endedAt?: string;
  assignee?: string;
};
export type InstanceDiagram = {
  instance: ProcessInstance;
  bpmnXml: string;
  activities: ActivityState[];
  activeActivityIds: string[];
  totalActivities: number;
  historyTruncated: boolean;
};

export type WorkflowProgress = {matter: WorkflowMatter; diagram: InstanceDiagram};
export type NodeExecution = {
  id: string; activityId: string; activityName?: string; activityType: string; taskId?: string;
  status: string; assigned: boolean; assigneeName?: string; actorName?: string; outcome?: string; comment?: string;
  startedAt?: string; endedAt?: string; candidateUsers: Array<{name?: string}>; candidateRoles: Array<{name?: string}>;
};

const quiet = {notifyOnError: false} as const;

export const workflowApi = {
  inboxItem: (id: string, signal?: AbortSignal) =>
    get<WorkflowInboxItem>(`${root}/inbox/tasks/${encodeURIComponent(id)}`, undefined, {...quiet, signal}),
  priorityLiteralPreview: (value: string, signal?: AbortSignal) =>
    get<{value: string | null; issue: string | null}>(`${root}/definitions/priority-preview`, {value}, {...quiet, signal}),
  numericLiteralPreview: (expression: string, signal?: AbortSignal) =>
    get<{value: string}>(`${root}/definitions/literal-preview`, {expression}, {...quiet, signal})
      .then(result => result.value),
  definitions: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<DefinitionSummary>>(`${root}/definitions`, {keyword, page, size}, {...quiet, signal}),
  permissions: (signal?: AbortSignal) => get<DefinitionPermissions>(`${root}/definitions/permissions`, undefined, {...quiet, signal}),
  runtimePermissions: (signal?: AbortSignal) => get<RuntimePermissions>(`${root}/runtime/permissions`, undefined, {...quiet, signal}),
  createDefinition: (data: {key: string; name: string; category?: string; description?: string}, signal?: AbortSignal) =>
    post<DefinitionDetail>(`${root}/definitions`, data, {...quiet, signal}),
  definition: (id: string, signal?: AbortSignal) => get<DefinitionDetail>(`${root}/definitions/${id}`, undefined, {...quiet, signal}),
  version: (id: string, version: number, signal?: AbortSignal) =>
    get<VersionDetail>(`${root}/definitions/${id}/versions/${version}`, undefined, {...quiet, signal}),
  saveDraft: (id: string, data: {expectedRevision: number; name: string; category?: string;
    description?: string; bpmnXml: string; formContract: WorkflowFormContract}, signal?: AbortSignal) =>
    put<DefinitionDetail>(`${root}/definitions/${id}/draft`, data, {...quiet, signal}),
  publish: (id: string, expectedRevision: number, comment?: string, signal?: AbortSignal) =>
    post<VersionSummary>(`${root}/definitions/${id}/publish`, {expectedRevision, comment}, {...quiet, signal}),
  copyVersion: (id: string, version: number, expectedRevision: number, signal?: AbortSignal) =>
    post<DefinitionDetail>(`${root}/definitions/${id}/versions/${version}/copy-to-draft` +
      `?expectedRevision=${encodeURIComponent(expectedRevision)}`, {}, {...quiet, signal}),
  start: (id: string, data: WorkflowStartCommand, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/definitions/${id}/instances`, data, {...quiet, signal}),
  resubmissionForm: (id: string, signal?: AbortSignal) =>
    get<WorkflowResubmission>(`${root}/instances/${encodeURIComponent(id)}/resubmission-form`, undefined, {...quiet, signal}),
  resubmit: (id: string, data: WorkflowStartCommand, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/instances/${encodeURIComponent(id)}/resubmit`, {...data, idempotencyKey: data.idempotencyKey ?? `resubmit:${id}`}, {...quiet, signal}),
  actions: (id: string, page: number, signal?: AbortSignal) =>
    get<WorkflowAuditTrail>(`${root}/instances/${encodeURIComponent(id)}/actions`, {page, size: 20}, {...quiet, signal}),
  startForm: (id: string, version?: number, signal?: AbortSignal) => get<WorkflowRuntimeForm>(`${root}/definitions/${id}/start-form`,
    {version}, {...quiet, signal}),
  taskForm: (id: string, signal?: AbortSignal) => get<WorkflowRuntimeForm>(`${root}/tasks/${id}/form`, undefined, {...quiet, signal}),
  pendingTasks: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<TaskSummary>>(`${root}/tasks/pending`, {keyword, page, size}, {...quiet, signal}),
  completedTasks: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<TaskSummary>>(`${root}/tasks/completed`, {keyword, page, size}, {...quiet, signal}),
  claim: (id: string, signal?: AbortSignal) => post<TaskSummary>(`${root}/tasks/${id}/claim`, {}, {...quiet, signal}),
  complete: (id: string, data: {variables?: Record<string, unknown>; comment?: string; idempotencyKey?: string}, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/tasks/${id}/complete`, {...data, idempotencyKey: data.idempotencyKey ?? `complete:${id}`}, {...quiet, signal}),
  startedInstances: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<ProcessInstance>>(`${root}/instances/started`, {keyword, page, size}, {...quiet, signal}),
  copiedInstances: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<ProcessInstance>>(`${root}/instances/copied`, {keyword, page, size}, {...quiet, signal}),
  instances: (keyword: string, status: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<ProcessInstance>>(`${root}/instances`, {keyword, status, page, size}, {...quiet, signal}),
  diagram: (id: string, signal?: AbortSignal) => get<InstanceDiagram>(`${root}/instances/${id}/diagram`, undefined, {...quiet, signal}),
  progress: (id: string, signal?: AbortSignal) =>
    get<WorkflowProgress>(`${root}/instances/${encodeURIComponent(id)}/progress`, undefined, {...quiet, signal}),
  nodeHistory: (id: string, node: string, page: number, activeOnly: boolean, signal?: AbortSignal) =>
    get<PageResult<NodeExecution>>(`${root}/instances/${encodeURIComponent(id)}/nodes/${encodeURIComponent(node)}`,
      {page, size: 20, activeOnly}, {...quiet, signal}),
  cancel: (id: string, reason: string, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/instances/${id}/cancel`, {reason, idempotencyKey: `cancel:${id}`}, {...quiet, signal}),
  reject: (id: string, reason: string, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/tasks/${encodeURIComponent(id)}/reject`, {reason, idempotencyKey: `reject:${id}`}, {...quiet, signal}),
  withdraw: (id: string, reason: string, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/instances/${encodeURIComponent(id)}/withdraw`, {reason, idempotencyKey: `withdraw:${id}`}, {...quiet, signal}),
};
