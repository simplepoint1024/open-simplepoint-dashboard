import React, {useCallback, useEffect, useLayoutEffect, useMemo, useState} from 'react';
import {PageLifecycleContext} from '@simplepoint/shared/page/context';
import {ownersAllowEviction, retainPages} from './tabCache';
import {useNavigationMeasurement} from './useNavigationMeasurement';

type Tab = {key: string; location: string};
type Retention = Record<string, Record<string, {canEvict: boolean; blockClose: boolean}>>;

function TabPanel({tab, active, report, children}: {
  tab: Tab;
  active: boolean;
  report: (page: string, owner: string, value: boolean | undefined, blockClose?: boolean) => void;
  children: React.ReactElement<{location?: string}>;
}) {
  const element = useNavigationMeasurement(active);
  const reportRetention = useCallback((owner: string, value: boolean | undefined, blockClose = false) => {
    report(tab.key, owner, value, blockClose);
  }, [report, tab.key]);
  const lifecycle = useMemo(() => ({active, reportRetention}), [active, reportRetention]);
  return (
    <PageLifecycleContext.Provider value={lifecycle}>
      <div ref={element} className={`nb-tab-panel${active ? ' nb-tab-panel-active' : ''}`} hidden={!active} aria-hidden={!active}>
        {React.cloneElement(children, {location: tab.location || tab.key})}
      </div>
    </PageLifecycleContext.Provider>
  );
}

/** Restored tab labels do not mount pages until visited. Only safe inactive pages are evicted. */
export function TabPanels({tabs, activeKey, children, onGuardChange}: {
  tabs: Tab[];
  activeKey: string;
  children: React.ReactElement<{location?: string}>;
  onGuardChange: (key: string, blocked: boolean) => void;
}) {
  const [visited, setVisited] = useState<string[]>([]);
  const [owners, setOwners] = useState<Retention>({});
  const report = useCallback((page: string, owner: string, value: boolean | undefined, blockClose = false) => {
    setOwners(previous => {
      const current = previous[page]?.[owner];
      if (current?.canEvict === value && (value === undefined || current?.blockClose === blockClose)) return previous;
      const registrations = {...previous[page]};
      if (value === undefined) delete registrations[owner];
      else registrations[owner] = {canEvict: value, blockClose};
      const next = {...previous};
      if (Object.keys(registrations).length) next[page] = registrations;
      else delete next[page];
      return next;
    });
  }, []);
  useLayoutEffect(() => {
    Object.entries(owners).forEach(([key, registrations]) => onGuardChange(key,
      Object.values(registrations).some(registration => registration.blockClose)));
    return () => Object.keys(owners).forEach(key => onGuardChange(key, false));
  }, [onGuardChange, owners]);
  const retained = useMemo(() => retainPages(
    visited, tabs.map(tab => tab.key), activeKey,
    key => ownersAllowEviction(Object.fromEntries(Object.entries(owners[key] ?? {}).map(([owner, value]) => [owner, value.canEvict]))),
  ), [visited, tabs, activeKey, owners]);
  useEffect(() => {
    setVisited(previous => previous.length === retained.length && previous.every((key, i) => key === retained[i])
      ? previous : retained);
  }, [retained]);
  return tabs.filter(tab => retained.includes(tab.key)).map(tab => (
    <TabPanel key={tab.key} tab={tab} active={tab.key === activeKey} report={report}>
      {children}
    </TabPanel>
  ));
}
