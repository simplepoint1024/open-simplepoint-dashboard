import assert from 'node:assert/strict';
import test from 'node:test';
import {isRetainedAsset, retainReleases} from './release-assets.mjs';

const asset = n => `common/mf/page.${String(n).padStart(10, '0')}.js`;
test('archive allows only versioned frontend assets, never APIs, manifests or traversal', () => {
  for (const file of ['assets/js/async/page.12345678.js', asset(1), 'dna/mf/style.abcdef1234.css', 'app/mf/page.12345678.js', 'workflow/mf/style.12345678.css']) assert.ok(isRetainedAsset(file));
  for (const file of ['../assets/page.12345678.js', 'common/mf/../../secret.12345678.js', 'common/mf/mf-manifest.json', 'common/users/me', 'assets/server.key', '/assets/page.12345678.js']) assert.equal(isRetainedAsset(file), false);
});
test('retains three releases and deletes only assets no retained release references', () => {
  const previous = [3, 2, 1].map(id => ({id: String(id), files: [asset(id), asset(99)]}));
  const result = retainReleases(previous, {id: '4', files: [asset(4), asset(99)]});
  assert.deepEqual(result.releases.map(release => release.id), ['4', '3', '2']);
  assert.deepEqual(result.remove, [asset(1)]);
});
test('repeated archival is idempotent and invalid metadata cannot authorize deletion', () => {
  const release = {id: '1', files: [asset(1)]};
  assert.deepEqual(retainReleases([release], release), {releases: [release], remove: []});
  assert.throws(() => retainReleases([{id: 'bad', files: ['../../secret']}], release));
  assert.throws(() => retainReleases([], release, 1));
});
