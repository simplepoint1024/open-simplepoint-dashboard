import {getInstance} from '@module-federation/runtime';
import {ServiceEntry} from '@/fetches/routes';
import {MenuInfo} from "@/store/routes";

/**
 * 注册远程模块（如果有的话）
 * @param remotes
 * @param entryPoint
 */
export function registerRemotesIfAny(remotes: ServiceEntry[], entryPoint?: string) {
    if (!remotes || remotes.length === 0) return;
    const mf = getInstance();
    if (!mf) return;
    mf.registerRemotes(remotes.map(map => formatMfEntry(map, entryPoint??'/mf/mf-manifest.json')));
}

/**
 * 格式化远程模块的 entry 地址
 * @param serviceEntry
 * @param entryPoint
 */
export function formatMfEntry(serviceEntry: ServiceEntry, entryPoint: string): ServiceEntry {
    const {name, entry} = serviceEntry;
    // 优先使用 entry 字段
    if (entry) {
        return serviceEntry;
    }
    const entryPointPath = entryPoint.startsWith("/") ? entryPoint : `/${entryPoint}`;
    const localEntry = `${window.location.origin}/${name}${entryPointPath}`;
    console.log(`[Mf] Remote ${name} entry resolved to: ${localEntry}`);
    // 否则按照约定路径构建
    return {
        name,
        entry: localEntry,
    };
}

export type MenuNode = MenuInfo & { children?: MenuNode[] };

export function flattenLeafRoutes(nodes: MenuNode[] = []): MenuNode[] {
    const res: MenuNode[] = [];
    const dfs = (arr: MenuNode[]) => {
        arr.forEach((n) => {
            const children = n.children as MenuNode[] | undefined;
            if (Array.isArray(children) && children.length > 0) {
                dfs(children);
            } else {
                res.push(n);
            }
        });
    };
    dfs(nodes);
    return res;
}
