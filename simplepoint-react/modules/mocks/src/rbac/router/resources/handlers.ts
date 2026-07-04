import {http, HttpResponse} from 'msw';

const base = '/common/resources';

const resources = [
  {
    id: 'res-dashboard',
    code: 'dashboard.view',
    name: 'Dashboard',
    label: 'Dashboard',
    title: 'menu.dashboard',
    type: 'PAGE',
    path: '/dashboard',
    component: 'common/Dashboard',
    icon: 'DashboardOutlined',
    routeKind: 'item',
    sort: 0,
    publicAccess: true,
    grantable: true,
    disabled: false,
    children: [],
  },
  {
    id: 'res-system',
    code: 'system.view',
    name: '系统配置',
    label: '系统配置',
    title: 'menu.system',
    type: 'MODULE',
    path: '/system',
    icon: 'SettingOutlined',
    routeKind: 'submenu',
    sort: 1,
    publicAccess: false,
    grantable: false,
    disabled: false,
    children: [
      {
        id: 'res-access-center',
        code: 'access-center.view',
        name: '资源授权中心',
        label: '资源授权中心',
        title: 'menu.system.accessCenter',
        type: 'PAGE',
        path: '/system/access-center',
        component: 'common/system/AccessCenter',
        icon: 'SafetyCertificateOutlined',
        routeKind: 'item',
        sort: 0,
        publicAccess: false,
        grantable: true,
        disabled: false,
        children: [
          {id: 'res-access-center-manage', code: 'access-center.manage', name: 'Resource Grant Manage', label: 'Resource Grant Manage', type: 'ACTION', grantable: true, disabled: false},
        ],
      },
      {
        id: 'res-resource',
        code: 'resources.view',
        name: '资源管理',
        label: '资源管理',
        title: 'menu.system.resource',
        type: 'PAGE',
        path: '/system/resource',
        component: 'common/system/Resource',
        icon: 'ClusterOutlined',
        routeKind: 'item',
        sort: 1,
        publicAccess: false,
        grantable: true,
        disabled: false,
        children: [
          {id: 'res-resource-create', code: 'resources.create', name: 'Resource Create', label: 'Resource Create', type: 'ACTION', grantable: true, disabled: false},
          {id: 'res-resource-edit', code: 'resources.edit', name: 'Resource Edit', label: 'Resource Edit', type: 'ACTION', grantable: true, disabled: false},
          {id: 'res-resource-delete', code: 'resources.delete', name: 'Resource Delete', label: 'Resource Delete', type: 'ACTION', grantable: true, disabled: false},
        ],
      },
      {
        id: 'res-role',
        code: 'roles.view',
        name: '角色管理',
        label: '角色管理',
        title: 'menu.system.role',
        type: 'PAGE',
        path: '/system/role',
        component: 'common/system/Role',
        icon: 'UsergroupAddOutlined',
        routeKind: 'item',
        sort: 2,
        publicAccess: false,
        grantable: true,
        disabled: false,
        children: [
          {id: 'res-role-config', code: 'roles.config.resource', name: 'Role Resource Grant', label: 'Role Resource Grant', type: 'ACTION', grantable: true, disabled: false},
        ],
      },
    ],
  },
];

const flatten = (nodes: any[]): any[] => nodes.flatMap((node) => [node, ...flatten(node.children ?? [])]);

export default [
  http.get(`${base}/schema`, () => HttpResponse.json({
    buttons: [
      {path: '[default]', authority: 'resources.create', icon: 'PlusCircleOutlined', argumentMaxSize: 1, argumentMinSize: 0, sort: 0, type: 'primary', title: 'i18n:table.button.create', danger: false, key: 'add'},
      {path: '[default]', authority: 'resources.edit', icon: 'EditOutlined', argumentMaxSize: 1, argumentMinSize: 1, sort: 1, type: 'primary', title: 'i18n:table.button.edit', danger: false, key: 'edit'},
      {path: '[default]', authority: 'resources.delete', icon: 'MinusCircleOutlined', argumentMaxSize: 10, argumentMinSize: 1, sort: 2, type: 'primary', title: 'i18n:table.button.delete', danger: true, key: 'delete'},
    ],
    schema: {
      type: 'object',
      properties: {
        code: {type: 'string', title: 'i18n:resources.title.code', description: 'i18n:resources.description.code', 'x-ui': {'x-list-visible': 'true'}},
        name: {type: 'string', title: 'i18n:resources.title.name', description: 'i18n:resources.description.name', 'x-ui': {'x-list-visible': 'true'}},
        type: {type: 'string', title: 'i18n:resources.title.type', description: 'i18n:resources.description.type', enum: ['GROUP', 'MODULE', 'PAGE', 'FEATURE', 'ACTION', 'API'], 'x-ui': {'x-list-visible': 'true'}},
        path: {type: ['string', 'null'], title: 'i18n:resources.title.path', description: 'i18n:resources.description.path', 'x-ui': {'x-list-visible': 'true'}},
        component: {type: ['string', 'null'], title: 'i18n:resources.title.component', description: 'i18n:resources.description.component', 'x-ui': {'x-list-visible': 'true'}},
        routeKind: {type: ['string', 'null'], title: 'i18n:resources.title.routeKind', description: 'i18n:resources.description.routeKind', enum: ['item', 'submenu', 'group', 'divider']},
        sort: {type: ['integer', 'null'], title: 'i18n:resources.title.sort', description: 'i18n:resources.description.sort', 'x-ui': {'x-list-visible': 'true'}},
        grantable: {type: 'boolean', title: 'i18n:resources.title.grantable', description: 'i18n:resources.description.grantable'},
        publicAccess: {type: 'boolean', title: 'i18n:resources.title.publicAccess', description: 'i18n:resources.description.publicAccess'},
        disabled: {type: 'boolean', title: 'i18n:resources.title.disabled', description: 'i18n:resources.description.disabled'},
      },
    },
  })),
  http.get(base, ({request}) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    return HttpResponse.json({
      content: resources,
      page: {size, number: page, totalElements: resources.length, totalPages: 1},
    });
  }),
  http.get(`${base}/service-routes`, () => HttpResponse.json({
    services: [],
    entryPoint: '/mf/mf-manifest.json',
    routes: resources,
    authorizationContext: {scopeType: 'PLATFORM', actorRole: 'PLATFORM_ADMIN', resources: flatten(resources).map((item) => item.code)},
  })),
];
