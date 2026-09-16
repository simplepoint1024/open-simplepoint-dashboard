export type WorkflowDesignerNodeType =
  | 'agent'
  | 'skill'
  | 'human'
  | 'wait'
  | 'condition'
  | 'parallel'
  | 'end';

export type WorkflowDesignerNode = {
  id: string;
  type: WorkflowDesignerNodeType;
  position: {x: number; y: number};
  definition: Record<string, unknown>;
};

export type WorkflowDesignerEdge = {
  id: string;
  source: string;
  target: string;
  condition?: Record<string, unknown>;
};

export type WorkflowDesignerDocument = {
  manifest: Record<string, unknown>;
  nodes: WorkflowDesignerNode[];
  edges: WorkflowDesignerEdge[];
};

export type WorkflowDesignerDiagnostic = {
  code: string;
  nodeId?: string;
  edgeId?: string;
};

export type WorkflowDependencyReferenceIds = {
  AGENT: string[];
  SKILL: string[];
};

const isObject = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value);

const nodeTypes = new Set<WorkflowDesignerNodeType>([
  'agent',
  'skill',
  'human',
  'wait',
  'condition',
  'parallel',
  'end',
]);

const identifier = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;

const positiveInteger = (value: unknown, maximum: number) =>
  typeof value === 'number'
  && Number.isInteger(value)
  && value > 0
  && value <= maximum;

const validObjectSchema = (value: unknown) => isObject(value)
  && (value.type === undefined || value.type === 'object')
  && (value.properties === undefined || isObject(value.properties))
  && (value.required === undefined || (Array.isArray(value.required)
    && value.required.every((item) => typeof item === 'string')));

const validCondition = (value: unknown, depth = 0): boolean => {
  if (depth > 8 || !isObject(value)) return false;
  const keys = Object.keys(value);
  if (keys.length !== 1) return false;
  const operator = keys[0];
  const operand = value[operator];
  if (operator === 'all' || operator === 'any') {
    return Array.isArray(operand)
      && operand.length > 0
      && operand.length <= 16
      && operand.every((item) => validCondition(item, depth + 1));
  }
  if (operator === 'not') return validCondition(operand, depth + 1);
  return ['equals', 'isTrue', 'notEquals'].includes(operator)
    && operand !== null
    && operand !== undefined;
};

