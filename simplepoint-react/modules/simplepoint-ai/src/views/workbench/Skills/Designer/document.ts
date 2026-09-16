import type {
  DesignerEdge,
  DesignerNode,
  DesignerNodeType,
  DesignerPort,
  DesignerPosition,
  SkillDesignerDocument,
  SkillSummary,
  PromptBinding,
  ResourceBinding,
  ToolBinding,
} from './types.ts';
import {INPUT_NODE_ID, OUTPUT_NODE_ID} from './types.ts';

const controlPort = (
  nodeId: string,
  id: string,
  direction: DesignerPort['direction'],
): DesignerPort => ({
  nodeId,
  id,
  direction,
  kind: 'CONTROL',
  label: null,
  required: true,
  multiple: false,
});

export const createDesignerDocument = (skill: SkillSummary): SkillDesignerDocument => {
  const nodes: DesignerNode[] = [
    {
      id: INPUT_NODE_ID,
      type: 'INPUT',
      parentNodeId: null,
      branchId: null,
      order: 0,
      configuration: {},
      position: {x: 80, y: 180},
    },
    {
      id: OUTPUT_NODE_ID,
      type: 'OUTPUT',
      parentNodeId: null,
      branchId: null,
      order: 0,
      configuration: {},
      position: {x: 720, y: 180},
    },
  ];
  return normalizeDocument({
    schemaVersion: 'simplepoint.io/designer/v1alpha1',
    metadata: {name: skill.code, version: '0.1.0', description: skill.description ?? ''},
    inputSchema: {type: 'object', properties: {}, additionalProperties: false},
    outputSchema: {type: 'object', properties: {}, additionalProperties: false},
    tools: [],
    prompts: [],
    resources: [],
    nodes,
    ports: [],
    edges: [],
    workflowOutput: {},
    budgets: {},
    approvals: {},
    tests: [],
    viewport: {x: 0, y: 0, zoom: 1},
  });
};

const sequence = (
  nodes: DesignerNode[],
  parentNodeId: string | null = null,
  branchId: string | null = null,
) => nodes
  .filter((node) => node.type !== 'INPUT' && node.type !== 'OUTPUT')
  .filter((node) => (node.parentNodeId ?? null) === parentNodeId && (node.branchId ?? null) === branchId)
  .sort((left, right) => left.order - right.order);

const executableNodes = (document: SkillDesignerDocument) => document.nodes
  .filter((node) => node.type !== 'INPUT' && node.type !== 'OUTPUT');

const topLevelNodes = (document: SkillDesignerDocument) => sequence(document.nodes);

type SequenceLocation = {
  parentNodeId: string | null;
  branchId: string | null;
};

const locationOf = (node: DesignerNode): SequenceLocation => ({
  parentNodeId: node.parentNodeId ?? null,
  branchId: node.branchId ?? null,
});

const sameLocation = (left: SequenceLocation, right: SequenceLocation) => (
  left.parentNodeId === right.parentNodeId && left.branchId === right.branchId
);

const reorderSequence = (
  document: SkillDesignerDocument,
  location: SequenceLocation,
  orderedNodeIds: string[],
): SkillDesignerDocument => {
  const orderById = new Map(orderedNodeIds.map((id, order) => [id, order]));
  return normalizeDocument({
    ...document,
    nodes: document.nodes.map((node) => sameLocation(locationOf(node), location) && orderById.has(node.id)
      ? {...node, order: orderById.get(node.id) as number}
      : node),
  });
};

