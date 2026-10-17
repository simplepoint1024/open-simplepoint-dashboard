import type {FormComponentDefinition as Node} from '../../FormRuntime/contracts.ts';

export const MATRIX = 'layout.matrix';
export const CELL = 'layout.cell';
export const MATRIX_LIMIT = 6;
export function matrixSize(node: Node) {
  const read = (key: string) => {
    const value = node.properties[key];
    const result = Number(value?.value);
    if (value?.kind !== 'INTEGER' || !Number.isInteger(result) || result < 1 || result > MATRIX_LIMIT) {
      throw new Error('matrix-size');
    }
    return result;
  };
  return {rows: read('rows'), columns: read('columns')};
}

/** Stable cell identities survive resizing. Nonempty cells can never be discarded implicitly. */
export function resizeMatrix(node: Node, rows: number, columns: number, identity: () => string = () => crypto.randomUUID()): Node {
  const previous = node.children.length ? matrixSize(node) : {rows: 0, columns: 0};
  const next = structuredClone(node);
  next.properties = {...node.properties, rows: {kind: 'INTEGER', value: String(rows)}, columns: {kind: 'INTEGER', value: String(columns)}};
  matrixSize(next);
  for (let index = 0; index < node.children.length; index++) {
    if ((Math.floor(index / previous.columns) >= rows || index % previous.columns >= columns) && node.children[index].children.length) {
      throw new Error('matrix-occupied');
    }
  }
  next.children = Array.from({length: rows * columns}, (_, index) => {
    const row = Math.floor(index / columns), column = index % columns;
    const existing = row < previous.rows && column < previous.columns ? node.children[row * previous.columns + column] : undefined;
    return existing ? structuredClone(existing) : {componentId: identity(), componentType: CELL, componentVersion: '1',
      columnSpan: 24, readOnly: false, properties: {}, children: []};
  });
  return next;
}

export function validateMatrix(node: Node) {
  const {rows, columns} = matrixSize(node);
  if (node.children.length !== rows * columns || node.children.some(child => child.componentType !== CELL || child.componentVersion !== '1')) {
    throw new Error('matrix-cells');
  }
}
