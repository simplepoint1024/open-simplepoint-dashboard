import {useMemo} from 'react';
import FormRuntime, {type FormRuntimeProps} from './index';
import type {ApplicationFormRuntimeView} from './contracts';
import type {FormRecord, RecordState} from './recordTableModel';
import {useRecordCalculationPreview} from './useRecordCalculationPreview';

type Props = Omit<FormRuntimeProps, 'document' | 'sceneId' | 'calculationPreview' | 'recordState'> & {
  descriptor: ApplicationFormRuntimeView;
  record?: Pick<FormRecord, 'id' | 'version' | 'state'> & {values?: Record<string, unknown>} | null;
  previewState?: RecordState;
};

/** Reuses the same authorized preview adapter for CRUD editors and workflow submission dialogs. */
export default function PublishedFormRuntime({descriptor, record, previewState, ...props}: Props) {
  const current = useMemo(() => record ? {...record.values, id: record.id, version: record.version, recordState: record.state} : undefined,
    [record?.id, record?.version, record?.state, record?.values]);
  const preview = useRecordCalculationPreview(descriptor, current, previewState);
  return <FormRuntime {...props} document={descriptor.document} sceneId={descriptor.sceneId}
    recordState={record?.state} calculationPreview={preview}/>;
}
