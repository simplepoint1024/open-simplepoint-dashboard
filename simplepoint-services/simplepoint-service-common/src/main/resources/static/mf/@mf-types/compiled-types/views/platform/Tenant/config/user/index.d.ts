export interface TenantUserConfigProps {
    tenantId: string;
    ownerId?: string;
}
declare const App: ({ tenantId, ownerId }: TenantUserConfigProps) => import("react/jsx-runtime").JSX.Element;
export default App;
