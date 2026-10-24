import {Alert, Spin} from 'antd';
import {createElement, forwardRef, useEffect, useState, type ComponentType, type ComponentPropsWithoutRef, type ComponentRef} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import TableActions from '../Table/Actions';
import {componentResource} from './resource';

/** A failed code download stays local and retryable, without discarding the owning business page. */
export function asyncComponent<T extends ComponentType<any>>(loader: () => Promise<{default: T}>) {
  const resource = componentResource(loader);
  return forwardRef<ComponentRef<T>, ComponentPropsWithoutRef<T>>(function AsyncComponent(props, ref) {
    const {t} = useI18n();
    const [attempt, retry] = useState(0);
    const [state, setState] = useState<{component?: T; failed?: boolean}>(() => ({component: resource.current()}));
    useEffect(() => {
      let current = true;
      setState({component: resource.current()});
      void resource.load().then(component => {if (current) setState({component});},
        cause => {if (current) {console.warn('[AsyncComponent] Component loading failed', cause); setState({failed: true});}});
      return () => {current = false;};
    }, [attempt]);
    if (state.failed) return <Alert className="sp-async-component-error" type="error" showIcon
      title={t('table.loadFail', '加载失败')} action={<TableActions selectedKeys={[]} selectedRows={[]}
        buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试')}]}
        onButtonEvents={{retry: () => retry(value => value + 1)}}/>}/>;
    if (!state.component) return <Spin/>;
    return createElement(state.component, {...props, ref});
  });
}
