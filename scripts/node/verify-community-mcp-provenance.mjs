#!/usr/bin/env node

import {spawnSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';

const root = resolve(import.meta.dirname, '../..');
const directory = resolve(
  root,
  'simplepoint-services/simplepoint-service-tool-runtime-node/testdata/community-mcp-profiles',
);
const provenance = JSON.parse(await readFile(
  resolve(directory, 'provenance.json'), 'utf8',
));
const expected = ['github', 'filesystem', 'git', 'postgresql', 'dockerhub', 'playwright'];
const remote = process.argv.includes('--remote');
const digestPattern = /^sha256:[a-f0-9]{64}$/;
const rows = [];

if (provenance.images.length !== expected.length) {
  throw new Error('Community MCP provenance must contain exactly six images');
}
for (const id of expected) {
  const image = provenance.images.find(item => item.id === id);
  if (!image || !digestPattern.test(image.manifestDigest)) {
    throw new Error(`Missing or invalid provenance digest for ${id}`);
  }
  if (!['UPSTREAM_ORIGINAL', 'CATALOG_ORIGINAL'].includes(image.sourceKind)) {
    throw new Error(`Invalid original-image provenance class for ${id}`);
  }
  if (!image.sourceRepository.startsWith('https://github.com/')) {
    throw new Error(`Source repository is not an auditable upstream GitHub URL for ${id}`);
  }
  const profile = JSON.parse(await readFile(
    resolve(directory, `${id}.profile.json`), 'utf8',
  ));
  if (!profile.artifact.reference.endsWith(`@${image.manifestDigest}`)) {
    throw new Error(`Profile/provenance digest mismatch for ${id}`);
  }
  if (profile.artifact.source !== image.sourceKind) {
    throw new Error(`Profile/provenance source class mismatch for ${id}`);
  }
  if (profile.artifact.reference.includes('open-simplepoint')) {
    throw new Error(`Community MCP ${id} unexpectedly uses a platform image`);
  }
  let currentDigest = image.manifestDigest;
  if (remote) {
    const inspect = spawnSync(
      'docker', ['buildx', 'imagetools', 'inspect', image.sourceTag],
      {encoding: 'utf8'},
    );
    if (inspect.status !== 0) {
      throw new Error(`Unable to inspect ${image.sourceTag}: ${inspect.stderr}`);
    }
    currentDigest = inspect.stdout.match(/^Digest:\s*(sha256:[a-f0-9]{64})$/m)?.[1];
    if (!currentDigest) throw new Error(`Registry returned no manifest digest for ${id}`);
    if (currentDigest !== image.manifestDigest) {
      throw new Error(
        `${image.sourceTag} moved from ${image.manifestDigest} to ${currentDigest}; `
          + 'review upstream and publish a new fixture revision',
      );
    }
  }
  rows.push({id, sourceTag: image.sourceTag, digest: currentDigest, sourceKind: image.sourceKind});
}
process.stdout.write(`${JSON.stringify({status: 'passed', remote, images: rows}, null, 2)}\n`);
