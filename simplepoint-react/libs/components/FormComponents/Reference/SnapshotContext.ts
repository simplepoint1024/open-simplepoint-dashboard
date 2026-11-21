import {createContext} from 'react';
import type {ReferenceSnapshotLabels} from './snapshots.ts';

/** Defined (including empty) means immutable history: no live lookup or drill-through fallback. */
export const ReferenceSnapshotContext = createContext<ReferenceSnapshotLabels | undefined>(undefined);
