// src/hooks/useRegisterRemotes.ts
import {useEffect, useRef} from 'react';
import {registerRemotesIfAny} from '@/utils/MfRoutes';

export function useRegisterRemotes(res: any, isLoading: boolean) {
    const signatureRef = useRef('');

    useEffect(() => {
        if (!res || isLoading) {
            return;
        }

        const signature = JSON.stringify({
            entryPoint: res.entryPoint,
            services: (res.services ?? []).map((service: any) => [service.name, service.entry, service.alias]),
        });
        if (signatureRef.current === signature) {
            return;
        }

        registerRemotesIfAny(res.services ?? [], res.entryPoint);
        signatureRef.current = signature;
    }, [res, isLoading]);
}
