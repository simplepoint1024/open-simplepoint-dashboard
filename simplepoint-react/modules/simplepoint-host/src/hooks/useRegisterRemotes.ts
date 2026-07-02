// src/hooks/useRegisterRemotes.ts
import {useEffect, useRef} from 'react';
import {registerRemotesIfAny, remoteRegistrySignature} from '@/utils/MfRoutes';
import {ServiceMenuResult} from '@/fetches/routes';

export function useRegisterRemotes(res: ServiceMenuResult | undefined, isLoading: boolean) {
    const signatureRef = useRef('');

    useEffect(() => {
        if (!res || isLoading) {
            return;
        }

        const signature = remoteRegistrySignature(res.services ?? [], res.entryPoint);
        if (signatureRef.current === signature) {
            return;
        }

        registerRemotesIfAny(res.services ?? [], res.entryPoint);
        signatureRef.current = signature;
    }, [res, isLoading]);
}
