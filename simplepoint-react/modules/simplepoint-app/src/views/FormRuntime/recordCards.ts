import type {ApplicationFormDocument} from '@simplepoint/components/FormRuntime/contracts';
import type {CardViewConfig} from '@simplepoint/components/Table/CardView';

/** Uses authorized business fields; the platform card renderer still honors visible columns. */
export function recordCards(document: ApplicationFormDocument): CardViewConfig {
  const order = document.presentation?.listFieldOrder ?? [];
  const rank = (id: string) => {
    const index = order.indexOf(id);
    return index < 0 ? Number.MAX_SAFE_INTEGER : index;
  };
  const fields = [...document.fields].sort((left, right) => rank(left.fieldId) - rank(right.fieldId));
  return {titleKey: (fields.find(field => field.type === 'STRING') ?? fields[0])?.fieldId, statusKey: 'recordState'};
}
