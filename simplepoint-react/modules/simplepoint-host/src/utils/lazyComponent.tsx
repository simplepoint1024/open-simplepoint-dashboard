import React from 'react';
import {Button, Result} from 'antd';
import { loadRemote } from '@module-federation/runtime';

// 简单缓存，避免重复创建 React.lazy
const lazyCache = new Map<string, React.LazyExoticComponent<React.ComponentType<any>>>();
const LAZY_CACHE_MAX = 50;

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
  lazyCache.delete(cacheKey(spec, remoteRegistryKey));
}

function retryRouteLoad(spec: string, remoteRegistryKey?: string) {
  invalidateLazyComponent(spec, remoteRegistryKey);
  window.dispatchEvent(new CustomEvent('sp-refresh-route'));
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
            <Button type="primary" onClick={() => retryRouteLoad(spec, remoteRegistryKey)}>
              {t('error.reload', '重新加载')}
            </Button>
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
    const candidates = getSpecCandidates(s);
    for (const candidate of candidates) {
      try {
        if (candidate.startsWith("./")) {
          // @ts-ignore
          return await import(`${candidate}`) as { default: React.ComponentType<any> };
        }
        return await loadRemote(`${candidate}`) as { default: React.ComponentType<any> };
      } catch (error) {
        console.warn(`[Mf] Failed to load remote component: ${candidate}`, error);
        if (candidate === candidates[candidates.length - 1]) {
          return fallback as any;
        }
      }
    }
    return fallback as any;
  });
  if (lazyCache.size >= LAZY_CACHE_MAX) {
    const firstKey = lazyCache.keys().next().value as string | undefined;
    if (firstKey) lazyCache.delete(firstKey);
  }
  lazyCache.set(key, comp);
  return comp;
}
