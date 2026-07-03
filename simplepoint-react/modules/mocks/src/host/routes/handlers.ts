import {http, HttpResponse} from 'msw';
const {get} = http;

const microapps = [
    {
         name: 'common',
         entry: 'http://127.0.0.1:3001/common/mf/mf-manifest.json'
    },
    {
         name: 'auditing',
         entry: 'http://127.0.0.1:3002/auditing/mf/mf-manifest.json'
    },
    {
         name: 'dna',
         entry: 'http://127.0.0.1:3003/dna/mf/mf-manifest.json'
    }
]

const routes = {
    "content": [
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde001",
            "authority": "dashboard.view",
            "label": "Dashboard",
            "title": "menu.dashboard",
            "icon": "DashboardOutlined",
            "path": "/dashboard",
            "sort": 0,
            "component": "common/Dashboard",
            "disabled": false,
            "children": []
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde002",
            "authority": "test.view",
            "label": "Test",
            "title": "menu.test",
            "icon": "DashboardOutlined",
            "path": "/test",
            "sort": 0,
            "component": "common/Test",
            "disabled": false,
            "children": []
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde003",
            "authority": "system.view",
            "label": "系统配置",
            "title": "menu.system",
            "icon": "SettingOutlined",
            "path": "/system",
            "sort": 1,
            "type": "item",
            "disabled": false,
            "children": [
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde011",
                    "authority": "system.user.view",
                    "label": "用户管理",
                    "title": "menu.system.user",
                    "icon": "UserOutlined",
                    "path": "/system/user",
                    "sort": 0,
                    "type": "item",
                    "component": "common/system/User",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde012",
                    "authority": "system.menu.view",
                    "label": "菜单管理",
                    "title": "menu.system.menu",
                    "icon": "MenuOutlined",
                    "path": "/system/menu",
                    "sort": 1,
                    "type": "item",
                    "component": "common/system/Menu",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde013",
                    "authority": "system.role.view",
                    "label": "角色管理",
                    "title": "menu.system.role",
                    "icon": "UsergroupAddOutlined",
                    "path": "/system/role",
                    "sort": 2,
                    "type": "item",
                    "component": "common/system/Role",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde014",
                    "authority": "system.permission.view",
                    "label": "权限管理",
                    "title": "menu.system.permission",
                    "icon": "SafetyCertificateOutlined",
                    "path": "/system/permission",
                    "sort": 3,
                    "type": "item",
                    "component": "common/system/Permission",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde015",
                    "authority": "system.oauth-client.view",
                    "label": "客户端管理",
                    "title": "menu.system.oauthClient",
                    "icon": "ApiOutlined",
                    "path": "/system/oauth-client",
                    "sort": 3,
                    "type": "item",
                    "component": "common/system/OAuthClient",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde016",
                    "authority": "data-scope.view",
                    "label": "数据权限",
                    "title": "menu.system.data",
                    "icon": "FilterOutlined",
                    "path": "/system/data-scope",
                    "sort": 5,
                    "type": "item",
                    "component": "common/system/DataScope",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde017",
                    "authority": "field-scope.view",
                    "label": "字段权限",
                    "title": "menu.system.field",
                    "icon": "TableOutlined",
                    "path": "/system/field-scope",
                    "sort": 6,
                    "type": "item",
                    "component": "common/system/FieldScope",
                    "disabled": false,
                    "children": []
                }
            ]
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde004",
            "authority": "i18n.view",
            "label": "国际化管理",
            "title": "menu.i18n",
            "icon": "GlobalOutlined",
            "path": "/i18n",
            "type": "item",
            "sort": 2,
            "disabled": false,
            "children": [
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde021",
                    "authority": "i18n.countries.view",
                    "label": "国家管理",
                    "title": "menu.i18n.countries",
                    "icon": "GlobalOutlined",
                    "path": "/i18n/countries",
                    "sort": 0,
                    "component": "common/i18n/Countries",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde022",
                    "authority": "i18n.regions.view",
                    "label": "区域管理",
                    "title": "menu.i18n.regions",
                    "icon": "CompassOutlined",
                    "path": "/i18n/regions",
                    "sort": 1,
                    "component": "common/i18n/Region",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde023",
                    "authority": "i18n.timezones.view",
                    "label": "时区管理",
                    "title": "menu.i18n.timezones",
                    "icon": "ClockCircleOutlined",
                    "path": "/i18n/timezones",
                    "sort": 2,
                    "component": "common/i18n/Timezone",
                    "type": "item",
                    "disabled": false,
                    "children": []
                }, {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde024",
                    "authority": "i18n.languages.view",
                    "label": "语言管理",
                    "title": "menu.i18n.languages",
                    "icon": "TranslationOutlined",
                    "path": "/i18n/languages",
                    "sort": 3,
                    "component": "common/i18n/Language",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde025",
                    "authority": "i18n.namespace.view",
                    "label": "国际化命名空间",
                    "title": "menu.i18n.namespace",
                    "icon": "FolderOpenOutlined",
                    "path": "/i18n/namespace",
                    "sort": 4,
                    "component": "common/i18n/Namespace",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde026",
                    "authority": "i18n.message.view",
                    "label": "国际化消息",
                    "title": "menu.i18n.message",
                    "icon": "CommentOutlined",
                    "path": "/i18n/message",
                    "sort": 5,
                    "component": "common/i18n/Message",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
            ]
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde005",
            "authority": "ops.view",
            "label": "系统维护",
            "title": "menu.ops",
            "icon": "ClusterOutlined",
            "path": "/ops",
            "type": "item",
            "disabled": false,
            "children": [
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde031",
                    "authority": "ops.micro-plugin.view",
                    "label": "插件市场",
                    "title": "menu.ops.microPlugin",
                    "icon": "BuildOutlined",
                    "path": "/ops/micro-plugin",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde032",
                    "authority": "ops.microapp.view",
                    "label": "微应用管理",
                    "title": "menu.ops.microapps",
                    "icon": "DropboxOutlined",
                    "path": "/ops/microapp",
                    "component": "common/ops/Microapp",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde033",
                    "authority": "ops.micro-service.view",
                    "label": "微服务管理",
                    "title": "menu.ops.microService",
                    "icon": "QrcodeOutlined",
                    "path": "common/ops/micro-service",
                    "type": "item",
                    "disabled": false,
                    "children": []
                }
            ]
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde006",
            "label": "监控审计",
            "title": "menu.monitoring",
            "icon": "EyeOutlined",
            "path": "/monitoring",
            "authority": "monitoring.view",
            "type": "item",
            "disabled": false,
            "children": [
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde041",
                    "label": "登录日志",
                    "title": "menu.monitoring.loginLog",
                    "icon": "SendOutlined",
                    "path": "/monitoring/login-log",
                    "authority": "login.logs.view",
                    "sort": 0,
                    "component": "auditing/monitoring/LoginLog",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde042",
                    "label": "权限变更记录",
                    "title": "menu.monitoring.permissionChangeLog",
                    "icon": "SafetyCertificateOutlined",
                    "path": "/monitoring/permission-change-log",
                    "authority": "permission.change.logs.view",
                    "sort": 1,
                    "component": "auditing/monitoring/PermissionChangeLog",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde043",
                    "label": "错误日志",
                    "title": "menu.monitoring.errorLog",
                    "icon": "WarningOutlined",
                    "path": "/monitoring/error-log",
                    "authority": "error.logs.view",
                    "sort": 2,
                    "component": "auditing/monitoring/ErrorLog",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde044",
                    "label": "服务限流",
                    "title": "menu.monitoring.serviceRateLimit",
                    "icon": "CloudServerOutlined",
                    "path": "/monitoring/service-rate-limit",
                    "authority": "service.rate.limit.rules.view",
                    "sort": 3,
                    "component": "auditing/monitoring/ServiceRateLimitRule",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde045",
                    "label": "接口限流",
                    "title": "menu.monitoring.endpointRateLimit",
                    "icon": "ApiOutlined",
                    "path": "/monitoring/endpoint-rate-limit",
                    "authority": "endpoint.rate.limit.rules.view",
                    "sort": 4,
                    "component": "auditing/monitoring/EndpointRateLimitRule",
                    "type": "item",
                    "disabled": false,
                    "children": []
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde046",
                    "label": "Redis管理",
                    "title": "menu.monitoring.redis",
                    "icon": "DatabaseOutlined",
                    "path": "/monitoring/redis",
                    "authority": "redis.entries.view",
                    "sort": 5,
                    "component": "auditing/monitoring/Redis",
                    "type": "item",
                    "disabled": false,
                    "children": []
                }
            ]
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde007",
            "label": "平台管理",
            "title": "menu.platform",
            "icon": "ApartmentOutlined",
            "path": "/platform",
            "authority": "tenants.view",
            "type": "item",
            "sort": 3,
            "disabled": false,
            "permissions": [
                {
                    "name": "Platform View",
                    "authority": "tenants.view",
                    "resource": "/platform",
                    "description": "允许访问平台管理菜单"
                }
            ],
            "children": [
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde051",
                    "label": "租户管理",
                    "title": "menu.platform.tenants",
                    "icon": "TeamOutlined",
                    "path": "/platform/tenants",
                    "authority": "tenants.view",
                    "sort": 0,
                    "component": "common/platform/Tenant",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Tenant View",
                            "authority": "tenants.view",
                            "resource": "/platform/tenants",
                            "description": "允许访问租户管理页面"
                        },
                        {
                            "name": "Tenant Create",
                            "authority": "tenants.create",
                            "resource": "/platform/tenants",
                            "description": "允许创建租户"
                        },
                        {
                            "name": "Tenant Edit",
                            "authority": "tenants.edit",
                            "resource": "/platform/tenants",
                            "description": "允许编辑租户"
                        },
                        {
                            "name": "Tenant Delete",
                            "authority": "tenants.delete",
                            "resource": "/platform/tenants",
                            "description": "允许删除租户"
                        }
                    ]
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde052",
                    "label": "套餐包管理",
                    "title": "menu.platform.packages",
                    "icon": "AppstoreOutlined",
                    "path": "/platform/packages",
                    "authority": "packages.view",
                    "sort": 1,
                    "component": "common/platform/Package",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Package View",
                            "authority": "packages.view",
                            "resource": "/platform/packages",
                            "description": "允许访问套餐包管理页面"
                        },
                        {
                            "name": "Package Create",
                            "authority": "packages.create",
                            "resource": "/platform/packages",
                            "description": "允许创建套餐包"
                        },
                        {
                            "name": "Package Edit",
                            "authority": "packages.edit",
                            "resource": "/platform/packages",
                            "description": "允许编辑套餐包"
                        },
                        {
                            "name": "Package Delete",
                            "authority": "packages.delete",
                            "resource": "/platform/packages",
                            "description": "允许删除套餐包"
                        }
                    ]
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde053",
                    "label": "应用管理",
                    "title": "menu.platform.applications",
                    "icon": "AppstoreOutlined",
                    "path": "/platform/applications",
                    "authority": "applications.view",
                    "sort": 2,
                    "component": "common/platform/Application",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Application View",
                            "authority": "applications.view",
                            "resource": "/platform/applications",
                            "description": "允许访问应用管理页面"
                        },
                        {
                            "name": "Application Create",
                            "authority": "applications.create",
                            "resource": "/platform/applications",
                            "description": "允许创建应用"
                        },
                        {
                            "name": "Application Edit",
                            "authority": "applications.edit",
                            "resource": "/platform/applications",
                            "description": "允许编辑应用"
                        },
                        {
                            "name": "Application Delete",
                            "authority": "applications.delete",
                            "resource": "/platform/applications",
                            "description": "允许删除应用"
                        }
                    ]
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde054",
                    "label": "功能管理",
                    "title": "menu.platform.features",
                    "icon": "FunctionOutlined",
                    "path": "/platform/features",
                    "authority": "features.view",
                    "sort": 3,
                    "component": "common/platform/Feature",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Feature View",
                            "authority": "features.view",
                            "resource": "/platform/features",
                            "description": "允许访问功能管理页面"
                        },
                        {
                            "name": "Feature Create",
                            "authority": "features.create",
                            "resource": "/platform/features",
                            "description": "允许创建功能"
                        },
                        {
                            "name": "Feature Edit",
                            "authority": "features.edit",
                            "resource": "/platform/features",
                            "description": "允许编辑功能"
                        },
                        {
                            "name": "Feature Delete",
                            "authority": "features.delete",
                            "resource": "/platform/features",
                            "description": "允许删除功能"
                        }
                    ]
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde055",
                    "label": "字典管理",
                    "title": "menu.platform.dictionaries",
                    "icon": "BookOutlined",
                    "path": "/platform/dictionaries",
                    "authority": "dictionaries.view",
                    "sort": 4,
                    "component": "common/platform/Dictionary",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Dictionary View",
                            "authority": "dictionaries.view",
                            "resource": "/platform/dictionaries",
                            "description": "允许访问字典管理页面"
                        },
                        {
                            "name": "Dictionary Create",
                            "authority": "dictionaries.create",
                            "resource": "/platform/dictionaries",
                            "description": "允许创建字典"
                        },
                        {
                            "name": "Dictionary Edit",
                            "authority": "dictionaries.edit",
                            "resource": "/platform/dictionaries",
                            "description": "允许编辑字典"
                        },
                        {
                            "name": "Dictionary Delete",
                            "authority": "dictionaries.delete",
                            "resource": "/platform/dictionaries",
                            "description": "允许删除字典"
                        },
                        {
                            "name": "Dictionary Config Item",
                            "authority": "dictionaries.config.item",
                            "resource": "/platform/dictionaries",
                            "description": "允许配置字典项"
                        }
                    ]
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde056",
                    "label": "组织机构管理",
                    "title": "menu.platform.organizations",
                    "icon": "ApartmentOutlined",
                    "path": "/platform/organizations",
                    "authority": "organizations.view",
                    "sort": 5,
                    "component": "common/platform/Organization",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Organization View",
                            "authority": "organizations.view",
                            "resource": "/platform/organizations",
                            "description": "允许访问组织机构管理页面"
                        },
                        {
                            "name": "Organization Create",
                            "authority": "organizations.create",
                            "resource": "/platform/organizations",
                            "description": "允许创建组织机构"
                        },
                        {
                            "name": "Organization Edit",
                            "authority": "organizations.edit",
                            "resource": "/platform/organizations",
                            "description": "允许编辑组织机构"
                        },
                        {
                            "name": "Organization Delete",
                            "authority": "organizations.delete",
                            "resource": "/platform/organizations",
                            "description": "允许删除组织机构"
                        }
                    ]
                },
                {
                    "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde057",
                    "label": "对象存储",
                    "title": "menu.platform.objectStorage",
                    "icon": "CloudUploadOutlined",
                    "path": "/platform/object-storage",
                    "authority": "storage.objects.view",
                    "sort": 6,
                    "component": "common/platform/ObjectStorage",
                    "type": "item",
                    "disabled": false,
                    "permissions": [
                        {
                            "name": "Object Storage View",
                            "authority": "storage.objects.view",
                            "resource": "/platform/object-storage",
                            "description": "允许访问对象存储页面"
                        },
                        {
                            "name": "Object Storage Create",
                            "authority": "storage.objects.create",
                            "resource": "/platform/object-storage",
                            "description": "允许上传对象"
                        },
                        {
                            "name": "Object Storage Delete",
                            "authority": "storage.objects.delete",
                            "resource": "/platform/object-storage",
                            "description": "允许删除对象"
                        },
                        {
                            "name": "Object Storage Quota View",
                            "authority": "storage.quotas.view",
                            "resource": "/platform/object-storage",
                            "description": "允许查看租户对象存储配额"
                        },
                        {
                            "name": "Object Storage Quota Create",
                            "authority": "storage.quotas.create",
                            "resource": "/platform/object-storage",
                            "description": "允许新增租户对象存储配额"
                        },
                        {
                            "name": "Object Storage Quota Edit",
                            "authority": "storage.quotas.edit",
                            "resource": "/platform/object-storage",
                            "description": "允许编辑租户对象存储配额"
                        },
                        {
                            "name": "Object Storage Quota Delete",
                            "authority": "storage.quotas.delete",
                            "resource": "/platform/object-storage",
                            "description": "允许删除租户对象存储配额"
                        }
                    ]
                }
            ]
        },
        {
            "id": "dna00000-0000-0000-0000-000000000001",
            "authority": "dna.view",
            "disabled": false,
            "label": "DNA工作台",
            "title": "menu.dna",
            "icon": "DatabaseOutlined",
            "path": "/dna",
            "sort": 5,
            "children": [
                {
                    "id": "dna00000-0000-0000-0000-000000000002",
                    "authority": "dna.dashboard.view",
                    "disabled": false,
                    "label": "仪表盘",
                    "title": "menu.dna.dashboard",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "DashboardOutlined",
                    "path": "/dna/dashboard",
                    "type": "item",
                    "component": "dna/platform/Dashboard",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000003",
                    "authority": "dna.drivers.view",
                    "disabled": false,
                    "label": "驱动管理",
                    "title": "menu.dna.drivers",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "CloudDownloadOutlined",
                    "path": "/dna/drivers",
                    "type": "item",
                    "component": "dna/platform/JdbcDriver",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000004",
                    "authority": "dna.dataSources.view",
                    "disabled": false,
                    "label": "数据源管理",
                    "title": "menu.dna.dataSources",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "ApiOutlined",
                    "path": "/dna/data-sources",
                    "type": "item",
                    "component": "dna/platform/JdbcDataSource",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000005",
                    "authority": "dna.metadata.view",
                    "disabled": false,
                    "label": "元数据管理",
                    "title": "menu.dna.metadata",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "ApartmentOutlined",
                    "path": "/dna/metadata",
                    "type": "item",
                    "component": "dna/platform/JdbcMetadata",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000006",
                    "authority": "dna.dialects.view",
                    "disabled": false,
                    "label": "数据库方言管理",
                    "title": "menu.dna.dialects",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "DeploymentUnitOutlined",
                    "path": "/dna/dialects",
                    "type": "item",
                    "component": "dna/platform/JdbcDialect",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000010",
                    "authority": "dna.federation.view",
                    "disabled": false,
                    "label": "数据源查询",
                    "title": "menu.dna.federation",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "PartitionOutlined",
                    "path": "/dna/federation",
                    "sort": 5,
                    "children": [
                        {
                            "id": "dna00000-0000-0000-0000-000000000011",
                            "authority": "dna.federation.dataCatalogs.view",
                            "disabled": false,
                            "label": "数据目录",
                            "title": "menu.dna.federation.dataCatalogs",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "FolderOpenOutlined",
                            "path": "/dna/federation/data-catalogs",
                            "type": "item",
                            "component": "dna/platform/DataCatalog",
                            "children": []
                        },
                        {
                            "id": "dna00000-0000-0000-0000-000000000012",
                            "authority": "dna.federation.jdbcUsers.view",
                            "disabled": false,
                            "label": "JDBC连接用户",
                            "title": "menu.dna.federation.jdbcUsers",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "UserSwitchOutlined",
                            "path": "/dna/federation/jdbc-users",
                            "type": "item",
                            "component": "dna/platform/FederationJdbcUser",
                            "children": []
                        },
                        {
                            "id": "dna00000-0000-0000-0000-000000000013",
                            "authority": "dna.federation.sqlConsole.view",
                            "disabled": false,
                            "label": "SQL 控制台",
                            "title": "menu.dna.federation.sqlConsole",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "CodeOutlined",
                            "path": "/dna/federation/sql-console",
                            "type": "item",
                            "component": "dna/platform/SqlConsole",
                            "children": []
                        },
                        {
                            "id": "dna00000-0000-0000-0000-000000000014",
                            "authority": "dna.federation.queryPolicies.view",
                            "disabled": false,
                            "label": "查询策略",
                            "title": "menu.dna.federation.queryPolicies",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "SafetyOutlined",
                            "path": "/dna/federation/query-policies",
                            "type": "item",
                            "component": "dna/platform/QueryPolicy",
                            "children": []
                        },
                        {
                            "id": "dna00000-0000-0000-0000-000000000015",
                            "authority": "dna.federation.queryAudits.view",
                            "disabled": false,
                            "label": "查询审计",
                            "title": "menu.dna.federation.queryAudits",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "AuditOutlined",
                            "path": "/dna/federation/query-audits",
                            "type": "item",
                            "component": "dna/platform/QueryAudit",
                            "children": []
                        },
                        {
                            "id": "dna00000-0000-0000-0000-000000000016",
                            "authority": "dna.federation.queryTemplates.view",
                            "disabled": false,
                            "label": "查询模板",
                            "title": "menu.dna.federation.queryTemplates",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "FileTextOutlined",
                            "path": "/dna/federation/query-templates",
                            "type": "item",
                            "component": "dna/platform/QueryTemplate",
                            "children": []
                        },
                        {
                            "id": "dna00000-0000-0000-0000-000000000017",
                            "authority": "dna.federation.views.view",
                            "disabled": false,
                            "label": "联邦视图",
                            "title": "menu.dna.federation.views",
                            "parent": "dna00000-0000-0000-0000-000000000010",
                            "icon": "EyeOutlined",
                            "path": "/dna/federation/views",
                            "type": "item",
                            "component": "dna/platform/FederationView",
                            "children": []
                        }
                    ]
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000020",
                    "authority": "dna.health.view",
                    "disabled": false,
                    "label": "健康监控",
                    "title": "menu.dna.health",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "HeartOutlined",
                    "path": "/dna/health",
                    "type": "item",
                    "component": "dna/platform/HealthMonitor",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000021",
                    "authority": "dna.dataAssets.view",
                    "disabled": false,
                    "label": "数据资产",
                    "title": "menu.dna.dataAssets",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "GoldOutlined",
                    "path": "/dna/data-assets",
                    "type": "item",
                    "component": "dna/platform/DataAsset",
                    "children": []
                },
                {
                    "id": "dna00000-0000-0000-0000-000000000022",
                    "authority": "dna.dataQuality.view",
                    "disabled": false,
                    "label": "数据质量",
                    "title": "menu.dna.dataQuality",
                    "parent": "dna00000-0000-0000-0000-000000000001",
                    "icon": "CheckCircleOutlined",
                    "path": "/dna/data-quality",
                    "type": "item",
                    "component": "dna/platform/DataQuality",
                    "children": []
                }
            ]
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde008",
            "authority": "external.baidu.view",
            "disabled": false,
            "label": "外部链接",
            "title": "menu.externalLink",
            "icon": "LinkOutlined",
            "path": "/baidu",
            "component": "iframe:https://www.baidu.com",
        },
        {
            "id": "c1a2b3c4-d5e6-4f70-8900-aabbccdde009",
            "authority": "external.ant-design.view",
            "disabled": false,
            "label": "AntDesign 官网",
            "parent": "91639c9d-86b1-44cd-8539-1297df4f6218",
            "icon": "LinkOutlined",
            "path": "/ops/ant-design",
            "type": "item",
            "component": "external:https://ant.design",
        }
    ],
    "page": {
        "size": 4,
        "number": 0,
        "totalElements": 4,
        "totalPages": 1
    }
}

const serviceRoutes = {
    "services": microapps,
    "routes": routes.content,
    "entryPoint": "/mf/mf-manifest.json"
}

export default [
    get('/common/menus/service-routes', () => HttpResponse.json(serviceRoutes)),
];
