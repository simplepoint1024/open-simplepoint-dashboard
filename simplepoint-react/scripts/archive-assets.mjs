// Run BEFORE replacing the running frontend containers. Archives static code only.
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {copyFile, mkdir, mkdtemp, readFile, rename, rm, rmdir, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {parseArgs} from 'node:util';
import {isRetainedAsset, retainReleases} from './release-assets.mjs';

const {values} = parseArgs({options: {project: {type: 'string'}, output: {type: 'string'}}});
if (!values.project || !/^[a-z0-9-]+$/.test(values.project) || !values.output) {
  throw new Error('Usage: node scripts/archive-assets.mjs --project PROJECT --output /absolute/path/asset-archive');
}
const output = path.resolve(values.output);
if (!path.isAbsolute(values.output) || path.basename(output) !== 'asset-archive') throw new Error('Use an explicit asset-archive directory');
await mkdir(output, {recursive: true, mode: 0o755});
const lock = path.join(output, '.archive-lock');
await mkdir(lock); // Refuse concurrent archival/pruning.
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-release-assets-'));
const run = (cmd, args) => execFileSync(cmd, args, {encoding: 'utf8', maxBuffer: 20 * 1024 * 1024});
const digest = createHash('sha256');
const files = [];
const readOptional = async file => {
  try { return await readFile(file); } catch (error) { if (error.code === 'ENOENT') return undefined; throw error; }
};
try {
  for (const service of ['host', 'common', 'auditing', 'dna', 'ai', 'app', 'workflow']) {
    const container = `${values.project}-${service}-1`;
    const imageId = run('docker', ['inspect', container, '--format', '{{.Image}}']).trim();
    digest.update(`${service}:${imageId}\n`);
    const jar = path.join(temporary, `${service}.jar`);
    run('docker', ['cp', `${container}:/opt/simplepoint/lib/simplepoint-service-${service}.jar`, jar]);
    const toOutput = entry => service === 'host' ? entry.slice('static/'.length) : `${service}/${entry.slice('static/'.length)}`;
    const entries = run('unzip', ['-Z1', jar]).split('\n').filter(entry => entry.startsWith('static/') && isRetainedAsset(toOutput(entry)));
    if (!entries.length) throw new Error(`No hashed assets found for ${service}; refusing incomplete archive`);
    const extracted = path.join(temporary, service);
    run('unzip', ['-q', jar, ...entries, '-d', extracted]);
    for (const entry of entries) {
      const relative = toOutput(entry);
      const destination = path.join(output, relative);
      const source = path.join(extracted, entry);
      const existing = await readOptional(destination);
      if (existing && !existing.equals(await readFile(source))) throw new Error(`Immutable asset collision: ${relative}`);
      if (!existing) {
        await mkdir(path.dirname(destination), {recursive: true, mode: 0o755});
        await copyFile(source, `${destination}.partial`);
        await rename(`${destination}.partial`, destination);
      }
      files.push(relative);
    }
  }
  const indexPath = path.join(output, 'releases.json');
  const previous = await readOptional(indexPath);
  const current = {id: digest.digest('hex'), createdAt: new Date().toISOString(), files: files.sort()};
  const result = retainReleases(previous ? JSON.parse(previous.toString()) : [], current);
  await writeFile(`${indexPath}.partial`, JSON.stringify(result.releases, null, 2), {mode: 0o644});
  await rename(`${indexPath}.partial`, indexPath);
  for (const relative of result.remove) await rm(path.join(output, relative), {force: true});
  console.log(`Archived ${files.length} assets; retained ${result.releases.length} releases; pruned ${result.remove.length} unreferenced assets.`);
} finally {
  await rm(temporary, {recursive: true, force: true});
  await rmdir(lock);
}
