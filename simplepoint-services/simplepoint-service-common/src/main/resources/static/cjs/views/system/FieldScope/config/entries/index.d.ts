import { FieldScopeEntryDto } from '../../../../../api/system/field-scope';
export interface FieldScopeEntriesConfigProps {
    fieldScopeId: string;
    initialEntries?: FieldScopeEntryDto[];
    onSuccess?: () => void;
}
declare const App: ({ fieldScopeId, initialEntries, onSuccess }: FieldScopeEntriesConfigProps) => import("react/jsx-runtime").JSX.Element;
export default App;
