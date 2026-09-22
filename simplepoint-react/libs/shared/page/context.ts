import {createContext} from 'react';

// Process-wide identity: host and MF remotes must consume this exact shared path.
export type PageLifecycle = {
  active: boolean;
  reportRetention: (owner: string, canEvict: boolean | undefined, blockClose?: boolean) => void;
};

export const PageLifecycleContext = createContext<PageLifecycle | null>(null);
