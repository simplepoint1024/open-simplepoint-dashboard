import {BillingView} from '../../platform/Billing';

const TenantBilling = () => (
  <BillingView
    configKey="tenant.ai-billing"
    invocationPath="/ai/invocations"
  />
);

export default TenantBilling;
