import {http, HttpResponse} from 'msw';

const base = '/common/i18n/regions';

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json(
      {
        "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "properties": {
            "code": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.code",
              "description": "i18n:regions.description.code",
              "x-ui": {"x-list-visible": "true"}
            },
            "countryCode": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.countryCode",
              "description": "i18n:regions.description.countryCode",
              "x-ui": {"x-list-visible": "true"}
            },
            "level": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.level",
              "description": "i18n:regions.description.level",
              "x-ui": {"x-list-visible": "true"}
            },
            "nameEnglish": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.nameEnglish",
              "description": "i18n:regions.description.nameEnglish",
              "x-ui": {"x-list-visible": "true"}
            },
            "nameNative": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.nameNative",
              "description": "i18n:regions.description.nameNative",
              "x-ui": {"x-list-visible": "true"}
            },
            "parentId": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.parentCode",
              "description": "i18n:regions.description.parentCode",
              "x-ui": {"x-list-visible": "true"}
            },
            "postalCode": {
              "type": ["string", "null"],
              "title": "i18n:regions.title.postalCode",
              "description": "i18n:regions.description.postalCode",
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
      })
  }),

  http.get(`${base}`, () => {
    return HttpResponse.json(
      {"content": [], "page": {"size": 20, "number": 0, "totalElements": 0, "totalPages": 0}}
    )
  }),
];



