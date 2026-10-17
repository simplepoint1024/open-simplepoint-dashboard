import type {ApplicationFormRuntimeView} from './contracts.ts';
import {runtimeFormModel} from './model.ts';
import {editableFieldIds, type RecordRow} from './recordTableModel.ts';

/** Write policy uses the persisted record, independently of projected and mutable form values. */
export function recordEditorUiSchema(
  descriptor: ApplicationFormRuntimeView,
  formData: Record<string, unknown>,
  schema: any,
  current: RecordRow | null,
) {
  const runtime = runtimeFormModel(
    descriptor.document,
    descriptor.sceneId,
    formData,
    {recordState: current?.recordState},
  ).uiSchema;
  const editable = editableFieldIds(descriptor, undefined, current?.recordState);
  for (const detail of descriptor.document.details ?? []) {
    runtime[detail.detailId] = {'ui:field': 'DetailRows', 'ui:disabled': !editable.has(detail.detailId),
      'ui:options': {detail, recordState: current?.recordState}};
  }
  const fields = new Set(Object.keys(schema?.properties ?? {}));
  return Object.fromEntries(Object.entries(runtime)
    .filter(([field]) => field === 'ui:order' || fields.has(field))
    .map(([field, value]) => field === 'ui:order'
      ? [field, (value as string[]).filter(item => item === '*' || fields.has(item))]
      : [field, value]));
}
