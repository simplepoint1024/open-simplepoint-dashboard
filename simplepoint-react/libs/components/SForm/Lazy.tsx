import {memo} from 'react';
import {asyncComponent} from '../AsyncComponent';

/** Keep the platform form lazy, preserving refs and allowing failed code downloads to be retried locally. */
const LazySForm = asyncComponent(() => import('./index'));

export default memo(LazySForm);
