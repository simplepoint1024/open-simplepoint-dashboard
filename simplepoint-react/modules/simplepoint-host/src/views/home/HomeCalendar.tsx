import {memo, useId, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent} from 'react';
import {Button} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@/layouts/i18n/useI18n';
import {addDays, addMonths, dayDate, monthDays, monthStart} from './calendar';

/** Reuses the local-context clock; no extra requests, timers or calendar dependency. */
export const HomeCalendar = memo(function HomeCalendar({today, timeZone}: {today: string; timeZone: string}) {
  const {t, locale} = useI18n();
  const heading = useId();
  const [view, setView] = useState<string | null>(null);
  const [selection, setSelection] = useState<string | null>(null);
  const table = useRef<HTMLTableElement>(null);
  const pendingFocus = useRef<string | null>(null);
  const selected = selection || today;
  const month = view || monthStart(today);
  const days = useMemo(() => monthDays(month), [month]);
  const focusDay = selected.slice(0, 7) === month.slice(0, 7) ? selected : month;
  const fullDate = useMemo(() => new Intl.DateTimeFormat(locale, {dateStyle: 'full', timeZone: 'UTC'}), [locale]);
  const monthLabel = new Intl.DateTimeFormat(locale, {year: 'numeric', month: 'long', timeZone: 'UTC'}).format(dayDate(month));
  const weekdays = useMemo(() => {
    const format = new Intl.DateTimeFormat(locale, {weekday: 'short', timeZone: 'UTC'});
    return Array.from({length: 7}, (_, index) => format.format(dayDate(addDays('2024-01-01', index))));
  }, [locale]);
  useLayoutEffect(() => {
    if (!pendingFocus.current) return;
    table.current?.querySelector<HTMLButtonElement>(`[data-day="${pendingFocus.current}"]`)?.focus();
    pendingFocus.current = null;
  }, [selected, month]);
  const select = (day: string, focus = false) => {
    if (day < '0001-01-01' || day > '9999-12-31') return;
    if (focus) pendingFocus.current = day;
    setSelection(day);
    setView(monthStart(day));
  };
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, day: string) => {
    const offset = (dayDate(day).getUTCDay() + 6) % 7;
    const delta: Record<string, number> = {ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7, Home: -offset, End: 6 - offset};
    if (event.key in delta) { event.preventDefault(); select(addDays(day, delta[event.key]), true); }
    else if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault(); select(addMonths(day, event.key === 'PageUp' ? -1 : 1), true);
    }
  };
  return <section className="sp-home-panel sp-home-calendar" aria-label={t('home.calendar.title', '日历')}>
    <div className="sp-home-section-heading"><h2>{createIcon('CalendarOutlined')} {t('home.calendar.title', '日历')}</h2>
      <Button size="small" onClick={() => {setSelection(null); setView(null);}}>{t('home.calendar.today', '回到今天')}</Button></div>
    <div className="sp-home-calendar-nav">
      <Button type="text" size="small" icon={createIcon('LeftOutlined')} aria-label={t('home.calendar.previous', '上个月')}
        disabled={month <= '0001-01-01'} onClick={() => setView(addMonths(month, -1))} />
      <strong id={heading} aria-live="polite">{monthLabel}</strong>
      <Button type="text" size="small" icon={createIcon('RightOutlined')} aria-label={t('home.calendar.next', '下个月')}
        disabled={month >= '9999-12-01'} onClick={() => setView(addMonths(month, 1))} />
    </div>
    <table ref={table} aria-labelledby={heading}>
      <thead><tr>{weekdays.map((label, index) => <th key={index} scope="col">{label}</th>)}</tr></thead>
      <tbody>{Array.from({length: 6}, (_, week) => <tr key={week}>{days.slice(week * 7, week * 7 + 7).map(day =>
        <td key={day}><button type="button" data-day={day} tabIndex={day === focusDay ? 0 : -1}
          className={day.slice(0, 7) !== month.slice(0, 7) ? 'is-other-month' : undefined}
          disabled={day < '0001-01-01' || day > '9999-12-31'}
          aria-label={fullDate.format(dayDate(day))} aria-current={day === today ? 'date' : undefined} aria-pressed={day === selected}
          onClick={() => select(day, true)} onKeyDown={event => onKeyDown(event, day)}>{dayDate(day).getUTCDate()}</button></td>)}</tr>)}</tbody>
    </table>
    <p className="sp-home-calendar-selected" aria-live="polite">{t('home.calendar.selected', '已选日期')} · {fullDate.format(dayDate(selected))}</p>
    <p className="sp-home-muted sp-home-caption">{timeZone}</p>
  </section>;
});
