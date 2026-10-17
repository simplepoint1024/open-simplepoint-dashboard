import type {DesignDocument} from '../FormDesigner/contracts.ts';
import {DETAIL, initializeDetail, cloneDetail, validateDetail} from './Detail/model.ts';
import type {FormComponentDefinition as Node} from '../FormRuntime/contracts.ts';
import {CELL, MATRIX, matrixSize, resizeMatrix, validateMatrix} from './Matrix/model.ts';

/** Pure component lifecycle hooks: no designer, networking or React dependencies. */
export type FormComponentBehavior = {
  type: string;
  version: string;
  palette?: boolean;
  structural?: boolean;
  cells?: (node: Node) => {columns: number; managed: boolean};
  initialize?: (node: Node, identity: () => string, document: DesignDocument) => Node;
  validate?: (node: Node, document: DesignDocument) => void;
  clone?: (node: Node, identity: () => string, document: DesignDocument, source: DesignDocument) => void;
};
export class FormComponentBehaviorRegistry {
  private readonly behaviors = new Map<string, FormComponentBehavior>();
  constructor(behaviors: readonly FormComponentBehavior[]) {
    for (const behavior of behaviors) {
      const key = `${behavior.type}@${behavior.version}`;
      if (this.behaviors.has(key)) throw new Error(`Duplicate component behavior: ${key}`);
      this.behaviors.set(key, Object.freeze({...behavior}));
    }
  }
  get(node: Pick<Node, 'componentType' | 'componentVersion'>) {return this.behaviors.get(`${node.componentType}@${node.componentVersion}`);}
}
export const platformComponentBehaviors = new FormComponentBehaviorRegistry([
  {type: 'layout.tabs', version: '1', initialize: (node, identity) => ({...node, children: [1, 2].map(index => ({
    componentId: identity(), componentType: 'layout.group', componentVersion: '1', columnSpan: 24,
    label: `${node.label} ${index}`, readOnly: false, properties: {}, children: [],
  }))})},
  {type: DETAIL, version: '1', initialize: initializeDetail, validate: validateDetail, clone: cloneDetail},
  {type: MATRIX, version: '1', cells: node => ({columns: matrixSize(node).columns, managed: true}), initialize: (node, identity) => {
    const {rows, columns} = matrixSize(node); return resizeMatrix(node, rows, columns, identity);
  }, validate: validateMatrix},
  {type: CELL, version: '1', palette: false, structural: true},
]);
