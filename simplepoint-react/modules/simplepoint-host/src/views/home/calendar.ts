/** Calendar arithmetic uses UTC date-only values, never device-local midnight. */
export function zonedDay(now: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', {timeZone, calendar: 'gregory', numberingSystem: 'latn',
    year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(now);
  const part = (type: string) => parts.find(value => value.type === type)!.value;
  return `${part('year').padStart(4, '0')}-${part('month')}-${part('day')}`;
}

export const dayDate = (day: string) => new Date(`${day}T12:00:00Z`);
export const dayKey = (date: Date) => date.toISOString().split('T')[0];
export const monthStart = (day: string) => `${day.slice(0, 7)}-01`;

export function addDays(day: string, amount: number): string {
  const date = dayDate(day);
  date.setUTCDate(date.getUTCDate() + amount);
  return dayKey(date);
}

export function addMonths(day: string, amount: number): string {
  const date = dayDate(monthStart(day));
  date.setUTCMonth(date.getUTCMonth() + amount + 1, 0);
  const lastDay = date.getUTCDate();
  date.setUTCDate(Math.min(Number(day.slice(8)), lastDay));
  return dayKey(date);
}

/** Monday-first, six rows to keep the home sidebar stable between months. */
export function monthDays(month: string): string[] {
  const first = monthStart(month);
  const offset = (dayDate(first).getUTCDay() + 6) % 7;
  const start = addDays(first, -offset);
  return Array.from({length: 42}, (_, index) => addDays(start, index));
}
