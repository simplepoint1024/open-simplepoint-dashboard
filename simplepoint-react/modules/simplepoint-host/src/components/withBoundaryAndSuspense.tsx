// src/components/withBoundaryAndSuspense.tsx
import React from 'react';
import {Skeleton, Result} from 'antd';
import {ErrorBoundary} from './ErrorBoundary';

type TranslateFn = (key: string, fallback?: string) => string;

interface BoundaryAndSuspenseProps {
    component: React.ComponentType;
    t: TranslateFn;
    path: string;
    refreshKey: number;
}

/**
 * Stable route boundary.
 *
 * This must remain a named component instead of returning a new anonymous
 * component from the route factory. A new component type on every host render
 * would unmount the remote page and discard its local state.
 */
export const BoundaryAndSuspense: React.FC<BoundaryAndSuspenseProps> = ({
    component: Component,
    t,
    path,
    refreshKey,
}) => (
    <React.Suspense
        fallback={
            <div style={{ padding: '24px' }}>
                <Skeleton active paragraph={{ rows: 8}} />
            </div>
        }
    >
        <ErrorBoundary
            key={`eb-${path}-${refreshKey}`}
            fallback={<Result status="error" title={t('error.componentCrashed', '页面加载失败')} subTitle={t('error.componentCrashedSub', '组件渲染异常，请刷新后重试')} />}
        >
            <Component key={`comp-${path}-${refreshKey}`} />
        </ErrorBoundary>
    </React.Suspense>
);
