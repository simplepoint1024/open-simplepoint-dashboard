import {
  ensureContextId,
  getStoredContextId,
  getStoredRoleId,
  getStoredTenantId,
  shouldAutoEnsureContextId,
  shouldUseTenantContext,
} from './contextId';
import {redirectToLogin} from './session';

export type QueryValue = string | number | boolean | Date | null | undefined;
export type QueryParams = URLSearchParams | object;
export type ResponseType = 'auto' | 'json' | 'text' | 'blob' | 'arrayBuffer' | 'void' | 'response';
export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'abort' | 'parse' | 'context';

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: BodyInit | null;
  json?: unknown;
  params?: QueryParams;
  timeoutMs?: number;
  responseType?: ResponseType;
  notifyOnError?: boolean;
  handleUnauthorized?: boolean;
  recoverUnauthorized?: boolean;
  skipTenantContext?: boolean;
}

type ParsedBody = {
  data: unknown;
  text?: string;
  parseError?: unknown;
};

const DEFAULT_TIMEOUT_MS = 0;

// 自定义错误类型，方便上层捕获和处理。
export class HttpError extends Error {
  readonly __simplepointHttpError = true;
  status: number;
  statusText: string;
  body?: string;
  data?: unknown;
  method?: string;
  url?: string;
  kind: ApiErrorKind;
  code?: string;
  requestId?: string;
  /** 面向用户的错误描述，供调用方展示在 UI 提示中 */
  userMessage?: string;
  /** 兼容旧逻辑：表示全局层已经提示过该错误 */
  __notified?: boolean;
  /** 401 前置探测确认浏览器登录会话仍然有效时为 true */
  sessionActive?: boolean;

  constructor(status: number, statusText: string, body?: string, meta?: Partial<HttpError> & {cause?: unknown}) {
    const label = status > 0 ? `HTTP ${status} ${statusText}` : statusText;
    super(label);
    this.name = 'HttpError';
    this.status = status;
    this.statusText = statusText;
    this.body = body;
    this.kind = meta?.kind ?? 'http';
    this.data = meta?.data;
    this.method = meta?.method;
    this.url = meta?.url;
    this.code = meta?.code;
    this.requestId = meta?.requestId;
    this.userMessage = meta?.userMessage;
    this.__notified = meta?.__notified;
    this.sessionActive = meta?.sessionActive;
    if (meta?.cause) {
      (this as any).cause = meta.cause;
    }
  }
}

export function isHttpError(error: unknown): error is HttpError {
  const candidate = error as Partial<HttpError> | null | undefined;
  return error instanceof HttpError
    || (
      typeof candidate === 'object'
      && candidate != null
      && candidate.__simplepointHttpError === true
      && typeof candidate.status === 'number'
      && typeof candidate.statusText === 'string'
    );
}

const getI18nT = () =>
  typeof window !== 'undefined'
    ? (window as any)?.spI18n?.t as ((key: string, fallback?: string, params?: Record<string, unknown>) => string) | undefined
    : undefined;

const t = (key: string, fallback: string, params?: Record<string, unknown>) => {
  const translate = getI18nT();
  return translate?.(key, fallback, params) ?? fallback;
};

function normalizeHeaders(headers?: HeadersInit): Headers {
  return new Headers(headers ?? {});
}

function hasBody(response: Response) {
  return response.status !== 204 && response.status !== 205 && response.headers.get('content-length') !== '0';
}

function isJsonContentType(contentType: string) {
  return contentType.includes('application/json') || contentType.includes('+json');
}

function stringifyQueryValue(value: Exclude<QueryValue, null | undefined>) {
  return value instanceof Date ? value.toISOString() : String(value);
}