const expectedPorts = (nodes: DesignerNode[]): DesignerPort[] => nodes.flatMap((node) => {
  if (node.type === 'INPUT') return [controlPort(node.id, 'out', 'OUTPUT')];
  if (node.type === 'OUTPUT') return [controlPort(node.id, 'in', 'INPUT')];
  if (node.type === 'CONDITION') return [
    controlPort(node.id, 'in', 'INPUT'),
    controlPort(node.id, 'then', 'OUTPUT'),
    controlPort(node.id, 'else', 'OUTPUT'),
  ];
  if (node.type === 'PARALLEL') {
    const branches = Array.isArray(node.configuration.branches)
      ? node.configuration.branches as Array<Record<string, unknown>>
      : [];
    return [
      controlPort(node.id, 'in', 'INPUT'),
      ...branches.map((branch) => ({
        ...controlPort(node.id, String(branch.id ?? ''), 'OUTPUT'),
        label: String(branch.id ?? ''),
      })),
    ];
  }
  return [controlPort(node.id, 'in', 'INPUT'), controlPort(node.id, 'out', 'OUTPUT')];
});

const expectedEdges = (nodes: DesignerNode[]): DesignerEdge[] => {
  const edges: DesignerEdge[] = [];
  const addEdge = (sourceNodeId: string, sourceHandle: string, targetNodeId: string) => edges.push({
    id: `edge-${edges.length}`,
    sourceNodeId,
    sourceHandle,
    targetNodeId,
    targetHandle: 'in',
  });
  const addNodeEdges = (node: DesignerNode, successor: string) => {
    if (node.type === 'CONDITION') {
      addBranchEdges(node.id, 'then', successor);
      addBranchEdges(node.id, 'else', successor);
      return;
    }
    if (node.type === 'PARALLEL') {
      const branches = Array.isArray(node.configuration.branches)
        ? node.configuration.branches as Array<Record<string, unknown>>
        : [];
      branches.forEach((branch) => addBranchEdges(node.id, String(branch.id ?? ''), successor));
      return;
    }
    addEdge(node.id, 'out', successor);
  };
  const addBranchEdges = (parentNodeId: string, branchId: string, successor: string) => {
    const branch = sequence(nodes, parentNodeId, branchId);
    if (branch.length === 0) {
      addEdge(parentNodeId, branchId, successor);
      return;
    }
    addEdge(parentNodeId, branchId, branch[0].id);
    branch.forEach((node, index) => addNodeEdges(node, branch[index + 1]?.id ?? successor));
  };
  const top = sequence(nodes);
  if (top.length === 0) {
    addEdge(INPUT_NODE_ID, 'out', OUTPUT_NODE_ID);
    return edges;
  }
  addEdge(INPUT_NODE_ID, 'out', top[0].id);
  top.forEach((node, index) => addNodeEdges(node, top[index + 1]?.id ?? OUTPUT_NODE_ID));
  return edges;
};

export const normalizeDocument = (document: SkillDesignerDocument): SkillDesignerDocument => {
  const groups = new Map<string, DesignerNode[]>();
  executableNodes(document).forEach((node) => {
    const key = JSON.stringify([node.parentNodeId ?? null, node.branchId ?? null]);
    groups.set(key, [...(groups.get(key) ?? []), node]);
  });
  const normalizedById = new Map<string, DesignerNode>();
  groups.forEach((group) => group
    .sort((left, right) => left.order - right.order)
    .forEach((node, order) => normalizedById.set(node.id, {...node, order})));
  const virtualNodes = document.nodes.filter((node) => node.type === 'INPUT' || node.type === 'OUTPUT');
  const steps = executableNodes(document).map((node) => normalizedById.get(node.id) ?? node);
  const nodes = [...virtualNodes, ...steps];
  const usedToolAliases = new Set(
    steps
      .filter((node) => node.type === 'TOOL')
      .map((node) => String(node.configuration.tool ?? ''))
      .filter(Boolean),
  );
  const usedPromptAliases = new Set(steps
    .filter((node) => node.type === 'PROMPT')
    .map((node) => String(node.configuration.prompt ?? '')).filter(Boolean));
  const usedResourceAliases = new Set(steps
    .filter((node) => node.type === 'RESOURCE')
    .map((node) => String(node.configuration.resource ?? '')).filter(Boolean));
  return {
    ...document,
    nodes,
    tools: document.tools.filter((binding) => usedToolAliases.has(String(binding.alias ?? ''))),
    prompts: document.prompts.filter((binding) => usedPromptAliases.has(String(binding.alias ?? ''))),
    resources: document.resources.filter((binding) => usedResourceAliases.has(String(binding.alias ?? ''))),
    ports: expectedPorts(nodes),
    edges: expectedEdges(nodes),
  };
};

