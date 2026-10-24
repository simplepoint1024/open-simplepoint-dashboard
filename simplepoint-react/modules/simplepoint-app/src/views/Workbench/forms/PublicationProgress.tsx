import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {createIcon} from '@simplepoint/shared/types/icon';
import {Tooltip} from 'antd';
import {publicationPercent} from './publicationProgress';
import type {PublicationProgress as Progress} from './publication';

/** Domain progress is deliberately separate from the final publication success notice. */
export default function PublicationProgress({progress}: {progress?: Progress | null}) {
  const {t} = useI18n();
  if (!progress) return null;
  const phase = progress.phase === 'STAGING' ? t('app.publication.staging', '数据核算')
    : progress.phase === 'APPLYING' ? t('app.publication.applying', '数据写入') : t('app.publication.restoring', '数据恢复');
  const label = `${phase} · ${progress.completedRecords} / ${progress.totalRecords}`;
  const hint = t('app.publication.confirmationPending', '阶段处理完成后仍需确认最终结果。');
  return <div className="app-form-publication-progress" role="status">
    <div>{label} <Tooltip title={hint}><span role="img" aria-label={hint}>{createIcon('QuestionCircleOutlined')}</span></Tooltip></div>
    <progress aria-label={label} value={publicationPercent(progress)} max={100}
      style={{width: '100%', accentColor: 'var(--ant-color-primary)'}}/>
  </div>;
}
