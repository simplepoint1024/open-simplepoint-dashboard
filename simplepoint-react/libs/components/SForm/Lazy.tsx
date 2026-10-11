import {forwardRef, lazy, memo, Suspense, type ComponentProps} from 'react';
import {Spin} from 'antd';
import type PlatformForm from './index';
import type {SFormHandle} from './index';

const Form = lazy(() => import('./index'));

/** The same platform form, with a local loading boundary so list routes do not eagerly load RJSF. */
const LazySForm = forwardRef<SFormHandle, ComponentProps<typeof PlatformForm>>(function LazySForm(props, ref) {
  return <Suspense fallback={<Spin size="small"/>}><Form {...props} ref={ref}/></Suspense>;
});

export default memo(LazySForm);
