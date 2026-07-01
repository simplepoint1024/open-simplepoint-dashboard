import {http, HttpResponse} from 'msw';

const base = '/common/i18n/messages';

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json(
      {
        "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "properties": {
            "code": {
              "type": ["string", "null"],
              "title": "i18n:messages.title.code",
              "description": "i18n:messages.description.code",
              "minLength": 1,
              "maxLength": 256,
              "x-order": 2,
              "x-ui": {"x-list-visible": "true"}
            },
            "description": {
              "type": ["string", "null"],
              "title": "i18n:messages.title.description",
              "description": "i18n:messages.description.description",
              "minLength": 1,
              "maxLength": 2048,
              "x-order": 4
            },
            "global": {
              "type": "boolean",
              "title": "i18n:messages.title.global",
              "description": "i18n:messages.description.global",
              "x-order": 5,
              "x-ui": {"x-list-visible": "true"}
            },
            "locale": {
              "type": ["string", "null"],
              "title": "i18n:messages.title.locale",
              "description": "i18n:messages.description.locale",
              "minLength": 1,
              "maxLength": 128,
              "x-order": 0,
              "x-ui": {"x-list-visible": "true"}
            },
            "message": {
              "type": ["string", "null"],
              "title": "i18n:messages.title.message",
              "description": "i18n:messages.description.message",
              "minLength": 1,
              "maxLength": 2048,
              "x-order": 3,
              "x-ui": {"x-list-visible": "true"}
            },
            "namespace": {
              "type": ["string", "null"],
              "title": "i18n:messages.title.namespace",
              "description": "i18n:messages.description.namespace",
              "minLength": 1,
              "maxLength": 128,
              "x-order": 1,
              "x-ui": {"x-list-visible": "true"}
            }
          }
        },
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
        }]
      }
    )
  }),

  http.get(`${base}`, () => {
    return HttpResponse.json(
      {"content": [], "page": {"size": 20, "number": 0, "totalElements": 0, "totalPages": 0}}
    )
  }),
];



