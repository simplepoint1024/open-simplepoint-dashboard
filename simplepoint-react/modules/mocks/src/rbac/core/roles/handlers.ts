import {http, HttpResponse} from 'msw';

const base = '/common/roles';
const authorizedResourcesByRole = new Map<string, string[]>([
  ['1', ['dashboard.view', 'roles.view', 'roles.config.resource']],
  ['2', ['dashboard.view']],
]);
const scopeAssignmentsByRole = new Map<string, { dataScopeId: string | null; fieldScopeId: string | null }>([
  ['1', { dataScopeId: 'ds-all', fieldScopeId: null }],
  ['2', { dataScopeId: 'ds-self', fieldScopeId: 'fs-user-sensitive' }],
]);

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json(
      {
        "buttons": [
          {
            "path": "[default]",
            "color": "danger",
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
            "color": "blue",
            "variant": "outlined",
            "icon": "PlusCircleOutlined",
            "argumentMaxSize": 1,
            "sort": 0,
            "type": "primary",
            "title": "i18n:table.button.add",
            "danger": false,
            "argumentMinSize": 0,
            "key": "add"
          },
          {
            "path": "[default]",
            "color": "orange",
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
            "variant": "outlined",
            "icon": "SafetyOutlined",
            "argumentMaxSize": 1,
            "sort": 2,
            "type": "primary",
            "title": "i18n:roles.config.resource",
            "danger": false,
            "argumentMinSize": 1,
            "key": "config.resource"
          }
        ],
        "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "authority": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:roles.title.authority",
              "description": "i18n:roles.description.authority",
              "minLength": 1,
              "maxLength": 100,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "description": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:roles.title.description",
              "description": "i18n:roles.description.description",
              "maxLength": 200,
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "priority": {
              "type": "integer",
              "title": "i18n:roles.title.priority",
              "description": "i18n:roles.description.priority",
              "x-ui": {
                "x-list-visible": "true"
              }
            },
            "roleName": {
              "type": [
                "string",
                "null"
              ],
              "title": "i18n:roles.title.roleName",
              "description": "i18n:roles.description.roleName",
              "minLength": 1,
              "maxLength": 50,
              "x-ui": {
                "x-list-visible": "true"
              }
            }
          }
        }
      })
  }),
  http.get(`${base}`, () => {
    return HttpResponse.json(
      {
        "content": [
          {
            "id": "1",
            "createdBy": null,
            "updatedBy": null,
            "createdAt": "2025-09-22T13:08:42.826Z",
            "updatedAt": "2025-09-22T13:08:45.546Z",
            "roleName": "超级管理员",
            "authority": "SYSTEM_ADMIN",
            "description": null,
            "priority": null
          },
          {
            "id": "f858ab1b-97f2-439b-a2a8-22d7620c3e4e",
            "createdBy": null,
            "updatedBy": null,
            "createdAt": null,
            "updatedAt": "2025-10-27T03:18:12.024058Z",
            "roleName": "系统用户",
            "authority": "SYSTEM_USER",
            "description": "系统用户角色",
            "priority": 2
          }
        ],
        "page": {
          "size": 10,
          "number": 0,
          "totalElements": 2,
          "totalPages": 1
        }
      }
    )
  }),
  http.get(`${base}/items`, () => {
    return HttpResponse.json({
      'content': [
        {
          'name': '超级管理员',
          'description': '拥有系统内所有资源授权',
          'id': '1'
        },
        {
          'name': '用户',
          'description': '普通用户',
          'id': '2'
        }
      ],
      "page": {
        "size": 10,
        "number": 0,
        "totalElements": 2,
        "totalPages": 1
      }
    })
  }),
  http.get(`${base}/authorized`, ({request})=> {
    const roleId = new URL(request.url).searchParams.get('roleId') ?? '';
    return HttpResponse.json(authorizedResourcesByRole.get(roleId) ?? []);
  }),
  http.post(`${base}/authorize`, async ({request}) => {
    const payload = await request.json() as {
      roleId?: string | null;
      resourceCodes?: string[];
      dataScopeId?: string | null;
      fieldScopeId?: string | null;
    };
    const roleId = payload.roleId ?? '';
    const current = new Set(authorizedResourcesByRole.get(roleId) ?? []);
    (payload.resourceCodes ?? []).forEach((authority) => current.add(authority));
    authorizedResourcesByRole.set(roleId, Array.from(current));
    scopeAssignmentsByRole.set(roleId, {
      dataScopeId: payload.dataScopeId ?? scopeAssignmentsByRole.get(roleId)?.dataScopeId ?? null,
      fieldScopeId: payload.fieldScopeId ?? scopeAssignmentsByRole.get(roleId)?.fieldScopeId ?? null,
    });
    return HttpResponse.json(Array.from(current).map((resourceCodes) => ({
      roleId,
      resourceCodes,
      dataScopeId: scopeAssignmentsByRole.get(roleId)?.dataScopeId ?? null,
      fieldScopeId: scopeAssignmentsByRole.get(roleId)?.fieldScopeId ?? null,
    })));
  }),
  http.post(`${base}/unauthorized`, async ({request}) => {
    const payload = await request.json() as { roleId?: string | null; resourceCodes?: string[] };
    const roleId = payload.roleId ?? '';
    const removing = new Set(payload.resourceCodes ?? []);
    authorizedResourcesByRole.set(
      roleId,
      (authorizedResourcesByRole.get(roleId) ?? []).filter((authority) => !removing.has(authority)),
    );
    return HttpResponse.json({});
  }),
  http.get(`${base}/scope-assignment`, ({request}) => {
    const roleId = new URL(request.url).searchParams.get('roleId') ?? '';
    const assignment = scopeAssignmentsByRole.get(roleId) ?? { dataScopeId: null, fieldScopeId: null };
    return HttpResponse.json({ roleId, ...assignment });
  }),
  http.put(`${base}/scope-assignment`, async ({request}) => {
    const payload = await request.json() as {
      roleId?: string;
      dataScopeId?: string | null;
      fieldScopeId?: string | null;
    };
    const roleId = payload.roleId ?? '';
    scopeAssignmentsByRole.set(roleId, {
      dataScopeId: payload.dataScopeId ?? null,
      fieldScopeId: payload.fieldScopeId ?? null,
    });
    return HttpResponse.json({});
  }),
];
