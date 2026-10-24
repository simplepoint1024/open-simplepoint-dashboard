import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {resolve, relative} from 'node:path';

/** Compare authenticated gateway responses with every declared federation JS/CSS asset. */
export async function verifyDeployedAssets({request, frontendRoot, state, save}) {
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  state.modules = [];
  for (const module of ['common', 'audit', 'dna', 'ai', 'app', 'workflow']) {
    const directory = resolve(frontendRoot, 'modules', 'simplepoint-' + module, 'dist/mf');
    const manifestBytes = await readFile(resolve(directory, 'mf-manifest.json'));
    const manifest = JSON.parse(manifestBytes);
    const entry = manifest.metaData.remoteEntry;
    const assets = new Set(['mf-manifest.json', (entry.path ? entry.path + '/' : '') + entry.name]);
    for (const descriptor of [...manifest.exposes, ...manifest.shared]) {
      for (const type of ['js', 'css']) for (const mode of ['sync', 'async']) {
        for (const asset of descriptor.assets[type][mode]) assets.add(asset);
      }
    }
    const pending = [...assets].sort(), verified = [];
    await Promise.all(Array.from({length: 4}, async () => {
      while (pending.length) {
        const asset = pending.shift(), local = resolve(directory, asset);
        assert(!relative(directory, local).startsWith('..'));
        const response = await request(manifest.metaData.publicPath + asset, {accept: '*/*'});
        assert.equal(response.status, 200, module + '/' + asset);
        const expected = hash(await readFile(local)), actual = hash(Buffer.from(response.body));
        assert.equal(actual, expected, module + '/' + asset + ' served bytes match build');
        verified.push({asset, sha256: actual});
      }
    }));
    state.modules.push({module, assetCount: verified.length, assets: verified.sort((a, b) => a.asset.localeCompare(b.asset))});
    await save(); console.log('PASS served manifest, entry and ' + verified.length + ' declared assets: ' + module);
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
