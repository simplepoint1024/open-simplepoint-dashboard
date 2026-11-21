import {referenceIdentity} from './display.ts';

export const REFERENCE_DRAWER_DEPTH = 2;

/** Pure stack decisions; the caller separately supplies current authorization and an active owner. */
export function referenceNavigation(depth: number, id: unknown, detailAvailable: boolean, pageAvailable: boolean): 'drawer' | 'page' | undefined {
  if (!referenceIdentity(id) || !detailAvailable || !Number.isSafeInteger(depth) || depth < 0) return undefined;
  return depth < REFERENCE_DRAWER_DEPTH ? 'drawer' : pageAvailable ? 'page' : undefined;
}
