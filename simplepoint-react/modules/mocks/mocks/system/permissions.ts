import {http, HttpResponse} from 'msw';

const base = '/common/permissions';

const items = [
  // Dashboard
  {id: 'p-001', name: '工作台访问', authority: 'dashboard.view', resource: '/dashboard', description: '允许访问 Dashboard 首页', type: 0},
  // RBAC - system
  {id: 'p-010', name: '平台管理访问', authority: 'system.view', resource: '/system', description: '允许访问平台管理模块', type: 0},
  {id: 'p-011', name: '菜单查看', authority: 'system.menu.view', resource: '/system/menu', description: '允许查看菜单管理', type: 0},
  {id: 'p-012', name: '菜单创建', authority: 'menus.create', resource: '/common/menus', description: '允许创建菜单', type: 1},
  {id: 'p-013', name: '菜单编辑', authority: 'menus.edit', resource: '/common/menus', description: '允许编辑菜单', type: 1},
  {id: 'p-014', name: '菜单删除', authority: 'menus.delete', resource: '/common/menus', description: '允许删除菜单', type: 1},
  {id: 'p-015', name: '角色查看', authority: 'system.role.view', resource: '/system/role', description: '允许查看角色管理', type: 0},
  {id: 'p-016', name: '角色创建', authority: 'roles.create', resource: '/common/roles', description: '允许创建角色', type: 1},
  {id: 'p-017', name: '角色编辑', authority: 'roles.edit', resource: '/common/roles', description: '允许编辑角色', type: 1},
  {id: 'p-018', name: '角色删除', authority: 'roles.delete', resource: '/common/roles', description: '允许删除角色', type: 1},
  {id: 'p-020', name: '用户查看', authority: 'system.user.view', resource: '/system/user', description: '允许查看用户配置', type: 0},
  {id: 'p-021', name: '用户创建', authority: 'users.create', resource: '/common/users', description: '允许创建用户', type: 1},
  {id: 'p-022', name: '用户编辑', authority: 'users.edit', resource: '/common/users', description: '允许编辑用户', type: 1},
  {id: 'p-023', name: '用户删除', authority: 'users.delete', resource: '/common/users', description: '允许删除用户', type: 1},
  {id: 'p-024', name: '权限查看', authority: 'permissions.view', resource: '/common/permissions', description: '允许查看权限列表', type: 0},
  {id: 'p-025', name: '权限创建', authority: 'permissions.create', resource: '/common/permissions', description: '允许创建权限', type: 1},
  {id: 'p-026', name: '权限编辑', authority: 'permissions.edit', resource: '/common/permissions', description: '允许编辑权限', type: 1},
  {id: 'p-027', name: '权限删除', authority: 'permissions.delete', resource: '/common/permissions', description: '允许删除权限', type: 1},
  // I18N
  {id: 'p-030', name: '国际化访问', authority: 'locale.view', resource: '/locale', description: '允许访问国际化管理模块', type: 0},
  {id: 'p-031', name: '时区查看', authority: 'locale.timezone.view', resource: '/locale/timezone', description: '允许查看时区管理', type: 0},
  {id: 'p-032', name: '国家地区查看', authority: 'locale.country-range.view', resource: '/locale/country-range', description: '允许查看国家和地区', type: 0},
  {id: 'p-033', name: '多语言管理查看', authority: 'locale.language.view', resource: '/locale/language', description: '允许查看多语言管理', type: 0},
  {id: 'p-034', name: '国际化键值查看', authority: 'locale.message.view', resource: '/locale/message', description: '允许查看国际化键值', type: 0},
  // OIDC
  {id: 'p-040', name: 'OIDC客户端查看', authority: 'oidc.clients.view', resource: '/oidc/clients', description: '允许查看 OIDC 客户端', type: 0},
  {id: 'p-041', name: 'OIDC客户端创建', authority: 'oidc.clients.create', resource: '/oidc/clients', description: '允许创建 OIDC 客户端', type: 1},
  {id: 'p-042', name: 'OIDC客户端编辑', authority: 'oidc.clients.edit', resource: '/oidc/clients', description: '允许编辑 OIDC 客户端', type: 1},
  {id: 'p-043', name: 'OIDC客户端删除', authority: 'oidc.clients.delete', resource: '/oidc/clients', description: '允许删除 OIDC 客户端', type: 1},
  // Features & Ops
  {id: 'p-050', name: '功能管理查看', authority: 'features.view', resource: '/common/platform/features', description: '允许查看功能管理', type: 0},
  {id: 'p-051', name: '功能创建', authority: 'features.create', resource: '/common/platform/features', description: '允许创建功能', type: 1},
  {id: 'p-052', name: '功能编辑', authority: 'features.edit', resource: '/common/platform/features', description: '允许编辑功能', type: 1},
  {id: 'p-053', name: '功能删除', authority: 'features.delete', resource: '/common/platform/features', description: '允许删除功能', type: 1},
  {id: 'p-054', name: '配置功能权限', authority: 'features.config.permission', resource: '/common/platform/features', description: '允许为功能分配权限', type: 1},
  {id: 'p-060', name: '微应用查看', authority: 'ops.microapp.view', resource: '/ops/app', description: '允许访问微应用中心', type: 0},
  {id: 'p-061', name: '微服务查看', authority: 'ops.microservice.view', resource: '/ops/services', description: '允许访问微服务中心', type: 0},
];

