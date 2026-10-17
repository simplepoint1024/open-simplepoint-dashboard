import type {ApplicationFormRuntimeView, FormRecord} from '../FormRuntime';
import type {WorkflowVariable} from '../WorkflowDesigner/contracts';

export type Submission = {
  id: string; title: string; applicationId: string; pageId: string; formId: string; formVersion: number;
  recordId?: string; recordVersion?: number; definitionId: string; definitionVersion: number;
  previousSubmissionId?: string; processInstanceId?: string; processStatus?: string;
  status: string; step: string; errorCode?: string; attempt: number; createdAt: string; updatedAt: string;
};
export type SubmissionSnapshot = {submission: Submission; form: ApplicationFormRuntimeView; record: FormRecord};
export type Preparation = {
  definitionId: string; definitionVersion: number; previousSubmissionId?: string; title: string;
  copyOnResubmission?: boolean;
  form: ApplicationFormRuntimeView; record?: FormRecord; variables: WorkflowVariable[];
  values: Record<string, unknown>; ccUserIds: string[];
};
export const submissionUrl = (applicationId: string, pageId: string) =>
  `/app/runtime/applications/${encodeURIComponent(applicationId)}/pages/${encodeURIComponent(pageId)}/submissions`;
