import {createContext} from 'react';
import type {MappingLocation} from './model';
export type MappingEditor = {busy: boolean; change: (location: MappingLocation, target: string | null,
  beginPreparation?: () => (() => void) | undefined) => void};
export const ReferenceMappingContext = createContext<MappingEditor | undefined>(undefined);
