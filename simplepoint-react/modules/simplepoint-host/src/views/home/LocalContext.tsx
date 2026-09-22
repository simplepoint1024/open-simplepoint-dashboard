import {useEffect, useState} from 'react';
import {Tag} from 'antd';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n';
import {HomeCalendar} from './HomeCalendar';
import {zonedDay} from './calendar';

export function LocalContext({region, timeZone}: {region?: string; timeZone?: string}) {
  const {t, locale} = useI18n();
  const active = usePageActive();
  const [now, setNow] = useState(() => new Date());
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    window.addEventListener('online', update); window.addEventListener('offline', update);
    return () => { window.removeEventListener('online', update); window.removeEventListener('offline', update); };
  }, []);
  useEffect(() => {
    if (!active) return;
    setNow(new Date());
    const timer = window.setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(timer);
  }, [active]);
  const zone = timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone;
  let browserRegion = navigator.language;
  try {
    const code = new Intl.Locale(navigator.language).region;
    if (code) browserRegion = new Intl.DisplayNames([locale], {type: 'region'}).of(code) || code;
  } catch { /* Retain the explicit locale when regional display names are unavailable. */ }
  return <><section className="sp-home-panel sp-home-clock" aria-label={t('home.localContext', '本地信息')}>
    <h2>{t('home.localContext', '本地信息')}</h2>
    <time dateTime={now.toISOString()} className="sp-home-time">{new Intl.DateTimeFormat(locale, {hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: zone}).format(now)}</time>
    <p>{new Intl.DateTimeFormat(locale, {dateStyle: 'full', timeZone: zone}).format(now)}</p>
    <dl><div><dt>{t('home.region', '地区')}</dt><dd>{region || browserRegion}</dd></div>
      <div><dt>{t('home.timeZone', '时区')}</dt><dd>{zone}</dd></div>
      <div><dt>{t('home.network', '浏览器网络')}</dt><dd><Tag color={online ? 'green' : 'orange'}>{online ? t('home.online', '在线') : t('home.offline', '离线')}</Tag></dd></div></dl>
    <p className="sp-home-muted sp-home-caption">{region ? t('home.regionManual', '地区由你手动设置') : t('home.regionHint', '地区来自浏览器语言设置，不代表实时定位')}</p>
  </section><HomeCalendar key={zone} today={zonedDay(now, zone)} timeZone={zone} /></>;
}
