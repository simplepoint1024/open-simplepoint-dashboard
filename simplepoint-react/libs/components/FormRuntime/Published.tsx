import {useMemo} from 'react';
import FormRuntime, {type FormRuntimeProps} from './index';
import type {ApplicationFormRuntimeView} from './contracts';
import type {FormRecord, RecordState} from './recordTableModel';
import {useRecordCalculationPreview} from './useRecordCalculationPreview';
import {useRecordReferences} from './useRecordReferences';
import ReferenceInspector from './ReferenceInspector';
import {ReferenceActionsScope} from '../FormComponents/Reference/NavigationContext';

type Props = Omit<FormRuntimeProps, 'document' | 'sceneId' | 'calculationPreview' | 'recordState'> & {
  descriptor: ApplicationFormRuntimeView;
  record?: Pick<FormRecord, 'id' | 'version' | 'state'> & {values?: Record<string, unknown>} | null;
  previewState?: RecordState;
};

/** Reuses the same authorized preview adapter for CRUD editors and workflow submission dialogs. */
export default function PublishedFormRuntime({descriptor, record, previewState, ...props}: Props) {
  const current = useMemo(() => record ? {...record.values, id: record.id, version: record.version, recordState: record.state} : undefined,
    [record?.id, record?.version, record?.state, record?.values]);
  const preview = useRecordCalculationPreview(descriptor, current, previewState, props.fixedReferenceField);
  const references = useRecordReferences(descriptor, JSON.stringify([record?.id, record?.version]), record ?? undefined);
  return <ReferenceInspector ownerKey={JSON.stringify([descriptor.applicationId, descriptor.pageId, descriptor.formId, descriptor.formVersion, record?.id])}>
    <ReferenceActionsScope disabled={!!props.disabled || !!props.submitting}>
      <FormRuntime {...props} document={descriptor.document} sceneId={descriptor.sceneId}
        recordState={record?.state} calculationPreview={preview} references={references}/>
    </ReferenceActionsScope>
  </ReferenceInspector>;
}
