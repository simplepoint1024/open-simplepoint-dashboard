import {createContext} from 'react';
import type {DesignerRelationDirectory} from './catalog';

/** Hosting applications supply discovery; reusable property editors never construct HTTP routes. */
export const DesignerRelationContext = createContext<DesignerRelationDirectory | undefined>(undefined);
