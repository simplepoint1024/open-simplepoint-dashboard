import {http, HttpResponse} from 'msw';

const base = '/common/i18n/countries';

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json(
      {
        "schema": {
          "$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "properties": {
            "currencyCode": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.currencyCode",
              "description": "i18n:countries.description.currencyCode",
              "x-ui": {"x-list-visible": "true"}
            },
            "currencyName": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.currencyName",
              "description": "i18n:countries.description.currencyName",
              "x-ui": {"x-list-visible": "true"}
            },
            "currencySymbol": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.currencySymbol",
              "description": "i18n:countries.description.currencySymbol",
              "x-ui": {"x-list-visible": "true"}
            },
            "defaultTimezone": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.timezone",
              "description": "i18n:countries.description.timezone",
              "x-ui": {"x-list-visible": "true"}
            },
            "enabled": {
              "type": "boolean",
              "title": "i18n:countries.title.enabled",
              "description": "i18n:countries.description.enabled",
              "x-ui": {"x-list-visible": "true"}
            },
            "flagIcon": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.flagIcon",
              "description": "i18n:countries.description.flagIcon",
              "x-ui": {"x-list-visible": "true"}
            },
            "isoCode2": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.isoCode2",
              "description": "i18n:countries.description.isoCode2",
              "x-ui": {"x-list-visible": "true"}
            },
            "isoCode3": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.isoCode3",
              "description": "i18n:countries.description.isoCode3",
              "x-ui": {"x-list-visible": "true"}
            },
            "nameEnglish": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.nameEnglish",
              "description": "i18n:countries.description.nameEnglish",
              "x-ui": {"x-list-visible": "true"}
            },
            "nameNative": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.nameNative",
              "description": "i18n:countries.description.nameNative",
              "x-ui": {"x-list-visible": "true"}
            },
            "numericCode": {
              "type": "integer",
              "title": "i18n:countries.title.numericCode",
              "description": "i18n:countries.description.numericCode",
              "x-ui": {"x-list-visible": "true"}
            },
            "phoneCode": {
              "type": ["string", "null"],
              "title": "i18n:countries.title.phoneCode",
              "description": "i18n:countries.description.phoneCode",
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