const itemsByAuthority = new Map(items.map((item) => [item.authority, item]));

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json({
      buttons: [
        {
          path: '[default]',
          authority: 'permissions.create',
          variant: 'outlined',
          icon: 'PlusCircleOutlined',
          argumentMaxSize: 1,
          sort: 0,
          type: 'primary',
          title: 'i18n:table.button.create',
          danger: false,
          argumentMinSize: 0,
          key: 'add',
        },
        {
          path: '[default]',
          color: 'orange',
          authority: 'permissions.edit',
          variant: 'outlined',
          icon: 'EditOutlined',
          argumentMaxSize: 1,
          sort: 1,
          type: 'primary',
          title: 'i18n:table.button.edit',
          danger: false,
          argumentMinSize: 1,
          key: 'edit',
        },
        {
          path: '[default]',
          color: 'danger',
          authority: 'permissions.delete',
          variant: 'outlined',
          icon: 'MinusCircleOutlined',
          argumentMaxSize: 10,
          sort: 2,
          type: 'primary',
          title: 'i18n:table.button.delete',
          danger: true,
          argumentMinSize: 1,
          key: 'delete',
        },
      ],
      schema: {
        $schema: 'http://json-schema.org/draft-07/schema#',
        type: 'object',
        properties: {
          name: {
            type: ['string', 'null'],
            title: 'i18n:permissions.title.name',
            description: 'i18n:permissions.description.name',
            maxLength: 100,
            minLength: 1,
            'x-order': 0,
            'x-ui': {'x-list-visible': 'true'},
          },
          authority: {
            type: ['string', 'null'],
            title: 'i18n:permissions.title.authority',
            description: 'i18n:permissions.description.authority',
            maxLength: 100,
            minLength: 1,
            'x-order': 1,
            'x-ui': {'x-list-visible': 'true'},
          },
          resource: {
            type: ['string', 'null'],
            title: 'i18n:permissions.title.resource',
            description: 'i18n:permissions.description.resource',
            maxLength: 100,
            minLength: 1,
            'x-order': 2,
            'x-ui': {'x-list-visible': 'true'},
          },
          description: {
            type: ['string', 'null'],
            title: 'i18n:permissions.title.description',
            description: 'i18n:permissions.description.description',
            maxLength: 100,
            minLength: 1,
            'x-order': 3,
            'x-ui': {'x-list-visible': 'true'},
          },
          type: {
            type: 'integer',
            title: 'i18n:permissions.title.type',
            description: 'i18n:permissions.description.type',
            'x-order': 4,
            'x-ui': {'x-list-visible': 'true'},
          },
        },
      },
    });
  }),
  http.get(`${base}/items/selected`, ({request}) => {
    const url = new URL(request.url);
    const authorities = (url.searchParams.get('authorities') ?? '').split(',').filter(Boolean);
    return HttpResponse.json(authorities.map((a) => itemsByAuthority.get(a)).filter(Boolean));
  }),
  http.get(`${base}/items`, ({request}) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 10);
    const start = page * size;
    return HttpResponse.json({
      content: items.slice(start, start + size),
      page: {
        size,
        number: page,
        totalElements: items.length,
        totalPages: Math.ceil(items.length / size),
      },
    });
  }),
  http.get(base, ({request}) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 20);
    const start = page * size;
    return HttpResponse.json({
      content: items.slice(start, start + size),
      page: {
        size,
        number: page,
        totalElements: items.length,
        totalPages: Math.ceil(items.length / size),
      },
    });
  }),
];
