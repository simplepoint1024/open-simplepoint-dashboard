import { useContext } from 'react';
import { I18nContext, I18nContextValue } from './I18nProvider';

/**
 * 消费国际化上下文
 */
export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    throw new Error('useI18n must be used within I18nProvider');
  }
  return ctx;
}
