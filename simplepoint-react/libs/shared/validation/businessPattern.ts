import {RE2JS} from 're2js';

const patterns = new Map<string, RE2JS>();
/** Same bounded RE2 syntax on the server and browser; never evaluate designer patterns with native RegExp. */
export function businessPattern(source: string): RE2JS {
  if (!source.trim() || source.length > 512) throw Error('Invalid business pattern');
  const previous = patterns.get(source);
  if (previous) return previous;
  const pattern = RE2JS.compile(source);
  if (pattern.programSize() > 4096) throw Error('Business pattern exceeds complexity budget');
  if (patterns.size >= 128) patterns.delete(patterns.keys().next().value!);
  patterns.set(source, pattern);
  return pattern;
}

/** Walk the owner form's schema so inline detail errors use the same paths as ordinary fields. */
export function validateBusinessPatterns(schema: any, value: any, errors: any, fallback: string): void {
  if (!schema || schema.readOnly || schema['x-field-access'] && schema['x-field-access'] !== 'EDITABLE') return;
  if (typeof value === 'string' && value !== '' && typeof schema['x-business-pattern'] === 'string') {
    let valid = false;
    try {valid = businessPattern(schema['x-business-pattern']).matcher(value).find();} catch { /* invalid metadata fails closed */ }
    if (!valid) errors?.addError?.(schema['x-validation-text']?.pattern || fallback);
  }
  if (Array.isArray(value)) value.forEach((item, index) => validateBusinessPatterns(schema.items, item, errors?.[index], fallback));
  else if (value && typeof value === 'object') Object.entries(schema.properties ?? {}).forEach(([key, child]) =>
    validateBusinessPatterns(child, value[key], errors?.[key], fallback));
}
