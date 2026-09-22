import {createContext, useContext} from 'react';

export const NavigationGuardContext = createContext<() => Promise<boolean>>(async () => true);
export const useNavigationGuard = () => useContext(NavigationGuardContext);
