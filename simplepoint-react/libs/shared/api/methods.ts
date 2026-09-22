import {appendQueryParams, request, RequestOptions, QueryParams} from './client';
import {useQuery, UseQueryOptions} from '@tanstack/react-query';
import {Page} from "../types/request"
import type {QueryFunctionContext} from '@tanstack/react-query';
import {scopedQueryOptions} from './queryScope';
import {useQueryScope} from '../hooks/useQueryScope';
import {usePageActive} from '../page/hooks';

/**
 * GET 请求封装
 */
export function get<T>(url: string, params?: QueryParams, options?: RequestOptions): Promise<T> {
    return request<T>(url, {...options, method: 'GET', params});
}

/**
 * POST 请求封装
 */
export function post<T>(url: string, data: any, options?: RequestOptions): Promise<T> {
    return request<T>(url, {
        ...options,
        method: 'POST',
        json: data,
    });
}

/**
 * PUT 请求封装
 */
export function put<T>(url: string, data: any, options?: RequestOptions): Promise<T> {
    return request<T>(url, {
        ...options,
        method: 'PUT',
        json: data,
    });
}

/**
 * DELETE 请求封装（支持单个或多个 ID）
 */
export function del<T>(url: string, ids: string | number | (string | number)[], options?: RequestOptions): Promise<T> {
    const list = Array.isArray(ids) ? ids : [ids];
    const idsStr = list.map(String).join(',');
    return request<T>(appendQueryParams(url, idsStr ? {ids: idsStr} : undefined), {...options, method: 'DELETE'});
}

/**
 * 分页数据查询 Hook
 */
export function usePage<T>(
    key: string | readonly unknown[],
    fetchFn: (context: QueryFunctionContext) => Promise<Page<T>>,
    options?: Omit<UseQueryOptions<Page<T>, Error, Page<T>, readonly unknown[]>, 'queryKey' | 'queryFn'>
) {
    const scope = useQueryScope();
    const active = usePageActive();
    const queryKey = typeof key === 'string' ? [key] : key;
    return useQuery<Page<T>, Error, Page<T>, readonly unknown[]>({
        ...scopedQueryOptions<Page<T>>(scope, queryKey, fetchFn),
        ...options,
        enabled: query => active && (typeof options?.enabled === 'function' ? options.enabled(query) : options?.enabled !== false),
    });
}

/**
 * 普通数据查询 Hook
 */
export function useData<T>(
    key: string | readonly unknown[],
    fetchFn: (context: QueryFunctionContext) => Promise<T>,
    options?: Omit<UseQueryOptions<T, Error, T, readonly unknown[]>, 'queryKey' | 'queryFn'>
) {
    const scope = useQueryScope();
    const active = usePageActive();
    const queryKey = typeof key === 'string' ? [key] : key;
    return useQuery<T, Error, T, readonly unknown[]>({
        ...scopedQueryOptions<T>(scope, queryKey, fetchFn),
        ...options,
        enabled: query => active && (typeof options?.enabled === 'function' ? options.enabled(query) : options?.enabled !== false),
    });
}
