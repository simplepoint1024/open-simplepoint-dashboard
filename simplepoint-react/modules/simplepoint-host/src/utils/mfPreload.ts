import {getInstance} from '@module-federation/runtime';

export type RemotePreloadTarget = {
  nameOrAlias: string;
  expose: string;
};

/** Convert `remote/expose/path` into the shape expected by the MF runtime. */
export function parseRemotePreloadTarget(spec: string): RemotePreloadTarget | undefined {
  if (!spec || spec.startsWith('.') || spec.includes(':')) return undefined;
  const separator = spec.indexOf('/');
  if (separator <= 0 || separator === spec.length - 1) return undefined;
  return {
    nameOrAlias: spec.slice(0, separator),
    expose: spec.slice(separator + 1),
  };
}

/** Preload only the synchronous assets for one exposed page. */
export async function preloadRemoteComponentAssets(spec: string): Promise<void> {
  const target = parseRemotePreloadTarget(spec);
  const mf = getInstance();
  if (!target || !mf) return;
  await mf.preloadRemote([{
    nameOrAlias: target.nameOrAlias,
    exposes: [target.expose],
    resourceCategory: 'sync',
    share: false,
    depsRemote: false,
  }]);
}
