import {formatTemporal} from '@simplepoint/shared/format/temporal';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {FieldProps} from '@rjsf/utils';
import {ID_KEY} from '@rjsf/utils';
import {displaySelection, displayValue} from '../displayOnly';
import ReferenceLabel from '../../ReferenceLabel';
import {projectedReference} from '../../ReferenceLabel/model.ts';
import FormReferenceValue from '../../FormComponents/Reference/Value';

/** Static projections take precedence; live business labels require an explicit owning runtime adapter. */
export default function DisplayValue({fieldPathId, formData, schema}: FieldProps) {
  const {locale, t} = useI18n();
  const format = schema['x-display-format'];
  const reference = projectedReference(formData, schema['x-reference-label']);
  const selection = displaySelection(formData, schema['x-display-selection']);
  return <span id={fieldPathId[ID_KEY]} className="sp-form-display-value">
    {reference ? <ReferenceLabel value={formData} projection={reference}/>
      : schema['x-form-reference']?.relationId ? <FormReferenceValue value={formData} relationId={schema['x-form-reference'].relationId}/>
      : format && !schema['x-display-choices']?.length ? formatTemporal(formData, {locale,
      kind: format === 'date' ? 'date' : format === 'time' ? 'time' : 'datetime'}) : selection ?? (
        typeof formData === 'boolean' && !schema['x-display-choices']?.length
          ? formData ? t('common.yes', '是') : t('common.no', '否') : displayValue(formData, schema['x-display-choices']))}
  </span>;
}
