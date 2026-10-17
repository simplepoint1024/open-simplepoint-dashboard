import type {WidgetProps} from '@rjsf/utils';
import {DomainSelectWidget} from './DomainSelectWidget';

/** Compatibility alias for previously persisted workflow form schemas. */
export default function TenantDirectoryWidget(props: WidgetProps) {
  return <DomainSelectWidget {...props} kind={props.options.kind === 'roles' ? 'role' : 'user'}/>;
}
