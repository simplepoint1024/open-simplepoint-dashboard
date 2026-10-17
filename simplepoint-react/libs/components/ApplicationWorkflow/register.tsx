import {lazy, Suspense} from 'react';
import {Spin} from 'antd';
import {registerWorkflowFormAdapter} from '../WorkflowForms/adapterRegistry';

const BindingEditor = lazy(() => import('./BindingEditor'));
const SnapshotView = lazy(() => import('./SnapshotView'));
const StartEntry = lazy(() => import('./StartEntry'));

/** Called once by the host, never discovered from saved process metadata. */
export function registerApplicationWorkflow() {
  return registerWorkflowFormAdapter({provider: 'simplepoint-app',
    renderBindingEditor: props => <Suspense fallback={<Spin size="small"/>}><BindingEditor {...props}/></Suspense>,
    renderStartEntry: props => <Suspense fallback={<Spin/>}><StartEntry {...props}/></Suspense>,
    renderTaskForm: props => <Suspense fallback={<Spin/>}><SnapshotView
      url={`/app/workflow/tasks/${encodeURIComponent(props.taskId)}/business-form`} onValidityChange={props.onValidityChange}/></Suspense>,
  });
}
