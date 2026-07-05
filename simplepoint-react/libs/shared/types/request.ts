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

export type RawPage<T> = Partial<Page<T>> & {
    content?: Array<T>;
    size?: number;
    totalElements?: number;
    totalPages?: number;
    number?: number;
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
function toPageNumber(value: unknown, fallback: number) {
    return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

export function normalizePage<T>(raw: RawPage<T> | null | undefined, defaultSize = 20): Page<T> {
    const content = Array.isArray(raw?.content) ? raw.content : [];
    const nested: Partial<Page<T>['page']> = raw?.page ?? {};
    const size = toPageNumber(nested.size ?? raw?.size, defaultSize);
    const totalElements = toPageNumber(nested.totalElements ?? raw?.totalElements, content.length);
    const totalPages = toPageNumber(
        nested.totalPages ?? raw?.totalPages,
        size > 0 ? Math.ceil(totalElements / size) : 0,
    );
    const number = toPageNumber(nested.number ?? raw?.number, 0);

    return {
        content,
        page: {
            size,
            totalElements,
            totalPages,
            number,
        },
    };
}

export function toPagination(page: RawPage<any> | null | undefined) {
    const normalized = normalizePage(page)
    const {size, number, totalElements} = normalized.page
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
