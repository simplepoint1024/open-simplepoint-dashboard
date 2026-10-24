import {useContext} from 'react';
import FieldDependencies from './FieldDependencies';
import {DesignerCalculationContext} from './CalculationFieldEditor';
import type {DesignerPropertyEditorProps} from './extensions';

export default function FieldDependencyProperties({document, node}: DesignerPropertyEditorProps) {
  const context = useContext(DesignerCalculationContext);
  if (!node.fieldId) return null;
  return <FieldDependencies document={document} target={{scope: 'parent', fieldId: node.fieldId}} onLocate={context?.onLocate}/>;
}