export function appendQueryParams(url: string, params?: QueryParams): string {
  if (!params) {
    return url;
  }

  const search = new URLSearchParams();
  const append = (key: string, value: QueryValue) => {
    if (value == null) return;
    search.append(key, stringifyQueryValue(value));
  };

  if (params instanceof URLSearchParams) {
    params.forEach((value, key) => search.append(key, value));
  } else {
    Object.entries(params as Record<string, QueryValue | QueryValue[]>).forEach(([key, value]) => {
      if (Array.isArray(value)) {
        value.forEach((item) => append(key, item));
      } else {
        append(key, value);
      }
    });
  }

  const query = search.toString();
  if (!query) {
    return url;
  }

  const hashIndex = url.indexOf('#');
  const base = hashIndex >= 0 ? url.slice(0, hashIndex) : url;
  const hash = hashIndex >= 0 ? url.slice(hashIndex) : '';
  const separator = base.includes('?') ? (base.endsWith('?') || base.endsWith('&') ? '' : '&') : '?';
  return `${base}${separator}${query}${hash}`;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Object.prototype.toString.call(value) === '[object Object]';
}

function looksLikeJson(text: string) {
  const trimmed = text.trim();
  return trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith('"');
}

async function parseBody(response: Response, responseType: ResponseType): Promise<ParsedBody> {
  if (responseType === 'response') {
    return {data: response};
  }
  if (responseType === 'void' || !hasBody(response)) {
    return {data: undefined};
  }
  if (responseType === 'blob') {
    return {data: await response.blob()};
  }
  if (responseType === 'arrayBuffer') {
    return {data: await response.arrayBuffer()};
  }

  const contentType = response.headers.get('content-type') || '';
  const text = await response.text();
  if (!text.trim()) {
    return {data: undefined, text};
  }
  if (responseType === 'text') {
    return {data: text, text};
  }

  const shouldParseJson = responseType === 'json' || (responseType === 'auto' && (isJsonContentType(contentType) || looksLikeJson(text)));
  if (!shouldParseJson) {
    return {data: text, text};
  }

  try {
    return {data: JSON.parse(text), text};
  } catch (error) {
    return {data: undefined, text, parseError: error};
  }
}

function textIsUsefulMessage(text?: string) {
  if (!text) return false;
  const trimmed = text.trim();
  if (!trimmed || trimmed.length > 300) return false;
  if (/^<!doctype html>|^<html[\s>]/i.test(trimmed)) return false;
  if (trimmed.includes('\n\tat ') || trimmed.includes('\n    at ')) return false;
  return true;
}

function firstString(...values: unknown[]) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return undefined;
}

function isGenericHttpReason(value: string) {
  return [
    'bad request',
    'unauthorized',
    'forbidden',
    'not found',
    'method not allowed',
    'conflict',
    'unprocessable entity',
    'too many requests',
    'internal server error',
    'bad gateway',
    'service unavailable',
    'gateway timeout',
  ].includes(value.trim().toLowerCase());
}

function usefulMessage(value?: string) {
  if (!textIsUsefulMessage(value)) return undefined;
  const trimmed = value!.trim();
  return isGenericHttpReason(trimmed) ? undefined : trimmed;
}

function extractFromArray(values: unknown): string | undefined {
  if (!Array.isArray(values)) return undefined;
  const messages = values
    .map((item) => {
      if (typeof item === 'string') return usefulMessage(item);
      if (isPlainObject(item)) {
        return usefulMessage(firstString(item.message, item.defaultMessage, item.detail, item.reason, item.error));
      }
      return undefined;
    })
    .filter(Boolean)
    .slice(0, 3);
  return messages.length ? messages.join('; ') : undefined;
}

function extractServerMessage(data: unknown, text?: string): string | undefined {
  if (typeof data === 'string') {
    return usefulMessage(data);
  }
  if (isPlainObject(data)) {
    const directMessage = firstString(
      data.userMessage,
      data.message,
      data.detail,
      data.error_description,
      data.errorMessage,
      data.localizedMessage,
      data.reason
    );
    return usefulMessage(directMessage)
      || extractFromArray(data.errors)
      || extractFromArray(data.violations)
      || extractFromArray(data.fieldErrors)
      || usefulMessage(firstString(data.title, data.error));
  }
  return usefulMessage(text);
}

