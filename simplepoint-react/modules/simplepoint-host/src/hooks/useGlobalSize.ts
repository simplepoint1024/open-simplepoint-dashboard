import { useEffect, useState } from 'react';

export type GlobalSize = 'small' | 'middle' | 'large';

export function useGlobalSize() {
  const [globalSize, setGlobalSize] = useState<GlobalSize>(() => (localStorage.getItem('sp.globalSize') as GlobalSize) || 'middle');

  useEffect(() => {
    const handler = (e: any) => {
      const next = (e?.detail as GlobalSize) || 'middle';
      setGlobalSize(next);
    };
    window.addEventListener('sp-set-size', handler as EventListener);
    return () => window.removeEventListener('sp-set-size', handler as EventListener);
  }, []);

  useEffect(() => { try { localStorage.setItem('sp.globalSize', globalSize); } catch {} }, [globalSize]);

  return { globalSize } as const;
}

