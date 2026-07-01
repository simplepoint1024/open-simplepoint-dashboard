import {http, HttpResponse} from 'msw';

const base = '/common/ops/microapps';

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
            "title": "i18n:roles.config.permission",
            "danger": false,
            "argumentMinSize": 1,
            "key": "config.permission"
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
          'description': '拥有系统内所有权限',
          'authority': 'SYSTEM_ADMIN'
        },
        {
          'name': '用户',
          'description': '普通用户',
          'authority': 'USER'
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
  http.get(`${base}/authorized`,()=> {
    return HttpResponse.json(["COMMON"])
  })
];

