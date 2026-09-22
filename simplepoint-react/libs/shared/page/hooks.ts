import {useContext, useId, useLayoutEffect, useSyncExternalStore} from 'react';
import {PageLifecycleContext} from '@simplepoint/shared/page/context';

const subscribeVisibility = (notify: () => void) => {
  document.addEventListener('visibilitychange', notify);
  return () => document.removeEventListener('visibilitychange', notify);
};
const isDocumentVisible = () => document.visibilityState !== 'hidden';

/** UI overlays follow the selected route, independent of browser window visibility. */
export const usePageSelected = () => useContext(PageLifecycleContext)?.active ?? true;

/** Foreground-only automatic reads; explicit saves must never depend on this flag. */
export function usePageActive() {
  const page = useContext(PageLifecycleContext);
  const visible = useSyncExternalStore(subscribeVisibility, isDocumentVisible, () => true);
  return page ? page.active && visible : true;
}

/** Opt-in only: every registered owner must agree before the host can release a page. */
export function usePageRetention(canEvict: boolean, blockClose = false) {
  const page = useContext(PageLifecycleContext);
  const owner = useId();
  const report = page?.reportRetention;
  useLayoutEffect(() => {
    report?.(owner, canEvict, blockClose);
    return () => report?.(owner, undefined);
  }, [report, owner, canEvict, blockClose]);
}
