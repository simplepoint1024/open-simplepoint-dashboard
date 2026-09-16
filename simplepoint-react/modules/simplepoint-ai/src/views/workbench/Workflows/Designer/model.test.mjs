import assert from 'node:assert/strict';
import test from 'node:test';
import {
  dependencyReferencesFromManifest,
  documentFromManifest,
  hasPath,
  manifestFromDocument,
  newWorkflowNode,
  validateWorkflowDocument,
} from './model.ts';

const manifest = {
  apiVersion: 'simplepoint.io/v1alpha1',
  kind: 'AgentWorkflow',
  spec: {
    inputSchema: {type: 'object'},
    outputSchema: {type: 'object'},
    nodes: [
      {id: 'first', type: 'wait', durationSeconds: 1},
      {id: 'done', type: 'end'},
    ],
    edges: [{from: 'first', to: 'done'}],
  },
};

test('manifest round trip preserves workflow settings and graph', () => {
  const document = documentFromManifest(manifest, {first: {x: 12, y: 34}});
  const result = manifestFromDocument(document);
  assert.deepEqual(result.spec.inputSchema, {type: 'object'});
  assert.deepEqual(result.spec.edges, [{from: 'first', to: 'done'}]);
  assert.deepEqual(document.nodes[0].position, {x: 12, y: 34});
  assert.deepEqual(validateWorkflowDocument(document), []);
});

test('cycle detection rejects a back edge', () => {
  const document = documentFromManifest(manifest);
  document.edges.push({id: 'back', source: 'done', target: 'first'});
  assert.equal(hasPath(document.edges, 'done', 'first'), true);
  assert.ok(validateWorkflowDocument(document).some((item) => item.code === 'graph.cycle'));
});

test('dependency nodes require immutable version selections', () => {
  const document = documentFromManifest(manifest);
  document.nodes.push(newWorkflowNode('agent', document.nodes, {humanTitle: 'Localized title'}));
  assert.ok(validateWorkflowDocument(document).some(
    (item) => item.code === 'node.agentDependency',
  ));
});

test('dependency references include Agent, Skill, and compensation versions once', () => {
  const dependencyManifest = structuredClone(manifest);
  dependencyManifest.spec.nodes = [
    {
      id: 'agent',
      type: 'agent',
      agentId: 'agent-resource',
      versionId: 'agent-version',
      compensation: {skillId: 'compensation-resource', versionId: 'skill-version'},
    },
    {
      id: 'skill',
      type: 'skill',
      skillId: 'skill-resource',
      versionId: 'skill-version',
    },
    {id: 'done', type: 'end'},
  ];
  dependencyManifest.spec.edges = [{from: 'agent', to: 'done'}];

  assert.deepEqual(dependencyReferencesFromManifest(dependencyManifest), {
    AGENT: ['agent-version'],
    SKILL: ['skill-version'],
  });
  assert.deepEqual(validateWorkflowDocument(documentFromManifest(dependencyManifest)), []);
});

test('partial compensation bindings are rejected', () => {
  const dependencyManifest = structuredClone(manifest);
  dependencyManifest.spec.nodes[0].compensation = {versionId: 'skill-version'};
  assert.ok(validateWorkflowDocument(documentFromManifest(dependencyManifest)).some(
    (item) => item.code === 'node.compensationDependency',
  ));
});

test('localized human task defaults survive manifest round trips', () => {
  const document = documentFromManifest(structuredClone(manifest));
  const localizedTitle = '人工复核';
  const humanNode = newWorkflowNode('human', document.nodes, {
    humanTitle: localizedTitle,
  });
  document.nodes.push(humanNode);

  const persisted = manifestFromDocument(document);
  const persistedHuman = persisted.spec.nodes.find(
    (node) => node.id === humanNode.id,
  );
  assert.equal(persistedHuman.title, localizedTitle);

  const restored = documentFromManifest(persisted);
  assert.equal(
    restored.nodes.find((node) => node.id === humanNode.id)?.definition.title,
    localizedTitle,
  );
});

test('contract budgets and conditions are checked before version creation', () => {
  const document = documentFromManifest(structuredClone(manifest));
  document.manifest.spec.inputSchema = {type: 'array'};
  document.manifest.spec.budgets = {maximumParallelism: 65};
  document.nodes.push({
    id: 'decision',
    type: 'condition',
    position: {x: 0, y: 0},
    definition: {id: 'decision', type: 'condition', condition: {unknown: true}},
  });
  const codes = validateWorkflowDocument(document).map((item) => item.code);
  assert.ok(codes.includes('spec.inputSchema'));
  assert.ok(codes.includes('spec.maximumParallelism'));
  assert.ok(codes.includes('node.condition'));
});