function extractErrorCode(data: unknown): string | undefined {
  if (!isPlainObject(data)) return undefined;
  const code = firstString(data.code, data.errorCode, data.error);
  return code && /^[A-Za-z0-9._:-]+$/.test(code) ? code : undefined;
}

function extractRequestId(response?: Response, data?: unknown) {
  const fromHeader = response?.headers.get('x-request-id')
    || response?.headers.get('x-correlation-id')
    || response?.headers.get('x-trace-id');
  if (fromHeader) return fromHeader;
  if (isPlainObject(data)) {
    return firstString(data.requestId, data.traceId, data.correlationId);
  }
  return undefined;
}

function statusFallback(status: number) {
  if (status === 400) return t('error.badRequest', '请求参数不正确');
  if (status === 401) return t('error.unauthorized', '登录状态已失效');
  if (status === 403) return t('error.forbidden', '无使用权限');
  if (status === 404) return t('error.404', '页面不存在');
  if (status === 409) return t('error.conflict', '数据已被修改，请刷新后重试');
  if (status === 422) return t('error.validation', '提交的数据不合法');
  if (status === 429) return t('error.tooManyRequests', '请求过于频繁，请稍后再试');
  if (status >= 500) return t('error.server', '服务暂时不可用');
  return t('error.requestFailed', '请求失败');
}

function describeError(error: HttpError) {
  const requestLine = [error.method, error.url].filter(Boolean).join(' ');
  const statusLine = error.status > 0 ? `HTTP ${error.status} ${error.statusText}` : error.statusText;
  const requestId = error.requestId ? `requestId=${error.requestId}` : undefined;
  const body = error.body?.trim()?.slice(0, 500);
  return [requestLine, statusLine, requestId, body].filter(Boolean).join('\n');
}

function logApiError(error: HttpError) {
  try {
    console.warn('[API]', error.userMessage || error.message, describeError(error));
  } catch {}
}

let unauthorizedModalOpen = false;

const SESSION_PROBE_URL = '/userinfo';
const SESSION_PROBE_DELAYS_MS = [0, 250, 750] as const;
const UNAUTHORIZED_RECOVERY_RETRY_DELAY_MS = 250;
let sessionProbeInflight: Promise<boolean> | undefined;

