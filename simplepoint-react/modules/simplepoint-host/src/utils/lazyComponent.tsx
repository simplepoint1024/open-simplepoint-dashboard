import React from 'react';
import {Button, Popconfirm, Result, Space} from 'antd';
import { loadRemote } from '@module-federation/runtime';
import {preloadRemoteComponentAssets} from './mfPreload';
import {createPrefetchScheduler, permitsSpeculation} from './prefetchScheduler';

type LazyModule = {default: React.ComponentType<any>};

// 简单缓存，避免重复创建 React.lazy
const lazyCache = new Map<string, React.LazyExoticComponent<React.ComponentType<any>>>();
const moduleCache = new Map<string, Promise<LazyModule>>();
const LAZY_CACHE_MAX = 50;
const prefetchScheduler = createPrefetchScheduler();

const specAliases: Record<string, string> = {
  'common/organization/tenant': 'common/platform/Tenant',
  'common/organization/AppPackage': 'common/platform/Package',
  'common/platform/tenant': 'common/platform/Tenant',
};

function getSpecCandidates(spec: string) {
  const normalized = specAliases[spec] ?? spec;
  return Array.from(new Set([normalized, spec]));
}

function cacheKey(spec: string, remoteRegistryKey?: string) {
  return `${remoteRegistryKey ?? 'default'}::${spec}`;
}

function invalidateLazyComponent(spec: string, remoteRegistryKey?: string) {
  const key = cacheKey(spec, remoteRegistryKey);
  lazyCache.delete(key);
  moduleCache.delete(key);
}

function retryRouteLoad(spec: string, remoteRegistryKey?: string) {
  invalidateLazyComponent(spec, remoteRegistryKey);
  window.dispatchEvent(new CustomEvent('sp-refresh-route'));
}

async function loadComponentModule(spec: string, remoteRegistryKey?: string): Promise<LazyModule> {
  const key = cacheKey(spec, remoteRegistryKey);
  const cached = moduleCache.get(key);
  if (cached) return cached;

  const request = (async () => {
    const candidates = getSpecCandidates(spec);
    let lastError: unknown;
    for (const candidate of candidates) {
      try {
        if (candidate.startsWith('./')) {
          throw new Error(`Unregistered local lazy component: ${candidate}`);
        }
        // Asset metadata allows the expose chunk and remote entry to start in
        // parallel instead of waiting for loadRemote's sequential discovery.
        void preloadRemoteComponentAssets(candidate).catch(() => {
          // Optional link preloads can time out even after the module is ready.
          // Only loadRemote determines whether navigation succeeds.
        });
        const loaded = await loadRemote<LazyModule>(candidate);
        if (!loaded?.default) throw new Error(`Remote component has no default export: ${candidate}`);
        return loaded;
      } catch (error) {
        lastError = error;
        console.warn(`[Mf] Failed to load remote component: ${candidate}`, error);
      }
    }
    throw lastError ?? new Error(`Unable to load remote component: ${spec}`);
  })();

  if (moduleCache.size >= LAZY_CACHE_MAX) {
    const firstKey = moduleCache.keys().next().value as string | undefined;
    if (firstKey) moduleCache.delete(firstKey);
  }
  moduleCache.set(key, request);
  void request.catch(() => {
    if (moduleCache.get(key) === request) moduleCache.delete(key);
  });
  return request;
}

/** Start loading a remote page while the user is hovering its menu item. */
export function prefetchLazyComponent(spec?: string, remoteRegistryKey?: string, immediate = false) {
  if (!spec || spec.startsWith('external:') || spec.startsWith('iframe:')) return;
  const connection = (navigator as Navigator & {connection?: {saveData?: boolean; effectiveType?: string}}).connection;
  if (!permitsSpeculation(connection)) return;
  const key = cacheKey(spec, remoteRegistryKey);
  if (immediate) {
    prefetchScheduler.cancel(key);
    void loadComponentModule(spec, remoteRegistryKey).catch(() => {});
  } else {
    prefetchScheduler.schedule(key, () => loadComponentModule(spec, remoteRegistryKey));
  }
}

export function cancelPrefetchLazyComponent(spec?: string, remoteRegistryKey?: string) {
  if (spec) prefetchScheduler.cancel(cacheKey(spec, remoteRegistryKey));
}

export function getLazyComponent(
  t: (k: string, d?: string) => string,
  spec?: string,
  remoteRegistryKey?: string,
): React.LazyExoticComponent<React.ComponentType<any>> {
  const fallback: { default: React.ComponentType<any> } = {
    default: () => (
      <Result
        status="error"
        title={t('error.remoteLoadFail', '远程资源加载失败，请稍后再试。')}
        subTitle={t('error.remoteLoadFailSub', '远程模块可能正在升级或网络暂不可用。')}
        extra={
          spec ? (
            <Space>
              <Button type="primary" onClick={() => retryRouteLoad(spec, remoteRegistryKey)}>
                {t('error.reload', '重新加载')}
              </Button>
              <Popconfirm
                title={t('error.refreshPageConfirm', '刷新会丢失未保存的内容，确定继续吗？')}
                onConfirm={() => window.location.reload()}
              >
                <Button>{t('error.refreshPage', '刷新页面获取最新版本')}</Button>
              </Popconfirm>
            </Space>
          ) : undefined
        }
      />
    )
  };
  if (!spec) return React.lazy(async () => fallback);
  const key = cacheKey(spec, remoteRegistryKey);
  const cached = lazyCache.get(key);
  if (cached) return cached;
  const s = spec as string; // 保证后续为非空字符串
  const comp = React.lazy(async () => {
    prefetchScheduler.cancel(key);
    try {
      return await loadComponentModule(s, remoteRegistryKey);
    } catch {
      return fallback;
    }
  });
  if (lazyCache.size >= LAZY_CACHE_MAX) {
    const firstKey = lazyCache.keys().next().value as string | undefined;
    if (firstKey) lazyCache.delete(firstKey);
  }
  lazyCache.set(key, comp);
  return comp;
}
