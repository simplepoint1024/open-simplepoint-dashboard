/** Parse data only. This deliberately does not apply workflow-specific variable-name rules. */
export function parseJsonObject(text: string): Record<string, unknown> {
  const value: unknown = JSON.parse(text);
  if (!value || Array.isArray(value) || typeof value !== 'object') throw Error('JSON 必须是一个对象');
  return value as Record<string, unknown>;
}
