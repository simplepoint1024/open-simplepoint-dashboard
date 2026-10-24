import type {DesignDocument, DesignField} from './contracts.ts';
import type {FormComponentDefinition} from '../FormRuntime/contracts.ts';

export function renamedFieldScenes(document: DesignDocument, previous: DesignField | undefined, next: DesignField | undefined) {
  if (!previous || !next || previous.label === next.label) return document.scenes;
  const visit = (node: FormComponentDefinition): FormComponentDefinition => ({...node,
    ...(node.fieldId === previous.fieldId && !node.labelLocalization && node.label === previous.label ? {label: next.label} : {}),
    children: node.children.map(visit)});
  return document.scenes.map(scene => ({...scene, root: visit(scene.root)}));
}
