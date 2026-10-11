import {useContext, useMemo, type ReactNode} from 'react';
import {PageLifecycleContext} from '@simplepoint/shared/page/context';

/** Retained feature tabs keep their state but suspend reads and overlays while not selected. */
export default function PageSection({selected, children}: {selected: boolean; children: ReactNode}) {
  const parent = useContext(PageLifecycleContext);
  const value = useMemo(() => ({...parent, active: selected && (parent?.active ?? true),
    reportRetention: parent?.reportRetention ?? (() => {})}), [parent, selected]);
  return <PageLifecycleContext.Provider value={value}>{children}</PageLifecycleContext.Provider>;
}
