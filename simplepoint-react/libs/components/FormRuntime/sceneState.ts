import type {ApplicationFormDocument} from './contracts.ts';
import type {RuntimeFormModel, RuntimeFormAction} from './model.ts';

export type FormValues = Record<string, unknown>;
export type SceneDraft = {sceneId: string; initial: FormValues; values: FormValues};

export function sceneValues(values: FormValues, fieldIds: readonly string[]): FormValues {
  return Object.fromEntries(fieldIds.filter(id => Object.prototype.hasOwnProperty.call(values, id)).map(id => [id, values[id]]));
}

/** A scene edits only its own enabled fields; other scene values survive every change. */
export function mergeSceneValues(values: FormValues, next: FormValues, model: Pick<RuntimeFormModel, 'editableFieldIds'>): FormValues {
  const merged = {...values};
  model.editableFieldIds.forEach(id => {
    if (Object.prototype.hasOwnProperty.call(next, id) || Object.prototype.hasOwnProperty.call(values, id)) merged[id] = next[id];
  });
  return merged;
}

export function canOpenScene(document: ApplicationFormDocument, action: RuntimeFormAction, active: readonly string[]) {
  const scene = document.scenes.find(candidate => candidate.sceneId === action.targetSceneId);
  const type = action.key === 'open-dialog' ? 'DIALOG' : action.key === 'open-drawer' ? 'DRAWER' : undefined;
  return Boolean(scene && scene.type === type && !active.includes(scene.sceneId) && active.length < 8);
}

export function sceneDraft(sceneId: string, values: FormValues): SceneDraft {
  return {sceneId, initial: structuredClone(values), values: structuredClone(values)};
}

/** Structural comparison normalizes absent properties, as the JSON transport does. */
export function sameFormValues(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false;
  if (Array.isArray(left) !== Array.isArray(right)) return false;
  const leftEntries = Object.entries(left).filter(([, value]) => value !== undefined);
  const rightEntries = Object.entries(right).filter(([, value]) => value !== undefined);
  return leftEntries.length === rightEntries.length && leftEntries.every(([key, value]) =>
    Object.prototype.hasOwnProperty.call(right, key) && sameFormValues(value, (right as FormValues)[key]));
}
