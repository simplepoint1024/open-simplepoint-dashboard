import {formatTemporal} from '@simplepoint/shared/format/temporal';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {FieldProps} from '@rjsf/utils';
import {ID_KEY} from '@rjsf/utils';
import {displaySelection, displayValue} from '../displayOnly';
import ReferenceLabel from '../../ReferenceLabel';
import {projectedReference} from '../../ReferenceLabel/model.ts';

/** Formats declared temporal values for display; never fetches references, coerces numbers, or emits changes. */
export default function DisplayValue({fieldPathId, formData, schema}: FieldProps) {
  const {locale, t} = useI18n();
  const format = schema['x-display-format'];
  const reference = projectedReference(formData, schema['x-reference-label']);
  const selection = displaySelection(formData, schema['x-display-selection']);
  return <span id={fieldPathId[ID_KEY]} className="sp-form-display-value">
    {reference ? <ReferenceLabel value={formData} projection={reference}/> : format && !schema['x-display-choices']?.length ? formatTemporal(formData, {locale,
      kind: format === 'date' ? 'date' : format === 'time' ? 'time' : 'datetime'}) : selection ?? (
        typeof formData === 'boolean' && !schema['x-display-choices']?.length
          ? formData ? t('common.yes', '是') : t('common.no', '否') : displayValue(formData, schema['x-display-choices']))}
  </span>;
}
