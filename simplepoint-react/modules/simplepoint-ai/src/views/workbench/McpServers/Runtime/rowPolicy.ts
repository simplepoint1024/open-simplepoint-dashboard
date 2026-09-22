/** A masked or omitted desired-state value must never be written back as a form default. */
export function canEditPool(row: object): boolean {
  const value = row as Record<string, unknown>;
  const strings = ['id', 'code', 'name', 'serverId', 'imageReference', 'imageDigest', 'networkMode'];
  const numbers = ['requestedMemoryBytes', 'requestedNanoCpus', 'requestedPidsLimit', 'minReplicas', 'maxReplicas',
    'desiredReplicas', 'activationReplicas', 'prewarmNodes', 'idleTimeoutSeconds', 'replicaLifetimeSeconds'];
  return strings.every(key => typeof value[key] === 'string' && value[key] !== '***')
    && numbers.every(key => typeof value[key] === 'number' && Number.isFinite(value[key]))
    && ['secretIds', 'egressAllowlist'].every(key => Array.isArray(value[key])
      && value[key].every(item => typeof item === 'string' && item !== '***'));
}

export function canScalePool(row: object): boolean {
  const value = row as Record<string, unknown>;
  return ['minReplicas', 'maxReplicas', 'desiredReplicas'].every(key => typeof value[key] === 'number' && Number.isFinite(value[key]));
}
