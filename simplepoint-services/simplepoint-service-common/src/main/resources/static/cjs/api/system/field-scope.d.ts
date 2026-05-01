export interface FieldScopeEntryDto {
    resource: string;
    field: string;
    access: string;
}
export declare function replaceEntries(fieldScopeId: string, entries: FieldScopeEntryDto[]): Promise<unknown>;
