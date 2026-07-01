// 抽离的 mocks 合并逻辑（跨包通用）
// 由调用方（各 app）提供 require.context 生成的上下文对象，避免跨包别名/路径问题

export type RequireContextLike = {
  keys: () => string[];
  (id: string): any;
};

// 判断是否可能是 msw handler（宽松过滤，避免误收集普通数组）
const isHandler = (val: any): boolean => {
  if (!val || typeof val !== 'object') return false;
  const keys = Object.keys(val);
  return keys.includes('resolver') || keys.includes('logLevel') || keys.includes('info');
};

export function collectMockHandlersFromContext(ctx: RequireContextLike): any[] {
  const handlers: any[] = [];
  ctx.keys().forEach((key) => {
    const mod = ctx(key);
    const exps: any[] = [mod?.default, mod?.handlers];
    Object.values(mod).forEach((v) => exps.push(v));
    exps.forEach((exp) => {
      if (!exp) return;
      if (Array.isArray(exp)) {
        exp.forEach((item) => {
          if (isHandler(item)) handlers.push(item);
        });
      } else if (isHandler(exp)) {
        handlers.push(exp);
      }
    });
  });
  return handlers;
}

// 简单缓存；同一个 ctx 在未 force 时返回缓存结果
const cache = new WeakMap<Function, any[]>();
export function useMocks(ctx: RequireContextLike, options?: { force?: boolean }): any[] {
  const key = ctx as unknown as Function;
  if (!options?.force && cache.has(key)) return cache.get(key)!;
  const result = collectMockHandlersFromContext(ctx);
  cache.set(key, result);
  return result;
}
