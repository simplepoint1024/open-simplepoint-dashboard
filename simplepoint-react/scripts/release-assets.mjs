export function isRetainedAsset(file) {
  return /^(?:assets\/|(?:common|auditing|dna|ai|app|workflow)\/mf\/)(?:[\w-]+\/)*[\w.-]+\.[a-f0-9]{8,}\.(?:js|css|svg|woff2?)$/i.test(file);
}

export function retainReleases(previous, current, keep = 3) {
  if (!Number.isInteger(keep) || keep < 2) throw new Error('Keep at least two releases');
  for (const release of [...previous, current]) {
    if (typeof release.id !== 'string' || !Array.isArray(release.files) || !release.files.every(isRetainedAsset)) {
      throw new Error('Invalid release manifest; refusing to prune');
    }
  }
  const releases = [current, ...previous.filter(release => release.id !== current.id)].slice(0, keep);
  const required = new Set(releases.flatMap(release => release.files));
  const remove = [...new Set(previous.flatMap(release => release.files))].filter(file => !required.has(file));
  return {releases, remove};
}
