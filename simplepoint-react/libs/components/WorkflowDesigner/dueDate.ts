export type DueDuration = {days: number; hours: number; minutes: number; seconds: number};

/** Native task due dates never accept exact boolean/numeric scalar results; no expression is evaluated. */
export function nonTemporalDueDateLiteral(value: string): boolean {
  return /^\$\{\s*(?:true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\s*\}$/.test(value.trim());
}

/** Only an explicit-offset, valid calendar spelling is safe for the browser's ordinary date control. */
export function ordinaryDueDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|([+-])(\d{2}):(\d{2}))$/.exec(value);
  if (!match) return false;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const monthDays = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  const offsetHours = Number(match[8] ?? 0), offsetMinutes = Number(match[9] ?? 0);
  return month >= 1 && month <= 12 && day >= 1 && day <= monthDays[month - 1]
    && hour <= 23 && minute <= 59 && second <= 59 && offsetMinutes <= 59
    && (offsetHours < 18 || offsetHours === 18 && offsetMinutes === 0);
}

/** Supports an explicit, lossless ISO-8601 duration subset without evaluating expressions. */
export function parseDueDuration(value: string): DueDuration | undefined {
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value);
  if (!match || !match.slice(1).some(part => part !== undefined) || value.endsWith('T')) return;
  const parts = match.slice(1).map(part => Number(part ?? 0));
  if (parts.some(part => !Number.isSafeInteger(part) || part > 1_000_000)) return;
  return {days: parts[0], hours: parts[1], minutes: parts[2], seconds: parts[3]};
}

export function dueDuration(value: DueDuration): string {
  if (Object.values(value).some(part => !Number.isSafeInteger(part) || part < 0 || part > 1_000_000)) throw Error('Invalid duration');
  const time = `${value.hours ? `${value.hours}H` : ''}${value.minutes ? `${value.minutes}M` : ''}${value.seconds ? `${value.seconds}S` : ''}`;
  const result = `P${value.days ? `${value.days}D` : ''}${time ? `T${time}` : ''}`;
  return result === 'P' ? 'PT0S' : result;
}
