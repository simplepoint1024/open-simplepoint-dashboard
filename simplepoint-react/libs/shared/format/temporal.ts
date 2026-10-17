export type TemporalKind = 'date' | 'time' | 'datetime';
type Options = {locale?: string; kind?: TemporalKind; timeZone?: string; empty?: string};
const applicationLocale = () => typeof window === 'undefined' ? 'zh-CN' : window.spI18n?.locale ?? 'zh-CN';

/** Dates and civil times never shift zones. Only offset-bearing instants use the viewer's zone. */
export function formatTemporal(value: unknown, {locale = applicationLocale(), kind = 'datetime', timeZone, empty = '—'}: Options = {}) {
  if (value == null || value === '') return empty;
  const text = String(value);
  if (kind === 'time') return text; // A clock time has no date or zone to convert.
  const civil = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(\.\d+)?)?)?$/.exec(text);
  const dateOnly = civil && !civil[4];
  let date: Date;
  if (civil) {
    date = new Date(`${civil[1]}-${civil[2]}-${civil[3]}T${civil[4] ?? '00'}:${civil[5] ?? '00'}:${civil[6] ?? '00'}${civil[7] ?? ''}Z`);
    if (Number(civil[1]) !== date.getUTCFullYear() || Number(civil[2]) !== date.getUTCMonth() + 1
      || Number(civil[3]) !== date.getUTCDate()) return text;
  } else {
    // Do not parse masked data, arbitrary text, or ambiguous locale-specific date strings.
    if (!(value instanceof Date) && !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:?\d{2})$/i.test(text)) return text;
    date = value instanceof Date ? value : new Date(text);
  }
  if (!Number.isFinite(date.getTime())) return text;
  const options: Intl.DateTimeFormatOptions = {year: 'numeric', month: '2-digit', day: '2-digit',
    ...(civil ? {timeZone: 'UTC'} : timeZone ? {timeZone} : {}),
    ...(kind === 'date' || dateOnly ? {} : {hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'})};
  try {return new Intl.DateTimeFormat(locale, options).format(date);}
  catch {return new Intl.DateTimeFormat('zh-CN', {...options, timeZone: civil ? 'UTC' : undefined}).format(date);}
}
export const formatDateTime = (value: unknown, locale?: string) => formatTemporal(value, {locale});