const nextNodeId = (document: SkillDesignerDocument, type: DesignerNodeType) => {
  const prefix = type.toLowerCase();
  let index = 1;
  while (document.nodes.some((node) => node.id === `${prefix}-${index}`)) index += 1;
  return `${prefix}-${index}`;
};

export const addCapabilityNode = (
  document: SkillDesignerDocument,
  type: 'TOOL' | 'PROMPT' | 'RESOURCE',
  position: DesignerPosition,
): {document: SkillDesignerDocument; nodeId: string} => {
  const id = nextNodeId(document, type);
  const alias = id;
  const field = type === 'TOOL' ? 'tool' : type === 'PROMPT' ? 'prompt' : 'resource';
  const node: DesignerNode = {
    id,
    type,
    parentNodeId: null,
    branchId: null,
    order: topLevelNodes(document).length,
    configuration: {[field]: alias, arguments: {}},
    position,
  };
  return {
    nodeId: id,
    document: normalizeDocument({
      ...document,
      nodes: [...document.nodes, node],
      tools: type === 'TOOL'
        ? [...document.tools, {alias, serverId: '', snapshotId: '', name: ''}]
        : document.tools,
      prompts: type === 'PROMPT'
        ? [...document.prompts, {alias, serverId: '', snapshotId: '', name: ''}]
        : document.prompts,
      resources: type === 'RESOURCE'
        ? [...document.resources, {alias, serverId: '', snapshotId: '', uri: ''}]
        : document.resources,
    }),
  };
};

export const addControlNode = (
  document: SkillDesignerDocument,
  type: 'CONDITION' | 'PARALLEL',
  position: DesignerPosition,
): {document: SkillDesignerDocument; nodeId: string} => {
  const id = nextNodeId(document, type);
  const branchIds = type === 'CONDITION' ? ['then', 'else'] : ['branch-1', 'branch-2'];
  const childNodes = branchIds.map((branchId, index): DesignerNode => ({
    id: `${id}-${branchId}-tool`,
    type: 'TOOL',
    parentNodeId: id,
    branchId,
    order: 0,
    configuration: {tool: `${id}-${branchId}-tool`, arguments: {}},
    position: {x: position.x + 260, y: position.y + (index === 0 ? -100 : 120)},
  }));
  const controlNode: DesignerNode = {
    id,
    type,
    parentNodeId: null,
    branchId: null,
    order: topLevelNodes(document).length,
    configuration: type === 'CONDITION'
      ? {condition: {isTrue: {$ref: 'input.enabled'}}}
      : {branches: branchIds.map((branchId) => ({id: branchId}))},
    position,
  };
  return {
    nodeId: id,
    document: normalizeDocument({
      ...document,
      nodes: [...document.nodes, controlNode, ...childNodes],
      tools: [
        ...document.tools,
        ...childNodes.map((node) => ({alias: node.id, serverId: '', snapshotId: '', name: ''})),
      ],
    }),
  };
};

export const addDesignerNode = (
  document: SkillDesignerDocument,
  type: 'TOOL' | 'PROMPT' | 'RESOURCE' | 'CONDITION' | 'PARALLEL',
  position: DesignerPosition,
) => type === 'CONDITION' || type === 'PARALLEL'
  ? addControlNode(document, type, position)
  : addCapabilityNode(document, type, position);

export const removeNode = (
  document: SkillDesignerDocument,
  nodeId: string,
): SkillDesignerDocument => {
  const removed = new Set([nodeId]);
  let changed = true;
  while (changed) {
    changed = false;
    document.nodes.forEach((node) => {
      if (node.parentNodeId && removed.has(node.parentNodeId) && !removed.has(node.id)) {
        removed.add(node.id);
        changed = true;
      }
    });
  }
  return normalizeDocument({...document, nodes: document.nodes.filter((node) => !removed.has(node.id))});
};

