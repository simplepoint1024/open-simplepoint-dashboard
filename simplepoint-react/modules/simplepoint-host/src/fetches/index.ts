export default {
  'rbac-roles': {
    baseUrl: '/common/roles',
    i18nNamespaces: ['roles'],
    name: 'roles',
    expansion: {
      roleItems: "/common/roles/items",
      authorized: "/common/roles/authorized",
      authorize: "/common/roles/authorize",
    }
  },
  'i18n-languages': {
    baseUrl: '/common/i18n/languages',
    i18nNamespaces: ['table', 'languages'],
    name: 'languages',
    expansion: {
      mapping: '/mapping',
    }
  },
  'i18n-messages': {
    baseUrl: '/common/i18n/messages',
    i18nNamespaces: ['table', 'messages'],
    name: 'messages',
    expansion: {
      mapping: '/mapping',
    }
  }
}