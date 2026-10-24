import type {ApplicationFormRuntimeView} from '../FormRuntime/contracts.ts';
import type {FormRecord} from '../FormRuntime/recordTableModel.ts';
import {runtimeFormModel} from '../FormRuntime/model.ts';
import {requiredValueIssues} from '../FormRuntime/requiredValues.ts';

/** Explain visible missing inputs before readonly confirmation; the server validates the full aggregate again. */
export function missingSubmissionFields(form: ApplicationFormRuntimeView, record: FormRecord): string[] {
  const labels = new Set<string>();
  for (const scene of form.document.scenes) {
    const model = runtimeFormModel(form.document, scene.sceneId, record.values,
      {enforceRequired: true, recordState: record.state});
    for (const issue of requiredValueIssues(form.document, model.schema, record.values)) {
      // Absent main values may be transient or deliberately omitted by the server projection.
      // Only diagnose saved values here; authoritative submission validation still checks every invariant.
      if (issue.path.length === 1 && !Object.prototype.hasOwnProperty.call(record.values, issue.path[0])) continue;
      const detail = form.document.details?.find(item => item.detailId === issue.path[0]);
      labels.add(detail ? `${detail.label} / ${Number(issue.path[1]) + 1} / ${issue.label}` : issue.label);
    }
  }
  return [...labels];
}
