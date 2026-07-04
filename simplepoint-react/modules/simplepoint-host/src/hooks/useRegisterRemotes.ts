// src/hooks/useRegisterRemotes.ts
import {useLayoutEffect, useMemo, useRef, useState} from 'react';
import {registerRemotesIfAny, remoteRegistrySignature} from '@/utils/MfRoutes';
import {ServiceMenuResult} from '@/fetches/routes';

export function useRegisterRemotes(res: ServiceMenuResult | undefined, isLoading: boolean) {
    const signatureRef = useRef('');
    const [registeredSignature, setRegisteredSignature] = useState('');
    const remoteRegistryKey = useMemo(
        () => remoteRegistrySignature(res?.services ?? [], res?.entryPoint),
        [res?.services, res?.entryPoint],
    );

    useLayoutEffect(() => {
        if (!res || isLoading) {
            setRegisteredSignature('');
            return;
        }

        if (signatureRef.current === remoteRegistryKey) {
            setRegisteredSignature(remoteRegistryKey);
            return;
        }

        registerRemotesIfAny(res.services ?? [], res.entryPoint);
        signatureRef.current = remoteRegistryKey;
        setRegisteredSignature(remoteRegistryKey);
    }, [res, isLoading, remoteRegistryKey]);

    return {
        remoteRegistryKey,
        remotesReady: Boolean(res && !isLoading && registeredSignature === remoteRegistryKey),
    };
}
