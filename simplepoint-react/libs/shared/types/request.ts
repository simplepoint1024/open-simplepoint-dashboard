import {useEffect, useState} from "react";
import {request as sharedRequest} from '../api/client';

export async function request<T>(url: string, options?: RequestInit): Promise<T> {
    return await sharedRequest<T>(url, options);
}

export async function post<T>(url: string, data: any): Promise<T> {
    return await request<T>(url, {
        method: 'POST',
        body: JSON.stringify(data),
    });
}

export async function get<T>(url: string, params?: Record<string, any>): Promise<T> {
    const query = params ? `?${new URLSearchParams(params).toString()}` : '';
    return await request<T>(`${url}${query}`, {
        method: 'GET'
    });
}

export async function put<T>(url: string, data: any): Promise<T> {
    return await request<T>(url, {
        method: 'PUT',
        body: JSON.stringify(data),
    });
}

export async function del<T>(url: string, ids: string[]): Promise<T> {
    return await request<T>(`${url}?ids=${ids.join(',')}`, {
        method: 'DELETE',
    });
}

/**
 * 空分页
 */
export const emptyPage: Page<any> = {
    content: [],
    page: {
        size: 0,
        totalElements: 0,
        totalPages: 0,
        number: 0,
    }
}


export type Page<T> = {
    content: Array<T>;
    page: {
        size: number;
        totalElements: number;
        totalPages: number;
        number: number;
    }
}

export type Sort = {
    empty: boolean,
    sorted: boolean,
    unsorted: boolean
}

/**
 * 将 Pageable 转换为 Ant Design Table 组件的分页配置
 * @param page Pageable 对象
 */
export function toPagination(page: Page<any>) {
    const {size, number, totalElements} = page.page
    return {
        total: totalElements ?? 0,
        pageSize: size ?? 20,
        current: (number ?? 0) + 1,
        showSizeChanger: true,
        showQuickJumper: true,
        pageSizeOptions: ['10', '20', '50', '100'],
        showTotal: (total: number) => `共 ${total} 条`,
    }
}

/**
 * 使用自定义 hook 来获取数据
 * @param fn 返回一个 Promise<Pageable<T>> 的函数
 * @returns 返回数据数组
 */
export function use<T>(fn: () => Promise<Page<T>>): Array<T> {
    const [data, setData] = useState<Array<T>>([]);
    useEffect(() => {
        const fetch = async () => {
            const {content} = await fn();
            setData(content);
        };
        fetch().then();
    }, []);
    return data;
}