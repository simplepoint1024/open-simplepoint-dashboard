import {http, HttpResponse} from 'msw';

const base = '/common/menus';

const unique = (values: string[]) => Array.from(new Set(values));

let menuFeatures: Record<string, string[]> = {
  'a333fea0-dc98-479c-a2fa-57764d81d20a': ['DASHBOARD'],
};

export default [
  http.get('/common/menus/schema', () => {
    return HttpResponse.json(
      {
        "buttons": [
          {
            "path": "[default]",
            "authority": "menus.create",
            "variant": "outlined",
            "icon": "PlusCircleOutlined",
            "argumentMaxSize": 1,
            "sort": 0,
            "type": "primary",
            "title": "i18n:table.button.create",
            "danger": false,
            "argumentMinSize": 0,
            "key": "add"
          },
          {
            "path": "[default]",
            "authority": "menus.delete",
            "variant": "outlined",
            "icon": "MinusCircleOutlined",
            "argumentMaxSize": 10,
            "sort": 2,
            "type": "primary",
            "title": "i18n:table.button.delete",
            "danger": true,
            "argumentMinSize": 1,
            "key": "delete"
          },
          {
            "path": "[default]",
            "color": "orange",
            "authority": "menus.edit",
            "variant": "outlined",
            "icon": "EditOutlined",
            "argumentMaxSize": 1,
            "sort": 1,
            "type": "primary",
            "title": "i18n:table.button.edit",
            "danger": false,
            "argumentMinSize": 1,
            "key": "edit"
          },
          {
            "path": "[default]",
            "color": "orange",
            "authority": "menus.config.permission",
            "variant": "outlined",
            "icon": "SafetyOutlined",
            "argumentMaxSize": 1,
            "sort": 3,
            "type": "primary",
            "title": "i18n:menus.config.feature",
            "danger": false,
            "argumentMinSize": 1,
            "key": "config.permission"
          }
        ],
        "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "label": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.label",
              "description": "i18n:menus.description.label",
              "minLength": 1,
              "maxLength": 50,
              "x-order": 0,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "title": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.title",
              "description": "i18n:menus.description.title",
              "minLength": 1,
              "maxLength": 100,
              "x-order": 0,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "icon": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.icon",
              "description": "i18n:menus.description.icon",
              "minLength": 1,
              "maxLength": 100,
              "x-order": 1,
              "x-ui": {
                "x-list-visible": "true",
                "widget": "IconPicker"
              }
            },
            "sort": {
              "type": "integer",
              "title": "i18n:menus.title.sort",
              "description": "i18n:menus.description.sort",
              "x-order": 1,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "type": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.type",
              "description": "i18n:menus.description.type",
              "minLength": 1,
              "maxLength": 32,
              "enum": ["item", "submenu", "group", "divider"],
              "x-order": 2,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "path": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.path",
              "description": "i18n:menus.description.path",
              "minLength": 1,
              "maxLength": 200,
              "x-order": 3,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "component": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.component",
              "description": "i18n:menus.description.component",
              "minLength": 5,
              "maxLength": 100,
              "x-order": 4,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "authority": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:menus.title.authority",
              "description": "i18n:menus.description.authority",
              "minLength": 1,
              "maxLength": 36,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "danger": {
              "type": "boolean",
              "title": "i18n:menus.title.danger",
              "description": "i18n:menus.description.danger"
            },
            "disabled": {
              "type": "boolean",
              "title": "i18n:menus.title.disabled",
              "description": "i18n:menus.description.disabled",
              "x-ui": {
                "x-list-visible": "true"
              }
            }
          }
        }
      }
    )
  }),
  http.get('/common/menus', () => {
    return HttpResponse.json(
      {
        "content": [
          {
            "id": "38765bf8-9617-40f3-9031-68ab50c3a410",
            "updatedAt": "2025-10-29T11:33:38.693763Z",
            "authority": "ops.view",
            "disabled": false,
            "label": "系统维护",
            "icon": "SettingOutlined",
            "path": "/ops",
            "type": "submenu",
            "children": [
              {
                "id": "718ae0a1-de9f-4788-a698-d86fa0142560",
                "updatedAt": "2025-10-29T11:33:38.676644Z",
                "authority": "ops.microapp.view",
                "disabled": false,
                "label": "微应用中心",
                "parent": "38765bf8-9617-40f3-9031-68ab50c3a410",
                "icon": "AppstoreAddOutlined",
                "path": "/ops/app",
                "type": "item",
                "component": "common/ops/MicroApp",
                "children": []
              },
              {
                "id": "f2868061-f647-4f8c-aa99-666dca54ddec",
                "updatedAt": "2025-10-29T11:33:38.692253Z",
                "authority": "ops.microservice.view",
                "disabled": false,
                "label": "微服务中心",
                "parent": "38765bf8-9617-40f3-9031-68ab50c3a410",
                "icon": "AppstoreOutlined",
                "path": "/ops/services",
                "type": "item",
                "component": "common/ops/MicroService",
                "children": []
              },
              {
                "id": "a5f2c1b0-9e3e-4a7f-8c11-2b4c6d7e8f90",
                "updatedAt": "2025-10-29T11:33:38.800000Z",
                "authority": "ops.baidu.view",
                "disabled": false,
                "label": "百度",
                "parent": "38765bf8-9617-40f3-9031-68ab50c3a410",
                "icon": "LinkOutlined",
                "path": "/ops/baidu",
                "type": "item",
                "component": "iframe:https://www.baidu.com",
                "children": []
              },
              {
                "id": "b7f2c1b0-9e3e-4a7f-8c11-2b4c6d7e8f91",
                "updatedAt": "2025-10-29T11:33:38.810000Z",
                "authority": "ops.ant-design.view",
                "disabled": false,
                "label": "AntDesign 官网",
                "parent": "38765bf8-9617-40f3-9031-68ab50c3a410",
                "icon": "LinkOutlined",
                "path": "/ops/ant-design",
                "type": "item",
                "component": "external:https://ant.design",
              }
            ]
          },
          {
            "id": "36a7a730-fdab-471c-adbb-601e606cb140",
            "updatedAt": "2025-10-29T11:33:38.702761Z",
            "authority": "locale.view",
            "disabled": false,
            "label": "国际化多语言",
            "icon": "GlobalOutlined",
            "path": "/locale",
            "type": "submenu",
            "children": [
              {
                "id": "11177141-c56b-4c6d-b1e7-a1be3714f43f",
                "updatedAt": "2025-10-29T11:33:38.678160Z",
                "authority": "locale.timezone.view",
                "disabled": false,
                "label": "时区",
                "parent": "36a7a730-fdab-471c-adbb-601e606cb140",
                "icon": "HistoryOutlined",
                "path": "/locale/timezone",
                "type": "item",
                "component": "common/locale/timezone",
                "children": []
              },
              {
                "id": "4f1bd1bd-fcfe-436c-9621-c4629aaec848",
                "updatedAt": "2025-10-29T11:33:38.681253Z",
                "authority": "locale.country-range.view",
                "disabled": false,
                "label": "国家和地区",
                "parent": "36a7a730-fdab-471c-adbb-601e606cb140",
                "icon": "BankOutlined",
                "path": "/locale/country-range",
                "type": "item",
                "component": "common/locale/CountryRange",
                "children": []
              },
              {
                "id": "615717aa-e68d-4d12-8771-97df48e67329",
                "updatedAt": "2025-10-29T11:33:38.688253Z",
                "authority": "locale.language.view",
                "disabled": false,
                "label": "多语言管理",
                "parent": "36a7a730-fdab-471c-adbb-601e606cb140",
                "icon": "TranslationOutlined",
                "path": "/locale/language",
                "type": "item",
                "component": "common/locale/Language",
                "children": []
              },
              {
                "id": "37be1336-765f-48a2-8c9a-484820985dd0",
                "updatedAt": "2025-10-29T11:33:38.695762Z",
                "authority": "locale.message.view",
                "disabled": false,
                "label": "国际化键值",
                "parent": "36a7a730-fdab-471c-adbb-601e606cb140",
                "icon": "MessageOutlined",
                "path": "/locale/message",
                "type": "item",
                "component": "common/locale/message",
                "children": []
              }
            ]
          },
          {
            "id": "8d14c87f-3270-4cf4-9a3d-19749783492d",
            "updatedAt": "2025-10-15T04:19:18.445753Z",
            "authority": "system.view",
            "disabled": false,
            "label": "平台管理",
            "icon": "KeyOutlined",
            "path": "/system",
            "type": "submenu",
            "children": [
              {
                "id": "abc5e586-3919-4e6b-be46-adddf9b79035",
                "updatedAt": "2025-10-15T04:19:18.435622Z",
                "authority": "system.endpoints.view",
                "disabled": false,
                "label": "端点管理",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "ApiOutlined",
                "path": "/system/endpoints",
                "type": "item",
                "component": "common/ac/Endpoint",
                "children": []
              },
              {
                "id": "a333fea0-dc98-479c-a2fa-57764d81d20a",
                "updatedAt": "2025-10-15T04:19:18.433622Z",
                "authority": "system.menu.view",
                "disabled": false,
                "label": "菜单管理",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "MenuOutlined",
                "path": "/system/menu",
                "type": "item",
                "component": "common/ac/Menu",
                "children": []
              },
              {
                "id": "2110c7a6-6b41-482b-8154-d6a03464e58c",
                "updatedAt": "2025-10-15T04:19:18.440131Z",
                "authority": "system.role.view",
                "disabled": false,
                "label": "角色管理",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "UsergroupAddOutlined",
                "path": "/system/role",
                "type": "item",
                "component": "common/ac/Role",
                "children": []
              },
              {
                "id": "7dd653d1-f66b-4030-a27c-d7db47369bcf",
                "updatedAt": "2025-10-15T04:19:18.407190Z",
                "authority": "system.user.view",
                "disabled": false,
                "label": "用户配置",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "UserOutlined",
                "path": "/system/user",
                "type": "item",
                "component": "common/ac/User",
                "children": []
              },
              {
                "id": "1d355be0-fda5-4917-af21-220f4058f4f8",
                "updatedAt": "2025-10-15T04:19:18.447757Z",
                "authority": "data-scope.view",
                "disabled": false,
                "label": "数据权限",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "FilterOutlined",
                "path": "/system/data-scope",
                "type": "item",
                "component": "common/system/DataScope",
                "children": []
              },
              {
                "id": "1a727149-03cb-4fb2-b774-8ea550d23a7f",
                "updatedAt": "2025-10-15T04:19:18.444144Z",
                "authority": "field-scope.view",
                "disabled": false,
                "label": "字段权限",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "TableOutlined",
                "path": "/system/field-scope",
                "type": "item",
                "component": "common/system/FieldScope",
                "children": []
              },
              {
                "id": "e46136d1-44ca-4f1f-ae17-ee0aca0bef4f",
                "updatedAt": "2025-10-15T04:19:18.437622Z",
                "authority": "system.auth.view",
                "disabled": false,
                "label": "授权管理",
                "parent": "8d14c87f-3270-4cf4-9a3d-19749783492d",
                "icon": "AppstoreOutlined",
                "path": "/system/auth",
                "type": "item",
                "component": "common/ac/Auth",
                "children": []
              }
            ]
          },
          {
            "id": "af83acf1-8496-44e3-a3f1-c9442214f6bc",
            "updatedAt": "2025-10-29T11:33:38.685253Z",
            "authority": "ac.view",
            "disabled": false,
            "label": "访问控制",
            "icon": "SecurityScanOutlined",
            "path": "/ac",
            "type": "group",
            "children": []
          }
        ],
        "page": {
          "size": 20,
          "number": 0,
          "totalElements": 4,
          "totalPages": 1
        }
      }
    )
  }),
  http.get(`${base}/authorized`, ({request}) => {
    const menuId = new URL(request.url).searchParams.get('menuId') ?? '';
    return HttpResponse.json(menuFeatures[menuId] ?? []);
  }),
  http.post(`${base}/authorize`, async ({request}) => {
    const payload = await request.json() as {menuId?: string | null; featureCodes?: string[]; permissionAuthority?: string[]};
    const menuId = payload.menuId ?? '';
    const featureCodes = payload.featureCodes ?? payload.permissionAuthority ?? [];
    menuFeatures[menuId] = unique([...(menuFeatures[menuId] ?? []), ...featureCodes]);
    return HttpResponse.json((menuFeatures[menuId] ?? []).map((featureCode) => ({menuId, featureCode})));
  }),
  http.post(`${base}/unauthorized`, async ({request}) => {
    const payload = await request.json() as {menuId?: string | null; featureCodes?: string[]; permissionAuthority?: string[]};
    const menuId = payload.menuId ?? '';
    const removing = new Set(payload.featureCodes ?? payload.permissionAuthority ?? []);
    menuFeatures[menuId] = (menuFeatures[menuId] ?? []).filter((featureCode) => !removing.has(featureCode));
    return HttpResponse.json(null);
  })
];
