import assert from 'node:assert/strict';
import test from 'node:test';
import {
  addDesignerNode,
  connectDesignerNodes,
  createDesignerDocument,
  designerConnectionErrorCodes,
} from './document.ts';
import {INPUT_NODE_ID, OUTPUT_NODE_ID} from './types.ts';

const createDocument = () => createDesignerDocument({
  id: 'skill-1',
  code: 'test-skill',
  name: 'Test Skill',
});

test('connection validation returns stable locale-neutral error codes', () => {
  const document = createDocument();

  assert.equal(
    connectDesignerNodes(document, 'missing', 'out', OUTPUT_NODE_ID).errorCode,
    'endpointMissing',
  );
  const added = addDesignerNode(document, 'TOOL', {x: 320, y: 180});
  assert.equal(
    connectDesignerNodes(added.document, added.nodeId, 'out', INPUT_NODE_ID).errorCode,
    'inputAsTarget',
  );
  assert.equal(
    connectDesignerNodes(document, OUTPUT_NODE_ID, 'out', INPUT_NODE_ID).errorCode,
    'outputAsSource',
  );
  assert.equal(
    connectDesignerNodes(document, INPUT_NODE_ID, 'out', INPUT_NODE_ID).errorCode,
    'selfConnection',
  );
});

test('every reachable structured connection rule returns its stable code', () => {
  const document = createDocument();
  const tool = addDesignerNode(document, 'TOOL', {x: 320, y: 180});
  assert.equal(
    connectDesignerNodes(
      tool.document,
      INPUT_NODE_ID,
      'out',
      OUTPUT_NODE_ID,
    ).errorCode,
    'directIoNotEmpty',
  );

  const condition = addDesignerNode(
    document,
    'CONDITION',
    {x: 320, y: 180},
  );
  const thenNodeId = `${condition.nodeId}-then-tool`;
  const elseNodeId = `${condition.nodeId}-else-tool`;
  assert.equal(
    connectDesignerNodes(
      condition.document,
      INPUT_NODE_ID,
      'out',
      thenNodeId,
    ).errorCode,
    'inputTopLevelOnly',
  );
  assert.equal(
    connectDesignerNodes(
      condition.document,
      condition.nodeId,
      'then',
      OUTPUT_NODE_ID,
    ).errorCode,
    'controlToOutput',
  );
  assert.equal(
    connectDesignerNodes(
      condition.document,
      thenNodeId,
      'out',
      OUTPUT_NODE_ID,
    ).errorCode,
    'branchToOutput',
  );
  assert.equal(
    connectDesignerNodes(
      condition.document,
      condition.nodeId,
      'invalid-branch',
      thenNodeId,
    ).errorCode,
    'invalidBranchPort',
  );
  assert.equal(
    connectDesignerNodes(
      condition.document,
      condition.nodeId,
      'then',
      elseNodeId,
    ).errorCode,
    'branchMismatch',
  );
  assert.equal(
    connectDesignerNodes(
      condition.document,
      thenNodeId,
      'out',
      elseNodeId,
    ).errorCode,
    'crossSequence',
  );
});

test('the public connection error code inventory remains complete and unique', () => {
  assert.equal(designerConnectionErrorCodes.length, 12);
  assert.equal(new Set(designerConnectionErrorCodes).size, 12);
  assert.deepEqual(designerConnectionErrorCodes, [
    'endpointMissing',
    'selfConnection',
    'outputAsSource',
    'inputAsTarget',
    'directIoNotEmpty',
    'inputTopLevelOnly',
    'controlToOutput',
    'branchToOutput',
    'invalidBranchPort',
    'branchMismatch',
    'crossSequence',
    'sourceNotExecutable',
  ]);
});

test('an empty designer still accepts the direct input-to-output connection', () => {
  const document = createDocument();
  const result = connectDesignerNodes(document, INPUT_NODE_ID, 'out', OUTPUT_NODE_ID);

  assert.equal(result.errorCode, undefined);
  assert.equal(result.document, document);
});
