import React from 'react';
import ReactDOM from 'react-dom/client';
import '@simplepoint/components/Simplepoint.css';
import '@ant-design/v5-patch-for-react-19';
import App from '@/App';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {applyInitialHtmlAttributes} from "@/utils/initHtmlAttributes.ts";
import {I18nProvider} from "@/layouts/i18n/I18nProvider.tsx";
import {HttpError} from '@simplepoint/shared/api/client';

applyInitialHtmlAttributes();

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof HttpError && [401, 403, 500].includes(error.status)) {
          return false;
        }
        return failureCount < 1;
      },
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: (failureCount, error) => {
        if (error instanceof HttpError && [401, 403, 500].includes(error.status)) {
          return false;
        }
        return failureCount < 1;
      },
    },
  },
});

function createRoot() {
  const rootEl = document.getElementById('root');
  if (!rootEl) throw new Error('Root element not found');
  ReactDOM.createRoot(rootEl).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <I18nProvider>
          <App/>
        </I18nProvider>
      </QueryClientProvider>
    </React.StrictMode>
  );
}

// 在开发环境按需加载 mocks，避免生产环境打包任何 mock 相关代码
if (process.env.NODE_ENV === 'development') {
  // 动态导入，确保生产环境不会生成相关 chunk；并等待 worker.start() 完成后再渲染
  import('../../mocks')
    .then(async (mod: any) => {
      try {
        // mocks/index.ts 默认导出是 worker.start(...) 返回的 Promise
        if (mod && mod.default && typeof mod.default.then === 'function') {
          await mod.default;
        }
      } catch (err) {
        console.warn('MSW mock init failed:', err);
      }
    })
    .finally(() => {
      // 无论是否成功启动，都继续渲染，失败时会走真实网络
      createRoot();
    });
} else {
  createRoot();
}
