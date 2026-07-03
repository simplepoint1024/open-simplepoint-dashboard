import {
  ensureContextId,
  getStoredContextId,
  getStoredRoleId,
  getStoredTenantId,
  shouldAutoEnsureContextId,
  shouldUseTenantContext,
} from './contextId';
import { redirectToLogin } from './session';

// 自定义错误类型，方便上层捕获和处理
export class HttpError extends Error {
  status: number;
  statusText: string;
  body?: string;
  /** 面向用户的错误描述，供调用方展示在 UI 提示中 */
  userMessage?: string;

  constructor(status: number, statusText: string, body?: string) {
    super(`HTTP ${status} ${statusText}`);
    this.status = status;
    this.statusText = statusText;
    this.body = body;
  }
}

/** 从后端响应体中提取人类可读的错误信息（Spring Boot / RFC 7807 格式） */
function extractUserMessage(body?: string): string | undefined {
  if (!body?.trim()) return undefined;
  try {
    const json = JSON.parse(body);
    return (typeof json.message === 'string' && json.message)
      || (typeof json.detail === 'string' && json.detail)
      || (typeof json.title === 'string' && json.title)
      || undefined;
  } catch {
    return undefined;
  }
}

// Console-only error logging (no UI popups — page-level handlers show messages)
const logError = (titleKey: string, fallbackTitle: string, desc?: string) => {
  try {
    const t: ((k: string, f?: string) => string) | undefined =
      typeof window !== 'undefined' ? (window as any)?.spI18n?.t : undefined;
    const title = t ? t(titleKey, fallbackTitle) : fallbackTitle;
    console.warn(`[API] ${title}`, desc ?? '');
  } catch {}
};

let unauthorizedModalOpen = false;

const getI18nT = () =>
  typeof window !== 'undefined' ? (window as any)?.spI18n?.t as ((key: string, fallback?: string, params?: Record<string, unknown>) => string) | undefined : undefined;

const getStatusDescription = (method: string, url: string, status: number, statusText: string, body?: string) => {
  const snippet = body?.trim()?.slice(0, 500);
  return `${method} ${url}\nHTTP ${status} ${statusText}${snippet ? `\n${snippet}` : ''}`;
};

async function handleHttpStatus(method: string, url: string, response: Response, body?: string) {
  const t = getI18nT();
  const desc = getStatusDescription(method, url, response.status, response.statusText, body);

  if (response.status === 401) {
    if (unauthorizedModalOpen) {
      return;
    }
    unauthorizedModalOpen = true;
    try {
      const { Modal } = await import('antd');
      await new Promise<void>((resolve) => {
        Modal.confirm({
          title: t?.('error.unauthorized.title', '登录状态已失效') ?? '登录状态已失效',
          content: t?.('error.unauthorized.content', '检测到当前登录状态失效。你可以留在当前页面，或返回登录页面重新登录。')
            ?? '检测到当前登录状态失效。你可以留在当前页面，或返回登录页面重新登录。',
          okText: t?.('error.unauthorized.goLogin', '返回登录页') ?? '返回登录页',
          cancelText: t?.('error.unauthorized.stay', '留在当前页') ?? '留在当前页',
          centered: true,
          onOk: async () => {
            resolve();
            await redirectToLogin();
          },
          onCancel: () => resolve(),
        });
      });
    } finally {
      unauthorizedModalOpen = false;
    }
    return;
  }

  if (response.status === 403) {
    logError('error.forbidden', '无使用权限', desc);
    return;
  }

  if (response.status >= 500) {
    logError('error.server', '服务暂时不可用', desc);
    return;
  }

  logError('error.requestFailed', '请求失败', desc);
}

// 通用请求方法
export async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const method = (options?.method || 'GET').toUpperCase();
  const body = options?.body;
  const isFormDataBody = typeof FormData !== 'undefined' && body instanceof FormData;
  const useTenantContext = shouldUseTenantContext(url);

  const tenantId = getStoredTenantId()?.trim();
  const roleId = useTenantContext ? getStoredRoleId(tenantId)?.trim() : undefined;
  let contextId: string | undefined = useTenantContext ? getStoredContextId(tenantId, roleId) : undefined;

  const mergedHeaders: Record<string, any> = {
    ...(isFormDataBody ? {} : {'Content-Type': 'application/json'}),
    ...(options?.headers || {}),
  };

  if (isFormDataBody) {
    Object.keys(mergedHeaders).forEach((key) => {
      if (key.toLowerCase() === 'content-type' && mergedHeaders[key] === 'application/json') {
        delete mergedHeaders[key];
      }
    });
  }

  if (useTenantContext && tenantId && mergedHeaders['X-Tenant-Id'] == null) {
    mergedHeaders['X-Tenant-Id'] = tenantId;
  }

  if (useTenantContext && roleId && mergedHeaders['X-Role-Id'] == null) {
    mergedHeaders['X-Role-Id'] = roleId;
  }

  if (useTenantContext && !tenantId) {
    throw new Error('Tenant context is required');
  }

  const headerContextId = mergedHeaders['X-Context-Id'];
  if (shouldAutoEnsureContextId(url, headerContextId)) {
    // best-effort：不阻断主请求
    if (!contextId) {
      try {
        contextId = await ensureContextId(tenantId, { roleId });
      } catch {
        // ignore
      }
    }
  }

  if (useTenantContext && contextId && mergedHeaders['X-Context-Id'] == null) {
    mergedHeaders['X-Context-Id'] = contextId;
  }

  let response: Response;
  try {
    response = await fetch(url, {
      credentials: 'include',
      headers: mergedHeaders,
      ...options,
    });
  } catch (error: any) {
    logError('error.network', '网络错误', `${method} ${url}\n${String(error?.message || error)}`);
    throw error;
  }

  if (!response.ok) {
    const text = await response.text();
    await handleHttpStatus(method, url, response, text);
    const t = getI18nT();
    const err = new HttpError(response.status, response.statusText, text);
    if (response.status === 401) {
      // Modal already shown by handleHttpStatus; mark as notified
      (err as any).__notified = true;
    } else if (response.status === 403) {
      err.userMessage = t?.('error.forbidden', '无使用权限') ?? '无使用权限';
    } else if (response.status >= 500) {
      err.userMessage = t?.('error.server', '服务暂时不可用') ?? '服务暂时不可用';
    } else {
      // 4xx: prefer server-provided message (e.g. validation error)
      err.userMessage = extractUserMessage(text) || (t?.('error.requestFailed', '请求失败') ?? '请求失败');
    }
    throw err;
  }

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') || '';
  const contentLength = response.headers.get('content-length');
  try {
    // Handle empty body (content-length 0 or missing body)
    if (contentLength === '0') return undefined as T;
    if (contentType.includes('application/json')) {
      const text = await response.text();
      if (!text || !text.trim()) return undefined as T;
      return JSON.parse(text) as T;
    }
    // 兼容 text/plain
    return (await response.text()) as unknown as T;
  } catch (error: any) {
    logError('error.network', '网络错误', `${method} ${url}\n${String(error?.message || error)}`);
    throw error;
  }
}