export const moveNode = (
  document: SkillDesignerDocument,
  nodeId: string,
  position: DesignerPosition,
): SkillDesignerDocument => {
  const movedNode = document.nodes.find((node) => node.id === nodeId);
  const movedDocument = {
    ...document,
    nodes: document.nodes.map((node) => node.id === nodeId ? {...node, position} : node),
  };
  if (!movedNode || movedNode.type === 'INPUT' || movedNode.type === 'OUTPUT') return movedDocument;

  const location = locationOf(movedNode);
  const orderedNodeIds = sequence(
    movedDocument.nodes,
    location.parentNodeId,
    location.branchId,
  )
    .sort((left, right) => left.position.x - right.position.x || left.order - right.order)
    .map((node) => node.id);
  return reorderSequence(movedDocument, location, orderedNodeIds);
};

export const designerConnectionErrorCodes = [
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
] as const;

export type DesignerConnectionErrorCode =
  typeof designerConnectionErrorCodes[number];

export type DesignerConnectionResult = {
  document?: SkillDesignerDocument;
  errorCode?: DesignerConnectionErrorCode;
};

const moveToIndex = (
  document: SkillDesignerDocument,
  location: SequenceLocation,
  nodeId: string,
  index: number,
) => {
  const orderedNodeIds = sequence(document.nodes, location.parentNodeId, location.branchId)
    .map((node) => node.id)
    .filter((id) => id !== nodeId);
  orderedNodeIds.splice(Math.max(0, Math.min(index, orderedNodeIds.length)), 0, nodeId);
  return reorderSequence(document, location, orderedNodeIds);
};

/**
 * Interprets a visual connection as a deterministic structured-order edit. It never stores the
 * requested edge directly: normalizeDocument remains the only source of workflow ports and edges.
 */
export const connectDesignerNodes = (
  document: SkillDesignerDocument,
  sourceNodeId?: string | null,
  sourceHandle?: string | null,
  targetNodeId?: string | null,
): DesignerConnectionResult => {
  const source = document.nodes.find((node) => node.id === sourceNodeId);
  const target = document.nodes.find((node) => node.id === targetNodeId);
  if (!source || !target) return {errorCode: 'endpointMissing'};
  if (source.id === target.id) return {errorCode: 'selfConnection'};
  if (source.type === 'OUTPUT') return {errorCode: 'outputAsSource'};
  if (target.type === 'INPUT') return {errorCode: 'inputAsTarget'};

  if (source.type === 'INPUT') {
    if (target.type === 'OUTPUT') {
      return topLevelNodes(document).length === 0
        ? {document}
        : {errorCode: 'directIoNotEmpty'};
    }
    const targetLocation = locationOf(target);
    if (!sameLocation(targetLocation, {parentNodeId: null, branchId: null})) {
      return {errorCode: 'inputTopLevelOnly'};
    }
    return {document: moveToIndex(document, targetLocation, target.id, 0)};
  }

  if (target.type === 'OUTPUT') {
    if (source.type === 'CONDITION' || source.type === 'PARALLEL') {
      return {errorCode: 'controlToOutput'};
    }
    const sourceLocation = locationOf(source);
    if (!sameLocation(sourceLocation, {parentNodeId: null, branchId: null})) {
      return {errorCode: 'branchToOutput'};
    }
    const top = topLevelNodes(document);
    return {document: moveToIndex(document, sourceLocation, source.id, top.length - 1)};
  }

  if (source.type === 'CONDITION' || source.type === 'PARALLEL') {
    const validBranchIds = document.ports
      .filter((port) => port.nodeId === source.id && port.direction === 'OUTPUT')
      .map((port) => port.id);
    if (!sourceHandle || !validBranchIds.includes(sourceHandle)) {
      return {errorCode: 'invalidBranchPort'};
    }
    const branchLocation = {parentNodeId: source.id, branchId: sourceHandle};
    if (!sameLocation(locationOf(target), branchLocation)) {
      return {errorCode: 'branchMismatch'};
    }
    return {document: moveToIndex(document, branchLocation, target.id, 0)};
  }

  const sourceLocation = locationOf(source);
  if (!sameLocation(sourceLocation, locationOf(target))) {
    return {errorCode: 'crossSequence'};
  }
  const orderedNodeIds = sequence(document.nodes, sourceLocation.parentNodeId, sourceLocation.branchId)
    .map((node) => node.id)
    .filter((id) => id !== target.id);
  const sourceIndex = orderedNodeIds.indexOf(source.id);
  if (sourceIndex < 0) return {errorCode: 'sourceNotExecutable'};
  orderedNodeIds.splice(sourceIndex + 1, 0, target.id);
  return {document: reorderSequence(document, sourceLocation, orderedNodeIds)};
};

