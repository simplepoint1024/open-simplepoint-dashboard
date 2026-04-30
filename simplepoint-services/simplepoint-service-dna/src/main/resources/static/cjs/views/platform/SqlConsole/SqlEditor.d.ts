export type SqlEditorRef = {
    insertText: (text: string) => void;
};
type SqlEditorProps = {
    value: string;
    onChange: (value: string) => void;
    onExecute?: () => void;
};
export declare const SqlEditor: import("react").ForwardRefExoticComponent<SqlEditorProps & import("react").RefAttributes<SqlEditorRef>>;
export {};
