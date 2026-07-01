import {http, HttpResponse} from 'msw';

const base = '/common/i18n/namespaces';

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json(
      {
        "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "code": {
              "type": ["string", "null"],
              "title": "i18n:namespaces.title.code",
              "description": "i18n:namespaces.description.code",
              "x-ui": {"x-list-visible": "true"}
            },
            "description": {
              "type": ["string", "null"],
              "title": "i18n:namespaces.title.description",
              "description": "i18n:namespaces.description.description",
              "x-ui": {"x-list-visible": "true"}
            },
            "module": {
              "type": ["string", "null"],
              "title": "i18n:namespaces.title.module",
              "description": "i18n:namespaces.description.module",
              "x-ui": {"x-list-visible": "true"}
            },
            "name": {
              "type": ["string", "null"],
              "title": "i18n:namespaces.title.name",
              "description": "i18n:namespaces.description.name",
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



