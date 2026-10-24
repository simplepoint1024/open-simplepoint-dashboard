import {Tooltip} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {projectedReference} from './model.ts';

/** Displays server-projected names without querying a directory or replacing the record identity. */
export default function ReferenceLabel({value, projection}: {value: unknown; projection: unknown}) {
  const {t} = useI18n();
  const reference = projectedReference(value, projection);
  if (!reference) return <>{value == null || value === '' ? '—' : String(value)}</>;
  return <Tooltip title={reference.id}><span>{reference.label ?? t('common.referenceUnavailable', '名称不可用')}</span></Tooltip>;
}
