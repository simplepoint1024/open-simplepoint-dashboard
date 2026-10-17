/** The clock picker edits seconds; preserve an existing lossless fractional value until the clock changes. */
export function timeClock(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const match = /^(\d{2}:\d{2})(?::(\d{2})(?:\.\d{1,6})?)?$/.exec(value);
  return match ? `${match[1]}:${match[2] ?? '00'}` : undefined;
}
export function changedTime(original: unknown, clock: string | undefined): string | undefined {
  return clock && clock === timeClock(original) ? String(original) : clock;
}
