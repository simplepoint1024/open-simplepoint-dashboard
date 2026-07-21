import {ModelView} from '../../platform/Model';

const TenantModel = () => (
  <ModelView
    modelConfigKey="tenant.ai-models"
    providerConfigKey="tenant.ai-providers"
  />
);

export default TenantModel;
