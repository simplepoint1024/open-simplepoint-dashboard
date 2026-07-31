import { readFile } from 'node:fs/promises';
import process from 'node:process';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const manifestPath = process.argv[2] ?? 'skill.json';
const schema = JSON.parse(
  await readFile(
    new URL('../schema/skill-manifest-v1.schema.json', import.meta.url),
    'utf8',
  ),
);
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const ajv = new Ajv2020({
  allErrors: true,
  strict: false,
});
addFormats(ajv);
const validate = ajv.compile(schema);

if (!validate(manifest)) {
  for (const error of validate.errors ?? []) {
    console.error(`${error.instancePath || '/'} ${error.message}`);
  }
  process.exit(1);
}

const forbiddenFields = new Set([
  'script',
  'command',
  'commands',
  'image',
  'container',
  'entrypoint',
  'executable',
  'shell',
]);
const aliases = new Map();
const stepIds = new Set();

function fail(message) {
  throw new Error(message);
}

function visitValue(value, path) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => visitValue(item, `${path}/${index}`));
    return;
  }
  if (!value || typeof value !== 'object') {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenFields.has(key.toLowerCase())) {
      fail(`${path}/${key} is an executable field and is forbidden`);
    }
    if (key === '$ref') {
      if (
        typeof child !== 'string'
        || (!child.startsWith('input.') && !child.startsWith('steps.'))
      ) {
        fail(`${path}/$ref must start with input. or steps.`);
      }
    }
    visitValue(child, `${path}/${key}`);
  }
}

function bind(alias, kind) {
  if (aliases.has(alias)) {
    fail(`Capability alias ${alias} is duplicated`);
  }
  aliases.set(alias, kind);
}

for (const binding of manifest.spec.tools ?? []) {
  bind(binding.alias, 'tool');
}
for (const binding of manifest.spec.prompts ?? []) {
  bind(binding.alias, 'prompt');
}
for (const binding of manifest.spec.resources ?? []) {
  bind(binding.alias, 'resource');
}

function visitSteps(steps) {
  for (const step of steps ?? []) {
    if (stepIds.has(step.id)) {
      fail(`Workflow step ID ${step.id} is duplicated`);
    }
    stepIds.add(step.id);
    if (step.type === 'condition') {
      visitSteps(step.then);
      visitSteps(step.else);
      continue;
    }
    if (step.type === 'parallel') {
      for (const branch of step.branches) {
        visitSteps(branch.steps);
      }
      continue;
    }
    const alias = step[step.type];
    if (aliases.get(alias) !== step.type) {
      fail(`Workflow step ${step.id} references unknown ${step.type} ${alias}`);
    }
  }
}

visitValue(manifest, '');
visitSteps(manifest.spec.workflow.steps);
console.log(`Validated declarative Skill ${manifest.metadata.name}`);
