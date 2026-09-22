import {useContext, useLayoutEffect, useState, useSyncExternalStore} from 'react';
import type {ReactNode} from 'react';
import {LoadingContext} from '@simplepoint/shared/loading/context';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {createLoadingState} from './state';
import './style.css';

export function LoadingBoundary({children, label, local = false}: {
  children: ReactNode;
  label: string;
  local?: boolean;
}) {
  const [state] = useState(createLoadingState);
  const {busy, visible, slow} = useSyncExternalStore(state.subscribe, state.getSnapshot, state.getSnapshot);
  return (
    <LoadingContext.Provider value={state.register}>
      <div className={`sp-loading-boundary${local ? ' sp-loading-local' : ''}`} aria-busy={busy}>
        {children}
        {visible && <div className="sp-loading-progress" role="progressbar" aria-label={label} />}
        {slow && <div className="sp-loading-hint" role="status">{label}</div>}
      </div>
    </LoadingContext.Provider>
  );
}

/** No geometry-changing placeholder. The persistent boundary owns feedback. */
export function LoadingPlaceholder() {
  const register = useContext(LoadingContext);
  useLayoutEffect(() => register?.(), [register]);
  return register
    ? <div className="sp-loading-placeholder" aria-hidden="true" />
    : <StandaloneLoadingPlaceholder />;
}

function StandaloneLoadingPlaceholder() {
  const {t} = useI18n();
  return (
    <LoadingBoundary local label={t('loading', '加载中...')}>
      <LoadingPlaceholder />
    </LoadingBoundary>
  );
}
