import {useEffect} from 'react';
import {useLocation} from 'react-router';
import {DEFAULT_APP_TITLE, resolveDocumentTitle} from './documentTitle';

export function TitleSync({
  leafRoutes,
  t,
}: {
  leafRoutes: Array<{ path?: string; title?: string; label?: string }>,
  t: (k: string, d?: string) => string,
}) {
  const location = useLocation();
  useEffect(() => {
    const current = leafRoutes.find(n => n.path === location.pathname);
    const keyOrText = (current?.title || current?.label || '') as string;
    const localizedPageTitle = keyOrText ? t(keyOrText, keyOrText) : '';
    const appTitle = t('app.title', DEFAULT_APP_TITLE);
    document.title = resolveDocumentTitle(localizedPageTitle, appTitle);
  }, [location.pathname, leafRoutes, t]);
  return null;
}
