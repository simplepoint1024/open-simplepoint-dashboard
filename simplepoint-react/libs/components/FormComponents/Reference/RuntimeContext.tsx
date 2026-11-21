import type {MappingLoader} from '../../FormRuntime/referenceMapping/model';
import {createContext} from 'react';
import type {EntitySelectSource} from '../../EntitySelect/index.tsx';
import type {ApplicationFormRuntimeView} from '../../FormRuntime/contracts';
import type {FormRecord} from '../../FormRuntime/recordTableModel';
import type {ReferenceSearchContext} from './inputs';
import type {LiveReferenceView} from '../../FormRuntime/liveReferenceValues';

export type ReferenceDetail = {form: ApplicationFormRuntimeView; record: FormRecord};
export type ReferenceBinding = {endpoint: string; source: EntitySelectSource;
  mapping?: MappingLoader;
  live?: (recordIds: string[], signal: AbortSignal) => Promise<LiveReferenceView[]>;
  detail?: (recordId: string, signal: AbortSignal) => Promise<ReferenceDetail>;
  pageLocation?: (recordId: string) => string};
export type ReferenceSources = (relationId: string, context?: ReferenceSearchContext) => ReferenceBinding;

/** The generic renderer receives trusted adapters; published JSON never supplies executable URLs. */
export const ReferenceRuntimeContext = createContext<ReferenceSources | undefined>(undefined);