export const toolBindingFor = (
  document: SkillDesignerDocument,
  node: DesignerNode,
): ToolBinding => {
  const alias = String(node.configuration.tool ?? '');
  const binding = document.tools.find((item) => item.alias === alias);
  return {
    alias,
    serverId: String(binding?.serverId ?? ''),
    snapshotId: String(binding?.snapshotId ?? ''),
    name: String(binding?.name ?? ''),
  };
};

export const updateToolBinding = (
  document: SkillDesignerDocument,
  nodeId: string,
  next: ToolBinding,
): SkillDesignerDocument => {
  const node = document.nodes.find((item) => item.id === nodeId);
  if (!node || node.type !== 'TOOL') return document;
  const previousAlias = String(node.configuration.tool ?? '');
  const tools = document.tools.filter((item) => item.alias !== previousAlias);
  tools.push(next);
  return normalizeDocument({
    ...document,
    tools,
    nodes: document.nodes.map((item) => item.id === nodeId
      ? {...item, configuration: {...item.configuration, tool: next.alias}}
      : item),
  });
};

export const promptBindingFor = (
  document: SkillDesignerDocument,
  node: DesignerNode,
): PromptBinding => {
  const alias = String(node.configuration.prompt ?? '');
  const binding = document.prompts.find((item) => item.alias === alias);
  return {
    alias,
    serverId: String(binding?.serverId ?? ''),
    snapshotId: String(binding?.snapshotId ?? ''),
    name: String(binding?.name ?? ''),
  };
};

export const updatePromptBinding = (
  document: SkillDesignerDocument,
  nodeId: string,
  next: PromptBinding,
): SkillDesignerDocument => {
  const node = document.nodes.find((item) => item.id === nodeId);
  if (!node || node.type !== 'PROMPT') return document;
  const previousAlias = String(node.configuration.prompt ?? '');
  return normalizeDocument({
    ...document,
    prompts: [...document.prompts.filter((item) => item.alias !== previousAlias), next],
    nodes: document.nodes.map((item) => item.id === nodeId
      ? {...item, configuration: {...item.configuration, prompt: next.alias}}
      : item),
  });
};

export const resourceBindingFor = (
  document: SkillDesignerDocument,
  node: DesignerNode,
): ResourceBinding => {
  const alias = String(node.configuration.resource ?? '');
  const binding = document.resources.find((item) => item.alias === alias);
  return {
    alias,
    serverId: String(binding?.serverId ?? ''),
    snapshotId: String(binding?.snapshotId ?? ''),
    uri: String(binding?.uri ?? ''),
    uriTemplate: String(binding?.uriTemplate ?? ''),
  };
};

export const updateResourceBinding = (
  document: SkillDesignerDocument,
  nodeId: string,
  next: ResourceBinding,
): SkillDesignerDocument => {
  const node = document.nodes.find((item) => item.id === nodeId);
  if (!node || node.type !== 'RESOURCE') return document;
  const previousAlias = String(node.configuration.resource ?? '');
  const stored = next.uriTemplate
    ? {alias: next.alias, serverId: next.serverId, snapshotId: next.snapshotId, uriTemplate: next.uriTemplate}
    : {alias: next.alias, serverId: next.serverId, snapshotId: next.snapshotId, uri: next.uri};
  return normalizeDocument({
    ...document,
    resources: [...document.resources.filter((item) => item.alias !== previousAlias), stored],
    nodes: document.nodes.map((item) => item.id === nodeId
      ? {...item, configuration: {...item.configuration, resource: next.alias}}
      : item),
  });
};

