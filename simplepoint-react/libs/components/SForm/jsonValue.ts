import {emptyJsonValue, jsonValue} from '../../shared/utils/jsonValue.ts';

/** Only the explicit field contract opts into structural JSON validation; other JSON editors keep their own syntax policy. */
export function jsonFieldIssue(value: unknown, structural: boolean, required: boolean): 'invalid' | 'required' | undefined {
  if (value == null || typeof value === 'string' && value.trim() === '') return required ? 'required' : undefined;
  try {
    if (structural) {
      jsonValue(value);
      if (required && emptyJsonValue(value)) return 'required';
    } else if (typeof value === 'string') JSON.parse(value);
    else return 'invalid';
  } catch {return 'invalid';}
}
