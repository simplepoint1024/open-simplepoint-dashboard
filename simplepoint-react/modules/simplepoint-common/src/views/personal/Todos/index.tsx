import {useSearchParams} from 'react-router';
import WorkflowInbox from '@simplepoint/components/WorkflowRuntime/Inbox';
import {InboxDetail} from '../components/Inbox';

/** Old manual-todo bookmarks remain readable, but no longer own the primary work queue. */
export default function PersonalTodos() {
  const [params, setParams] = useSearchParams();
  const legacyId = params.get('id');
  return <><WorkflowInbox/>{legacyId && <InboxDetail kind="todos" id={legacyId} readOnly
    close={() => {const next = new URLSearchParams(params); next.delete('id'); setParams(next);}}/>}</>;
}
