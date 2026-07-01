import {useEffect} from 'react';
import {useLocation} from 'react-router-dom';

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
    if (keyOrText) {
      const localized = t(keyOrText, keyOrText);
      if (localized) document.title = localized;
    }
  }, [location.pathname, leafRoutes, t]);
  return null;
}
