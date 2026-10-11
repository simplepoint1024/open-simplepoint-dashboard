// src/components/withBoundaryAndSuspense.tsx
import React from 'react';
import {Result} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {LoadingBoundary, LoadingPlaceholder} from '@simplepoint/components/LoadingFeedback';
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
    <LoadingBoundary label={t('loading', '加载中...')}>
    <React.Suspense fallback={<LoadingPlaceholder />}>
        <ErrorBoundary
            key={`eb-${path}-${refreshKey}`}
            fallback={retry => (
                <Result status="error" title={t('error.componentCrashed', '页面加载失败')}
                    subTitle={t('error.componentRetryHint', '当前页面出现异常，可重试加载，其他标签页不受影响。')}
                    extra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
                        text: t('action.retry', '重试'), onClick: retry}]}/>}/>
            )}
        >
            <Component key={`comp-${path}-${refreshKey}`} />
        </ErrorBoundary>
    </React.Suspense>
    </LoadingBoundary>
);
