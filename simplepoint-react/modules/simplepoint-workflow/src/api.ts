import {get, post, put} from '@simplepoint/shared/api/methods';
import type {WorkflowFormContract, WorkflowRuntimeForm} from '@simplepoint/components/WorkflowDesigner/contracts';

const root = '/workflow';

export type PageResult<T> = {content: T[]; page: number; size: number; totalElements: number};
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
};

const quiet = {notifyOnError: false} as const;

export const workflowApi = {
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
  start: (id: string, data: {version?: number; businessKey?: string; title?: string;
    variables?: Record<string, unknown>; ccUserIds?: string[]}, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/definitions/${id}/instances`, data, {...quiet, signal}),
  startForm: (id: string, version?: number, signal?: AbortSignal) => get<WorkflowRuntimeForm>(`${root}/definitions/${id}/start-form`,
    {version}, {...quiet, signal}),
  taskForm: (id: string, signal?: AbortSignal) => get<WorkflowRuntimeForm>(`${root}/tasks/${id}/form`, undefined, {...quiet, signal}),
  pendingTasks: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<TaskSummary>>(`${root}/tasks/pending`, {keyword, page, size}, {...quiet, signal}),
  completedTasks: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<TaskSummary>>(`${root}/tasks/completed`, {keyword, page, size}, {...quiet, signal}),
  claim: (id: string, signal?: AbortSignal) => post<TaskSummary>(`${root}/tasks/${id}/claim`, {}, {...quiet, signal}),
  complete: (id: string, data: {variables?: Record<string, unknown>; comment?: string}, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/tasks/${id}/complete`, data, {...quiet, signal}),
  startedInstances: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<ProcessInstance>>(`${root}/instances/started`, {keyword, page, size}, {...quiet, signal}),
  copiedInstances: (keyword: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<ProcessInstance>>(`${root}/instances/copied`, {keyword, page, size}, {...quiet, signal}),
  instances: (keyword: string, status: string, page: number, size: number, signal?: AbortSignal) =>
    get<PageResult<ProcessInstance>>(`${root}/instances`, {keyword, status, page, size}, {...quiet, signal}),
  diagram: (id: string, signal?: AbortSignal) => get<InstanceDiagram>(`${root}/instances/${id}/diagram`, undefined, {...quiet, signal}),
  cancel: (id: string, reason: string, signal?: AbortSignal) =>
    post<ProcessInstance>(`${root}/instances/${id}/cancel`, {reason}, {...quiet, signal}),
};
