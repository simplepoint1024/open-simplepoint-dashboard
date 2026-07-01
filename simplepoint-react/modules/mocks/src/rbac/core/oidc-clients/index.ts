import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'oidc.clients',
  scope: 'identity',
  backendModule: 'simplepoint-plugin-oidc-rest',
  backendController: 'OidcClientController',
  contextPath: '/common',
  paths: ['/oidc/clients'],
  entity: 'Client',
  i18nNamespaces: ['clients'],
});

export default defineMockModule(contract, handlers);
