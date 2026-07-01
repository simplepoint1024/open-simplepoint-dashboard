import {http, HttpResponse} from 'msw';

export default [
  http.get('/common/oidc/clients/schema', () => {
    return HttpResponse.json(
      {
        "buttons": [{
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
        }, {
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
        }, {
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
        }], "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "properties": {
            "authorizationGrantTypes": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.authorizationGrantTypes",
              "description": "i18n:clients.description.authorizationGrantTypes",
              "x-order": 4,
              "x-ui": {"x-list-visible": "true"}
            },
            "clientAuthenticationMethods": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientAuthenticationMethods",
              "description": "i18n:clients.description.clientAuthenticationMethods",
              "x-order": 3,
              "x-ui": {"x-list-visible": "true"}
            },
            "clientId": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientId",
              "description": "i18n:clients.description.clientId",
              "x-order": 0,
              "x-ui": {"x-list-visible": "true"}
            },
            "clientIdIssuedAt": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientIdIssuedAt",
              "description": "i18n:clients.description.clientIdIssuedAt",
              "format": "date-time",
              "x-order": 8
            },
            "clientName": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientName",
              "description": "i18n:clients.description.clientName",
              "x-order": 1,
              "x-ui": {"x-list-visible": "true"}
            },
            "clientSecret": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientSecret",
              "description": "i18n:clients.description.clientSecret",
              "x-order": 2,
              "x-ui": {"widget": "password"}
            },
            "clientSecretExpiresAt": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientSecretExpiresAt",
              "description": "i18n:clients.description.clientSecretExpiresAt",
              "format": "date-time",
              "x-order": 9
            },
            "clientSettings": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.clientSettings",
              "description": "i18n:clients.description.clientSettings",
              "x-ui": {"widget": "textarea"}
            },
            "postLogoutRedirectUris": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.postLogoutRedirectUris",
              "description": "i18n:clients.description.postLogoutRedirectUris",
              "x-order": 6
            },
            "redirectUris": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.redirectUris",
              "description": "i18n:clients.description.redirectUris",
              "x-order": 5
            },
            "scopes": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.scopes",
              "description": "i18n:clients.description.scopes",
              "x-order": 7,
              "x-ui": {"x-list-visible": "true"}
            },
            "tokenSettings": {
              "type": ["string", "null"],
              "title": "i18n:clients.title.tokenSettings",
              "description": "i18n:clients.description.tokenSettings",
              "x-ui": {"widget": "textarea"}
            }
          }
        }
      }
    )
  }),

  http.get('/common/oidc/clients', () => {
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
          }
        ],
        "page": {
          "size": 20,
          "number": 0,
          "totalElements": 1,
          "totalPages": 1
        }
      }
    )
  }),
];

