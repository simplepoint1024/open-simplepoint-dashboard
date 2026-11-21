import {createContext, useContext, type ReactNode} from 'react';
import type {ReferenceBinding} from './RuntimeContext';

export type ReferenceNavigation = {
  canOpen: (binding: ReferenceBinding, recordId: string) => boolean;
  open: (binding: ReferenceBinding, recordId: string) => void;
};
export const ReferenceNavigationContext = createContext<ReferenceNavigation | undefined>(undefined);

const disabledNavigation: ReferenceNavigation = {canOpen: () => false, open: () => {}};

/** A busy/locked form cannot bypass the owning operation lock through a reference action. */
export function ReferenceActionsScope({disabled, children}: {disabled: boolean; children: ReactNode}) {
  const navigation = useContext(ReferenceNavigationContext);
  return <ReferenceNavigationContext.Provider value={disabled ? disabledNavigation : navigation}>{children}</ReferenceNavigationContext.Provider>;
}