async function handleUnauthorized(error: HttpError) {
  if (error.sessionActive) {
    return;
  }
  if (unauthorizedModalOpen) {
    error.__notified = true;
    return;
  }
  unauthorizedModalOpen = true;
  error.__notified = true;
  try {
    const {Modal} = await import('antd');
    await new Promise<void>((resolve) => {
      Modal.confirm({
        title: t('error.unauthorized.title', '登录状态已失效'),
        content: t('error.unauthorized.content', '检测到当前登录状态失效。你可以留在当前页面，或返回登录页面重新登录。'),
        okText: t('error.unauthorized.goLogin', '返回登录页'),
        cancelText: t('error.unauthorized.stay', '留在当前页'),
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
}

const notifyTimestamps = new Map<string, number>();

async function notifyError(error: HttpError) {
  if (error.__notified) return;
  const key = `${error.status}:${error.code ?? ''}:${error.userMessage ?? error.message}`;
  const now = Date.now();
  const last = notifyTimestamps.get(key) ?? 0;
  if (now - last < 3000) return;
  notifyTimestamps.set(key, now);
  try {
    const {message} = await import('antd');
    message.error(error.userMessage || error.message);
    error.__notified = true;
  } catch {}
}

async function handleError(error: HttpError, options: RequestOptions) {
  if (error.status === 401 && options.handleUnauthorized !== false) {
    await handleUnauthorized(error);
  }
  if (options.notifyOnError) {
    await notifyError(error);
  }
  logApiError(error);
}

function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

function isSessionProbeUrl(url: string) {
  try {
    return new URL(
      url,
      typeof window !== 'undefined' ? window.location.origin : 'http://localhost'
    ).pathname === SESSION_PROBE_URL;
  } catch {
    return url === SESSION_PROBE_URL || url.includes(SESSION_PROBE_URL);
  }
}

function isRedirectStatus(status: number) {
  return status >= 300 && status < 400;
}

async function fetchSessionProbeOnce() {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 3000);
  try {
    const response = await fetch(SESSION_PROBE_URL, {
      method: 'GET',
      credentials: 'include',
      redirect: 'manual',
      cache: 'no-store',
      headers: {
        Accept: 'application/json, text/plain, */*',
        'Cache-Control': 'no-cache',
        'X-Requested-With': 'XMLHttpRequest',
      },
      signal: controller.signal,
    });
    if (isRedirectStatus(response.status) || response.status === 401 || response.status === 403) {
      return false;
    }
    if (!response.ok) {
      return false;
    }
    const contentType = response.headers.get('content-type') || '';
    return !contentType.toLowerCase().includes('text/html');
  } catch {
    return false;
  } finally {
    clearTimeout(timeoutId);
  }
}

async function verifySessionStillActive() {
  if (!sessionProbeInflight) {
    sessionProbeInflight = (async () => {
      for (const waitMs of SESSION_PROBE_DELAYS_MS) {
        if (waitMs > 0) {
          await delay(waitMs);
        }
        if (await fetchSessionProbeOnce()) {
          return true;
        }
      }
      return false;
    })().finally(() => {
      sessionProbeInflight = undefined;
    });
  }
  return sessionProbeInflight;
}

function createHttpError(method: string, url: string, response: Response, parsed: ParsedBody) {
  const userMessage = extractServerMessage(parsed.data, parsed.text) || statusFallback(response.status);
  return new HttpError(response.status, response.statusText, parsed.text, {
    kind: 'http',
    method,
    url,
    data: parsed.data,
    code: extractErrorCode(parsed.data),
    requestId: extractRequestId(response, parsed.data),
    userMessage,
  });
}

function createClientError(kind: ApiErrorKind, method: string, url: string, message: string, cause?: unknown) {
  return new HttpError(0, message, undefined, {
    kind,
    method,
    url,
    cause,
    userMessage: message,
  });
}

function createContextError(method: string, url: string) {
  return createClientError(
    'context',
    method,
    url,
    t('error.tenantContextRequired', '请选择租户后再操作')
  );
}

function createRequestSignal(externalSignal: AbortSignal | undefined, timeoutMs: number | undefined) {
  const effectiveTimeout = timeoutMs === undefined ? DEFAULT_TIMEOUT_MS : timeoutMs;
  if (!externalSignal && (!effectiveTimeout || effectiveTimeout <= 0)) {
    return {signal: undefined, cleanup: () => {}, isTimeout: () => false};
  }

  const controller = new AbortController();
  let timedOut = false;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;

  const abort = (reason?: unknown) => {
    if (!controller.signal.aborted) {
      controller.abort(reason);
    }
  };

  const onExternalAbort = () => abort((externalSignal as any)?.reason);

  if (externalSignal) {
    if (externalSignal.aborted) {
      onExternalAbort();
    } else {
      externalSignal.addEventListener('abort', onExternalAbort, {once: true});
    }
  }

  if (effectiveTimeout && effectiveTimeout > 0) {
    timeoutId = setTimeout(() => {
      timedOut = true;
      abort(new Error('Request timeout'));
    }, effectiveTimeout);
  }

  const cleanup = () => {
    if (timeoutId) clearTimeout(timeoutId);
    if (externalSignal) externalSignal.removeEventListener('abort', onExternalAbort);
  };

  return {signal: controller.signal, cleanup, isTimeout: () => timedOut};
}

async function sendRequest(
  finalUrl: string,
  method: string,
  requestOptions: RequestOptions,
  fetchOptions: Omit<RequestInit, 'body'>,
  headers: Headers,
  body: BodyInit | null | undefined,
  externalSignal: AbortSignal | undefined,
  timeoutMs: number | undefined
) {
  const {signal, cleanup, isTimeout} = createRequestSignal(externalSignal, timeoutMs);
  try {
    return await fetch(finalUrl, {
      ...fetchOptions,
      method,
      credentials: fetchOptions.credentials ?? 'include',
      headers,
      body,
      signal,
    });
  } catch (error: any) {
    const aborted = signal?.aborted || error?.name === 'AbortError';
    const kind: ApiErrorKind = isTimeout() ? 'timeout' : aborted ? 'abort' : 'network';
    const message = kind === 'timeout'
      ? t('error.timeout', '请求超时，请稍后再试')
      : kind === 'abort'
        ? t('error.aborted', '请求已取消')
        : t('error.networkError', '网络错误');
    const clientError = createClientError(kind, method, finalUrl, message, error);
    await handleError(clientError, requestOptions);
    throw clientError;
  } finally {
    cleanup();
  }
}

function isFormData(value: unknown): value is FormData {
  return typeof FormData !== 'undefined' && value instanceof FormData;
}

function isUrlSearchParams(value: unknown): value is URLSearchParams {
  return typeof URLSearchParams !== 'undefined' && value instanceof URLSearchParams;
}

function isBinaryBody(value: unknown) {
  if (!value) return false;
  if (typeof Blob !== 'undefined' && value instanceof Blob) return true;
  if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) return true;
  if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(value as ArrayBufferView)) return true;
  if (typeof ReadableStream !== 'undefined' && value instanceof ReadableStream) return true;
  return false;
}

function buildBodyAndHeaders(options: RequestOptions, headers: Headers) {
  if (options.json !== undefined) {
    if (!headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    return JSON.stringify(options.json);
  }

  const body = options.body;
  if (isFormData(body)) {
    headers.delete('Content-Type');
    return body;
  }

  if (isUrlSearchParams(body)) {
    if (!headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
    }
    return body;
  }

  if (isBinaryBody(body)) {
    return body;
  }

  if (body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return body;
}

function shouldRecoverUnauthorized(method: string, url: string, options: RequestOptions) {
  if (options.recoverUnauthorized === false || options.handleUnauthorized === false) {
    return false;
  }
  if (isSessionProbeUrl(url)) {
    return false;
  }
  return method === 'GET' || method === 'HEAD';
}

function markSessionActiveUnauthorized(error: HttpError) {
  error.sessionActive = true;
  error.userMessage = t(
    'error.authorizationSync',
    '登录状态仍然有效，但服务授权状态暂未同步，请稍后重试'
  );
  return error;
}

async function parseSuccessfulResponse(
  response: Response,
  responseType: ResponseType,
  method: string,
  finalUrl: string,
  requestOptions: RequestOptions
) {
  const parsed = await parseBody(response, responseType);

  if (parsed.parseError) {
    const error = new HttpError(response.status, response.statusText || 'Invalid JSON response', parsed.text, {
      kind: 'parse',
      method,
      url: finalUrl,
      cause: parsed.parseError,
      requestId: extractRequestId(response, undefined),
      userMessage: t('error.invalidResponse', '服务返回数据格式不正确'),
    });
    await handleError(error, requestOptions);
    throw error;
  }

  return parsed.data;
}

async function recoverUnauthorizedRequest(
  error: HttpError,
  finalUrl: string,
  method: string,
  responseType: ResponseType,
  requestOptions: RequestOptions,
  fetchOptions: Omit<RequestInit, 'body'>,
  headers: Headers,
  body: BodyInit | null | undefined,
  externalSignal: AbortSignal | undefined,
  timeoutMs: number | undefined
): Promise<{ recovered: true; data: unknown } | { recovered: false; error: HttpError }> {
  if (error.status !== 401 || !shouldRecoverUnauthorized(method, finalUrl, requestOptions)) {
    return {recovered: false, error};
  }

  const sessionActive = await verifySessionStillActive();
  if (!sessionActive) {
    return {recovered: false, error};
  }

  await delay(UNAUTHORIZED_RECOVERY_RETRY_DELAY_MS);
  const retryResponse = await sendRequest(
    finalUrl,
    method,
    {...requestOptions, recoverUnauthorized: false},
    fetchOptions,
    headers,
    body,
    externalSignal,
    timeoutMs
  );

  if (!retryResponse.ok) {
    const parsed = await parseBody(retryResponse.clone(), 'auto');
    const retryError = createHttpError(method, finalUrl, retryResponse, parsed);
    return {
      recovered: false,
      error: retryError.status === 401 ? markSessionActiveUnauthorized(retryError) : retryError,
    };
  }

  return {
    recovered: true,
    data: await parseSuccessfulResponse(retryResponse, responseType, method, finalUrl, requestOptions),
  };
}

// 通用请求方法
export async function request<T>(url: string, options?: RequestOptions): Promise<T> {
  const requestOptions = options ?? {};
  const finalUrl = appendQueryParams(url, requestOptions.params);
  const method = (requestOptions.method || 'GET').toUpperCase();
  const responseType = requestOptions.responseType ?? 'auto';
  const useTenantContext = !requestOptions.skipTenantContext && shouldUseTenantContext(finalUrl);

  const {
    params: _params,
    json: _json,
    timeoutMs,
    responseType: _responseType,
    notifyOnError: _notifyOnError,
    handleUnauthorized: _handleUnauthorized,
    recoverUnauthorized: _recoverUnauthorized,
    skipTenantContext: _skipTenantContext,
    headers: optionHeaders,
    signal: rawExternalSignal,
    body: _body,
    ...fetchOptions
  } = requestOptions;
  const externalSignal = rawExternalSignal ?? undefined;

  const tenantId = getStoredTenantId()?.trim();
  const roleId = useTenantContext ? getStoredRoleId(tenantId)?.trim() : undefined;
  let contextId: string | undefined = useTenantContext ? getStoredContextId(tenantId, roleId) : undefined;

  if (useTenantContext && !tenantId) {
    const error = createContextError(method, finalUrl);
    await handleError(error, requestOptions);
    throw error;
  }

  const headers = normalizeHeaders(optionHeaders);
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json, text/plain, */*');
  }
  const body = buildBodyAndHeaders(requestOptions, headers);

  if (useTenantContext && tenantId && !headers.has('X-Tenant-Id')) {
    headers.set('X-Tenant-Id', tenantId);
  }
  if (useTenantContext && roleId && !headers.has('X-Role-Id')) {
    headers.set('X-Role-Id', roleId);
  }

  if (shouldAutoEnsureContextId(finalUrl, headers.get('X-Context-Id'))) {
    if (!contextId) {
      try {
        contextId = await ensureContextId(tenantId, {roleId, signal: externalSignal});
      } catch {
        // contextId is best-effort. The backend will still validate authorization.
      }
    }
  }

  if (useTenantContext && contextId && !headers.has('X-Context-Id')) {
    headers.set('X-Context-Id', contextId);
  }

  const response = await sendRequest(
    finalUrl,
    method,
    requestOptions,
    fetchOptions,
    headers,
    body,
    externalSignal,
    timeoutMs
  );

  if (!response.ok) {
    const parsed = await parseBody(response.clone(), 'auto');
    const error = createHttpError(method, finalUrl, response, parsed);
    const recovered = await recoverUnauthorizedRequest(
      error,
      finalUrl,
      method,
      responseType,
      requestOptions,
      fetchOptions,
      headers,
      body,
      externalSignal,
      timeoutMs
    );
    if (recovered.recovered) {
      return recovered.data as T;
    }
    await handleError(recovered.error, requestOptions);
    throw recovered.error;
  }

  return await parseSuccessfulResponse(response, responseType, method, finalUrl, requestOptions) as T;
}

export function resolveApiErrorMessage(error: unknown, fallback?: string) {
  if (isHttpError(error)) {
    return error.userMessage || extractServerMessage(error.data, error.body) || fallback || error.message;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback || t('error.requestFailed', '请求失败');
}
