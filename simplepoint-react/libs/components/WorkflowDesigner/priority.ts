/** A boolean is never an accepted native priority; no number or Unicode integer text is coerced here. */
export function priorityBooleanLiteral(value: string) {
  return /^\$\{\s*(?:true|false)\s*\}$/.test(value.trim());
}
