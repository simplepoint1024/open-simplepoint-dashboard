import {useQuery} from '@tanstack/react-query';
import {useEffect} from 'react';
import {get} from '@simplepoint/shared/api/methods';

export type UserInfo = any;

export type CurrentProfile = {
    id: string;
    nickname?: string | null;
    name?: string | null;
    email?: string | null;
    phoneNumber?: string | null;
    picture?: string | null;
    address?: string | null;
    birthdate?: string | null;
    familyName?: string | null;
    givenName?: string | null;
    middleName?: string | null;
    gender?: string | null;
    profile?: string | null;
    website?: string | null;
    locale?: string | null;
    zoneinfo?: string | null;
    twoFactorEnabled?: boolean | null;
    enabled?: boolean | null;
    createdAt?: string | null;
    updatedAt?: string | null;
};

function normalizeUserInfo(data: UserInfo): UserInfo {
    if (!data || typeof data !== 'object') {
        return data;
    }

    const normalized = {...data};

    if (normalized.username == null && typeof normalized.preferred_username === 'string') {
        normalized.username = normalized.preferred_username;
    }

    if (normalized.phone == null && typeof normalized.phone_number === 'string') {
        normalized.phone = normalized.phone_number;
    }

    if (normalized.phone == null && typeof normalized.phoneNumber === 'string') {
        normalized.phone = normalized.phoneNumber;
    }

    return normalized;
}

export async function fetchUserInfo(): Promise<UserInfo> {
    return normalizeUserInfo(await get<UserInfo>('/userinfo'));
}

export function useUserInfo() {
    // 读取会话缓存作为初始数据，提升首屏速度
    let cached: UserInfo | undefined;
    try {
        const raw = sessionStorage.getItem('sp.userinfo');
        cached = raw ? normalizeUserInfo(JSON.parse(raw)) : undefined;
    } catch (e) {
        console.warn('[user] Failed to read cached userinfo:', e);
    }

    const result = useQuery({
        queryKey: ['userinfo'],
        queryFn: fetchUserInfo,
        // 2 分钟内视为新鲜数据，避免重复拉取
        staleTime: 2 * 60 * 1000,
        // 页面重新获得焦点不强制刷新
        refetchOnWindowFocus: false,
        // 用缓存作为初始值（可选）
        initialData: cached,
        // 会话缓存只用于首屏兜底展示，但应立即后台回源，避免 2FA 等状态变更被旧值卡住
        initialDataUpdatedAt: cached ? 0 : undefined,
    });

    useEffect(() => {
        if (result.data) {
            try {
                sessionStorage.setItem('sp.userinfo', JSON.stringify(result.data));
            } catch (e) {
                console.warn('[user] Failed to cache userinfo:', e);
            }
        }
    }, [result.data]);

    return result;
}

export async function fetchCurrentProfile(): Promise<CurrentProfile> {
    return get<CurrentProfile>('/common/users/me');
}

export function useCurrentProfile() {
    return useQuery({
        queryKey: ['current-profile'],
        queryFn: fetchCurrentProfile,
        staleTime: 60 * 1000,
        refetchOnWindowFocus: false,
    });
}
