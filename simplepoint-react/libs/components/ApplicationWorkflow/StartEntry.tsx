import {Alert, Spin} from 'antd';
import {useState} from 'react';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import EntitySelect, {type EntitySelectSource} from '../EntitySelect';
import type {WorkflowFormAdapter} from '../WorkflowDesigner/contracts';
import SubmissionDialog from './SubmissionDialog';
import type {SubmissionSnapshot} from './types';

type Props = Parameters<NonNullable<WorkflowFormAdapter['renderStartEntry']>>[0];
const source: EntitySelectSource = {
  key: 'app.workflow-runtime-pages', searchParams: (keyword, page, size) => ({keyword, page, size}),
  option: row => typeof row.applicationId === 'string' && typeof row.pageId === 'string' && typeof row.name === 'string'
    ? {value: `${row.applicationId}:${row.pageId}`, label: row.name} : undefined,
};

export default function StartEntry({definitionId, form, previousInstanceId, onClose}: Props) {
  const {t} = useI18n();
  const [page, setPage] = useState<string>();
  const sourceSubmission = useData(['app', 'resubmit-source', previousInstanceId], ({signal}) =>
    get<SubmissionSnapshot>(`/app/workflow/instances/${encodeURIComponent(previousInstanceId!)}/business-form`, undefined, {signal}),
  {enabled: !!previousInstanceId, retry: false});
  const previous = sourceSubmission.data?.submission;
  const target = previous ? [previous.applicationId, previous.pageId] : page?.split(':');
  if (target) return <SubmissionDialog applicationId={target[0]} pageId={target[1]}
    definition={{id: definitionId, version: form.definitionVersion}} previousSubmissionId={previous?.id} onClose={onClose}/>;
  if (previousInstanceId) return sourceSubmission.error
    ? <Alert showIcon type="error" title={t('app.workflow.snapshotFailed', '业务快照不可用')}/> : <Spin/>;
  return <EntitySelect endpoint={`/app/workflow/forms/${encodeURIComponent(form.binding!.formId)}/versions/${form.binding!.version}/pages`}
    source={source} ariaLabel={t('app.workflow.choosePage', '选择应用页面')} placeholder={t('app.workflow.choosePage', '选择应用页面')}
    onChange={setPage}/>;
}
