export type BillingPeriod = {from: string; to: string};
const maximumPeriod = 366 * 24 * 60 * 60 * 1000;

/** The API period is [from, to); the UI supplies midnight after the last selected day. */
export function billingPeriod(from: string, to: string): BillingPeriod | undefined {
  const start = Date.parse(from), end = Date.parse(to);
  return Number.isFinite(start) && Number.isFinite(end) && start < end && end - start <= maximumPeriod
    ? {from, to} : undefined;
}

/** User filters can narrow the selected period, never replace its boundary conditions. */
export function billingInvocationQuery(period: BillingPeriod, advanced?: string): string {
  const children: object[] = [
    {field: 'startedAt', op: 'than:equal:greater', value: period.from},
    {field: 'startedAt', op: 'than:less', value: period.to},
  ];
  if (advanced) {
    const document = JSON.parse(advanced);
    if (document.version !== 1 || !document.where || typeof document.where !== 'object' || Array.isArray(document.where)) {
      throw Error('Invalid billing invocation query');
    }
    children.push(document.where);
  }
  return JSON.stringify({version: 1, where: {op: 'and', children}});
}

const formats = new Map<string, Intl.NumberFormat>();
/** Keep BigDecimal JSON strings exact; Number(value) would round large financial totals. */
export function formatBillingAmount(value: unknown, currency: string, locale: string): string {
  if ((typeof value !== 'string' && typeof value !== 'number') || !/^[+-]?\d+(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(String(value))) return '—';
  const raw = String(value), key = JSON.stringify([locale, currency]);
  try {
    let format = formats.get(key);
    if (!format) {
      format = new Intl.NumberFormat(locale, {style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 12});
      if (formats.size >= 32) formats.delete(formats.keys().next().value!);
      formats.set(key, format);
    }
    // Modern ECMAScript Intl accepts exact decimal strings; the TS library signature predates it.
    return (format.format as unknown as (amount: string) => string)(raw);
  } catch {return `${currency} ${raw}`;}
}
