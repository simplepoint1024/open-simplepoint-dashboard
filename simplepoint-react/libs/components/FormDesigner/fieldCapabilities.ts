import type {DesignField} from './contracts.ts';

/** Formula-backed fields share persistent field features; legacy placeholders do not. */
export const persistentField = (field: DesignField) => ['STORED', 'REFERENCE', 'ATTACHMENT'].includes(field.storageMode)
  || field.storageMode === 'COMPUTED' && Boolean(field.calculation);
export const calculableField = (field: DesignField) => field.storageMode === 'STORED'
  || field.storageMode === 'COMPUTED' && Boolean(field.calculation);
