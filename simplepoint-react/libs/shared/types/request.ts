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
