import type {DesignDocument} from '../contracts.ts';
import type {FormInverseRelationView} from './inverseContracts.ts';
import type {InverseSourceMetadata} from './catalog.ts';
import {sameDesignValue} from '../draftState.ts';
import {RelationEditError, sameRelationTarget, validateDesignRelations} from './model.ts';

/** One designer command, fenced against concurrent edits; unrelated presentation metadata survives. */
export function applyInverseView(document: DesignDocument, base: FormInverseRelationView | undefined,
  next: FormInverseRelationView | undefined, metadata?: InverseSourceMetadata) {
  const views = document.presentation?.inverseViews ?? [];
  const id = base?.viewId ?? next?.viewId;
  if (!id || !sameDesignValue(views.find(view => view.viewId === id), base)
    || base && next && base.viewId !== next.viewId) throw new RelationEditError('conflict');
  if (next) {
    const choice = metadata?.relations.find(value => value.relationId === next.sourceRelationId);
    if (!metadata || !sameRelationTarget(next.source, metadata.source) || !choice || next.allowUnlink && !choice.nullable || choice.collection && (next.allowEdit || next.allowDelete))
      throw new RelationEditError('target');
  }
  const result = {...document, presentation: {...document.presentation,
    listFieldOrder: document.presentation?.listFieldOrder ?? [], inverseViews: next
      ? base ? views.map(view => view.viewId === id ? structuredClone(next) : view) : [...views, structuredClone(next)]
      : views.filter(view => view.viewId !== id)}};
  if (!result.presentation.inverseViews.length) delete (result.presentation as {inverseViews?: FormInverseRelationView[]}).inverseViews;
  validateDesignRelations(result);
  return result;
}
