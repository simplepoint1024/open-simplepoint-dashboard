import {createContext} from 'react';

// Process-wide identity: host and MF remotes must consume this exact shared path.
export type PageLifecycle = {
  active: boolean;
  /** This tab's location remains stable while another tab owns the global address bar. */
  location?: string;
  reportRetention: (owner: string, canEvict: boolean | undefined, blockClose?: boolean) => void;
};

export const PageLifecycleContext = createContext<PageLifecycle | null>(null);
