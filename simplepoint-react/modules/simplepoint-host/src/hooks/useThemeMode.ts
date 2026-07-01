import { useEffect, useState } from 'react';

export type ThemeMode = 'light' | 'dark' | 'system';

function getSystemTheme(): 'light'|'dark' {
  try { return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'; } catch { return 'light'; }
}

export function useThemeMode() {
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => (localStorage.getItem('sp.theme') as ThemeMode) || 'light');
  const [resolvedTheme, setResolvedTheme] = useState<'light'|'dark'>(() => themeMode === 'system' ? getSystemTheme() : (themeMode as 'light'|'dark'));

  useEffect(() => {
    const handler = (e: any) => {
      const next = (e?.detail as ThemeMode) || 'light';
      setThemeMode(next);
    };
    window.addEventListener('sp-set-theme', handler as EventListener);
    return () => window.removeEventListener('sp-set-theme', handler as EventListener);
  }, []);

  useEffect(() => {
    if (themeMode === 'system') {
      const mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : (null as any);
      const apply = () => setResolvedTheme(getSystemTheme());
      apply();
      if (mq && mq.addEventListener) mq.addEventListener('change', apply);
      else if (mq && (mq as any).addListener) (mq as any).addListener(apply);
      return () => {
        if (mq && mq.removeEventListener) mq.removeEventListener('change', apply);
        else if (mq && (mq as any).removeListener) (mq as any).removeListener(apply);
      };
    } else {
      setResolvedTheme(themeMode as 'light'|'dark');
    }
  }, [themeMode]);

  useEffect(() => { try { localStorage.setItem('sp.theme', themeMode); } catch {} }, [themeMode]);
  useEffect(() => { try { document.documentElement.setAttribute('data-theme', resolvedTheme); } catch {} }, [resolvedTheme]);

  return { themeMode, resolvedTheme } as const;
}

