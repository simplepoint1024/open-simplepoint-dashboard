import type { I18nLike } from '@simplepoint/shared/hooks/useI18n';

declare global {
  interface Window {
    spI18n?: I18nLike;
  }
}

export {};
