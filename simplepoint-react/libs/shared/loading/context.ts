import {createContext} from 'react';

// This identity crosses MF boundaries. Only the context is shared, not UI code.
export const LoadingContext = createContext<(() => () => void) | null>(null);
