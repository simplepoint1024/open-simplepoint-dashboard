import {formatTemporal} from '@simplepoint/shared/format/temporal';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {FieldProps} from '@rjsf/utils';
import {ID_KEY} from '@rjsf/utils';
import {displayValue} from '../displayOnly';

/** Formats declared temporal values for display; never fetches references, coerces numbers, or emits changes. */
export default function DisplayValue({fieldPathId, formData, schema}: FieldProps) {
  const {locale} = useI18n();
  const format = schema['x-display-format'];
  return <span id={fieldPathId[ID_KEY]} className="sp-form-display-value">
    {format && !schema['x-display-choices']?.length ? formatTemporal(formData, {locale,
      kind: format === 'date' ? 'date' : format === 'time' ? 'time' : 'datetime'}) : displayValue(formData, schema['x-display-choices'])}
  </span>;
}
