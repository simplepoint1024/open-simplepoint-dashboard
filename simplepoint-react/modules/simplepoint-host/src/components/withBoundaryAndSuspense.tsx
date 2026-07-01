// src/components/withBoundaryAndSuspense.tsx
import React from 'react';
import {Skeleton, Result} from 'antd';
import {ErrorBoundary} from './ErrorBoundary';

type TranslateFn = (key: string, fallback?: string) => string;

export function withBoundaryAndSuspense(Component: React.ComponentType, t: TranslateFn, path: string, rk: number) {
    return () => (
        <React.Suspense
            fallback={
                <div style={{ padding: '24px' }}>
                    <Skeleton active paragraph={{ rows: 8 }} />
                </div>
            }
        >
            <ErrorBoundary
                key={`eb-${path}-${rk}`}
                fallback={<Result status="error" title={t('error.componentCrashed', '页面加载失败')} subTitle={t('error.componentCrashedSub', '组件渲染异常，请刷新后重试')} />}
            >
                <Component key={`comp-${path}-${rk}`} />
            </ErrorBoundary>
        </React.Suspense>
    );
}
