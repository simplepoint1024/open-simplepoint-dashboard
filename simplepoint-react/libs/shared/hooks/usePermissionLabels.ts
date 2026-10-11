import {useEffect, useMemo} from 'react';
import {get} from '../api/methods';
import {useOwnedData} from './useOwnedData';
import {permissionLabels, type PermissionPresentation} from '../i18n/permissionLabels';
import {useI18n} from './useI18n';

export function usePermissionLabels(enabled = true) {
  const {t, ensure, locale} = useI18n();
  const query = useOwnedData<PermissionPresentation>(['permission-presentation'],
    ({signal}) => get('/common/data-scopes/presentation', undefined, {signal, notifyOnError: false}), {enabled, staleTime: 30_000, retry: false});
  useEffect(() => {
    if (!enabled) return;
    const pages = Object.entries(query.data?.pages ?? {});
    const titles = pages.flatMap(([, page]) => [page.title, ...page.models.flatMap(model => Object.values(model.properties).map(field => field.title))]);
    void ensure([...new Set(['permissions', 'organizations', ...pages.map(([code]) => code.replace(/\.view$/, '').split('.')[0]),
      ...titles.filter(title => title?.startsWith('i18n:')).map(title => title.slice(5).split('.')[0])])]);
  }, [query.data, enabled, ensure, locale]);
  return {...query, labels: useMemo(() => permissionLabels(t, query.data), [t, query.data]), t, locale};
}