export const updateNodeArguments = (
  document: SkillDesignerDocument,
  nodeId: string,
  argumentsValue: Record<string, unknown>,
): SkillDesignerDocument => ({
  ...document,
  nodes: document.nodes.map((node) => node.id === nodeId
    ? {...node, configuration: {...node.configuration, arguments: argumentsValue}}
    : node),
});

export const updateNodeConfiguration = (
  document: SkillDesignerDocument,
  nodeId: string,
  configuration: Record<string, unknown>,
): SkillDesignerDocument => normalizeDocument({
  ...document,
  nodes: document.nodes.map((node) => node.id === nodeId ? {...node, configuration} : node),
});

export const addParallelBranch = (
  document: SkillDesignerDocument,
  nodeId: string,
): SkillDesignerDocument => {
  const parent = document.nodes.find((node) => node.id === nodeId && node.type === 'PARALLEL');
  if (!parent) return document;
  const branches = Array.isArray(parent.configuration.branches)
    ? parent.configuration.branches as Array<Record<string, unknown>>
    : [];
  let index = branches.length + 1;
  while (branches.some((branch) => branch.id === `branch-${index}`)) index += 1;
  const branchId = `branch-${index}`;
  const childId = `${nodeId}-${branchId}-tool`;
  const child: DesignerNode = {
    id: childId,
    type: 'TOOL',
    parentNodeId: nodeId,
    branchId,
    order: 0,
    configuration: {tool: childId, arguments: {}},
    position: {x: parent.position.x + 260, y: parent.position.y + index * 110},
  };
  return normalizeDocument({
    ...document,
    nodes: [
      ...document.nodes.map((node) => node.id === nodeId
        ? {...node, configuration: {...node.configuration, branches: [...branches, {id: branchId}]}}
        : node),
      child,
    ],
    tools: [...document.tools, {alias: childId, serverId: '', snapshotId: '', name: ''}],
  });
};

export const renameParallelBranch = (
  document: SkillDesignerDocument,
  nodeId: string,
  previousId: string,
  nextId: string,
): SkillDesignerDocument => {
  const parent = document.nodes.find((node) => node.id === nodeId && node.type === 'PARALLEL');
  if (!parent) return document;
  const branches = Array.isArray(parent.configuration.branches)
    ? parent.configuration.branches as Array<Record<string, unknown>>
    : [];
  return normalizeDocument({
    ...document,
    nodes: document.nodes.map((node) => {
      if (node.id === nodeId) {
        return {
          ...node,
          configuration: {
            ...node.configuration,
            branches: branches.map((branch) => branch.id === previousId ? {...branch, id: nextId} : branch),
          },
        };
      }
      return node.parentNodeId === nodeId && node.branchId === previousId ? {...node, branchId: nextId} : node;
    }),
  });
};

export const removeParallelBranch = (
  document: SkillDesignerDocument,
  nodeId: string,
  branchId: string,
): SkillDesignerDocument => {
  const parent = document.nodes.find((node) => node.id === nodeId && node.type === 'PARALLEL');
  if (!parent) return document;
  const branches = Array.isArray(parent.configuration.branches)
    ? parent.configuration.branches as Array<Record<string, unknown>>
    : [];
  if (branches.length <= 2) return document;
  const removedIds = new Set(document.nodes
    .filter((node) => node.parentNodeId === nodeId && node.branchId === branchId)
    .map((node) => node.id));
  return normalizeDocument({
    ...document,
    nodes: document.nodes
      .filter((node) => !removedIds.has(node.id))
      .map((node) => node.id === nodeId
        ? {...node, configuration: {...node.configuration, branches: branches.filter((branch) => branch.id !== branchId)}}
        : node),
  });
};
