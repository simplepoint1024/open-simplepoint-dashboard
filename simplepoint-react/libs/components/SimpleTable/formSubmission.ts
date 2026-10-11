export type FormSubmission<T> = {owner: string; request: AbortController; target: T; phase: 'validating' | 'preparing' | 'writing'};
type SubmissionRef<T> = {current: FormSubmission<T> | undefined};

export function prepareFormSubmission<T>(ref: SubmissionRef<T>, owner: string, request: AbortController, target: T) {
  if (ref.current || request.signal.aborted) return undefined;
  const submission: FormSubmission<T> = {owner, request, target, phase: 'validating'};
  ref.current = submission; return submission;
}
export function claimFormSubmission<T>(ref: SubmissionRef<T>, owner: string) {
  const submission = ref.current;
  if (!submission || submission.owner !== owner || submission.phase !== 'validating' || submission.request.signal.aborted) return undefined;
  submission.phase = 'preparing'; return submission;
}
export function startFormWrite<T>(ref: SubmissionRef<T>, submission: FormSubmission<T>) {
  if (ref.current !== submission || submission.phase !== 'preparing' || submission.request.signal.aborted) return false;
  submission.phase = 'writing'; return true;
}
/** Hiding cancels validation/preparation, never a write that has already been dispatched. */
export function cancelFormPreparation<T>(ref: SubmissionRef<T>, owner: string) {
  const submission = ref.current;
  if (!submission || submission.owner !== owner || submission.phase === 'writing') return undefined;
  ref.current = undefined; submission.request.abort(); return submission;
}
/** Validation failure only releases validation, never an already started write or another owner. */
export function cancelFormValidation<T>(ref: SubmissionRef<T>, owner: string) {
  const submission = ref.current;
  if (!submission || submission.owner !== owner || submission.phase !== 'validating') return undefined;
  ref.current = undefined; return submission;
}
export function finishFormSubmission<T>(ref: SubmissionRef<T>, submission: FormSubmission<T>) {
  if (ref.current !== submission) return false;
  ref.current = undefined; return true;
}