export const parseWorkflowManifest = (
  value: string,
): Record<string, unknown> | undefined => {
  try {
    const parsed: unknown = JSON.parse(value);
    return isObject(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
};

export const documentFromManifest = (
  manifest: Record<string, unknown>,
  savedPositions: Record<string, {x: number; y: number}> = {},
): WorkflowDesignerDocument => {
  const spec = isObject(manifest.spec) ? manifest.spec : {};
  const rawNodes = Array.isArray(spec.nodes) ? spec.nodes : [];
  const nodes = rawNodes.filter(isObject).map((definition, index) => {
    const rawType = String(definition.type ?? '').toLowerCase();
    const type = nodeTypes.has(rawType as WorkflowDesignerNodeType)
      ? rawType as WorkflowDesignerNodeType : 'end';
    const id = String(definition.id ?? `node-${index + 1}`);
    return {
      id,
      type,
      position: savedPositions[id] ?? {
        x: 60 + (index % 4) * 240,
        y: 60 + Math.floor(index / 4) * 150,
      },
      definition: {...definition, id, type},
    };
  });
  const rawEdges = Array.isArray(spec.edges) ? spec.edges : [];
  const edges = rawEdges.filter(isObject).map((edge, index) => ({
    id: `edge-${index}-${String(edge.from)}-${String(edge.to)}`,
    source: String(edge.from ?? ''),
    target: String(edge.to ?? ''),
    ...(isObject(edge.condition) ? {condition: edge.condition} : {}),
  }));
  return {manifest, nodes, edges};
};

export const dependencyReferencesFromManifest = (
  manifest: Record<string, unknown>,
): WorkflowDependencyReferenceIds => {
  const references = {
    AGENT: new Set<string>(),
    SKILL: new Set<string>(),
  };
  documentFromManifest(manifest).nodes.forEach((node) => {
    const versionId = typeof node.definition.versionId === 'string'
      ? node.definition.versionId.trim() : '';
    if (versionId && node.type === 'agent') references.AGENT.add(versionId);
    if (versionId && node.type === 'skill') references.SKILL.add(versionId);
    const compensation = isObject(node.definition.compensation)
      ? node.definition.compensation : undefined;
    const compensationVersionId = typeof compensation?.versionId === 'string'
      ? compensation.versionId.trim() : '';
    if (compensationVersionId) references.SKILL.add(compensationVersionId);
  });
  return {
    AGENT: Array.from(references.AGENT),
    SKILL: Array.from(references.SKILL),
  };
};

export const manifestFromDocument = (
  document: WorkflowDesignerDocument,
): Record<string, unknown> => {
  const spec = isObject(document.manifest.spec) ? document.manifest.spec : {};
  return {
    ...document.manifest,
    spec: {
      ...spec,
      nodes: document.nodes.map((node) => ({
        ...node.definition,
        id: node.id,
        type: node.type,
      })),
      edges: document.edges.map((edge) => ({
        from: edge.source,
        to: edge.target,
        ...(edge.condition ? {condition: edge.condition} : {}),
      })),
    },
  };
};

export const hasPath = (
  edges: WorkflowDesignerEdge[],
  from: string,
  to: string,
) => {
  const outgoing = new Map<string, string[]>();
  edges.forEach((edge) => outgoing.set(
    edge.source,
    [...(outgoing.get(edge.source) ?? []), edge.target],
  ));
  const pending = [from];
  const visited = new Set<string>();
  while (pending.length) {
    const current = pending.pop() as string;
    if (current === to) return true;
    if (visited.has(current)) continue;
    visited.add(current);
    pending.push(...(outgoing.get(current) ?? []));
  }
  return false;
};

export const validateWorkflowDocument = (
  document: WorkflowDesignerDocument,
): WorkflowDesignerDiagnostic[] => {
  const diagnostics: WorkflowDesignerDiagnostic[] = [];
  const spec = isObject(document.manifest.spec) ? document.manifest.spec : {};
  if (!validObjectSchema(spec.inputSchema)) diagnostics.push({code: 'spec.inputSchema'});
  if (!validObjectSchema(spec.outputSchema)) diagnostics.push({code: 'spec.outputSchema'});
  const budgets = isObject(spec.budgets) ? spec.budgets : {};
  if (budgets.maximumDurationSeconds !== undefined
    && !positiveInteger(budgets.maximumDurationSeconds, 604800)) {
    diagnostics.push({code: 'spec.maximumDurationSeconds'});
  }
  if (budgets.maximumNodeExecutions !== undefined
    && !positiveInteger(budgets.maximumNodeExecutions, 4096)) {
    diagnostics.push({code: 'spec.maximumNodeExecutions'});
  }
  if (budgets.maximumParallelism !== undefined
    && !positiveInteger(budgets.maximumParallelism, 64)) {
    diagnostics.push({code: 'spec.maximumParallelism'});
  }
  const failurePolicy = isObject(spec.failurePolicy) ? spec.failurePolicy : {};
  if (failurePolicy.mode !== undefined
    && !['FAIL_FAST', 'CONTINUE'].includes(String(failurePolicy.mode))) {
    diagnostics.push({code: 'spec.failureMode'});
  }
  if (failurePolicy.compensation !== undefined
    && !['NONE', 'REVERSE_SUCCEEDED'].includes(String(failurePolicy.compensation))) {
    diagnostics.push({code: 'spec.compensation'});
  }
  if (document.nodes.length === 0) diagnostics.push({code: 'nodes.empty'});
  if (document.nodes.length > 128) diagnostics.push({code: 'nodes.maximum'});
  if (document.edges.length > 512) diagnostics.push({code: 'edges.maximum'});
  const ids = new Set<string>();
  document.nodes.forEach((node) => {
    if (!identifier.test(node.id)) diagnostics.push({code: 'node.id', nodeId: node.id});
    if (ids.has(node.id)) diagnostics.push({code: 'node.duplicate', nodeId: node.id});
    ids.add(node.id);
    if (node.type === 'agent'
      && (!node.definition.agentId || !node.definition.versionId)) {
      diagnostics.push({code: 'node.agentDependency', nodeId: node.id});
    }
    if (node.type === 'skill'
      && (!node.definition.skillId || !node.definition.versionId)) {
      diagnostics.push({code: 'node.skillDependency', nodeId: node.id});
    }
    if (node.definition.compensation !== undefined) {
      const compensation = isObject(node.definition.compensation)
        ? node.definition.compensation : {};
      if (!compensation.skillId || !compensation.versionId) {
        diagnostics.push({code: 'node.compensationDependency', nodeId: node.id});
      }
    }
    if (node.type === 'human'
      && (!node.definition.title || !node.definition.timeoutSeconds)) {
      diagnostics.push({code: 'node.human', nodeId: node.id});
    }
    if (node.type === 'wait' && !node.definition.durationSeconds) {
      diagnostics.push({code: 'node.wait', nodeId: node.id});
    }
    if (node.type === 'condition' && !validCondition(node.definition.condition)) {
      diagnostics.push({code: 'node.condition', nodeId: node.id});
    }
  });
  const edgePairs = new Set<string>();
  document.edges.forEach((edge) => {
    if (!ids.has(edge.source) || !ids.has(edge.target)) {
      diagnostics.push({code: 'edge.endpoint', edgeId: edge.id});
    }
    if (edge.source === edge.target) diagnostics.push({code: 'edge.self', edgeId: edge.id});
    if (edge.condition !== undefined && !validCondition(edge.condition)) {
      diagnostics.push({code: 'edge.condition', edgeId: edge.id});
    }
    const pair = `${edge.source}\u0000${edge.target}`;
    if (edgePairs.has(pair)) diagnostics.push({code: 'edge.duplicate', edgeId: edge.id});
    edgePairs.add(pair);
  });
  if (document.edges.some((edge) => edge.source === edge.target || hasPath(
    document.edges.filter((candidate) => candidate.id !== edge.id),
    edge.target,
    edge.source,
  ))) diagnostics.push({code: 'graph.cycle'});
  return diagnostics;
};

export const newWorkflowNode = (
  type: WorkflowDesignerNodeType,
  existing: WorkflowDesignerNode[],
  defaults: {humanTitle: string},
): WorkflowDesignerNode => {
  let sequence = existing.length + 1;
  let id = `${type}-${sequence}`;
  const ids = new Set(existing.map((node) => node.id));
  while (ids.has(id)) id = `${type}-${++sequence}`;
  const base: Record<string, unknown> = {id, type, name: id};
  if (type === 'human') {
    Object.assign(base, {
      title: defaults.humanTitle,
      timeoutSeconds: 3600,
      timeoutAction: 'FAIL',
      inputSchema: {type: 'object', additionalProperties: true},
    });
  }
  if (type === 'wait') base.durationSeconds = 60;
  if (type === 'condition') base.condition = {isTrue: {$ref: 'input.approved'}};
  return {
    id,
    type,
    position: {
      x: 80 + (existing.length % 4) * 220,
      y: 80 + Math.floor(existing.length / 4) * 140,
    },
    definition: base,
  };
};
