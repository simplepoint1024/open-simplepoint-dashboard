const property = (name, kind, value) => ({name, acceptedKinds: [kind], required: true, defaultValue: {kind, value}});
const descriptor = (type, fieldType, properties = {}) => ({type, version: '1', container: false, fieldRequired: Boolean(fieldType),
  supportedFieldTypes: fieldType ? [fieldType] : [], propertySchema: properties, allowedParentTypes: [], allowedChildTypes: []});
export const standardComponents = [
  descriptor('display.divider'),
  descriptor('display.image', null, {src: property('src', 'STRING', ''), height: property('height', 'INTEGER', '180')}),
  descriptor('display.alert', null, {text: property('text', 'STRING', '提示内容'), severity: property('severity', 'STRING', 'info')}),
  descriptor('display.progress', null, {percent: property('percent', 'INTEGER', '0')}),
  descriptor('display.icon', null, {icon: property('icon', 'STRING', 'InfoCircleOutlined'), size: property('size', 'INTEGER', '24')}),
  descriptor('display.markdown', null, {text: property('text', 'STRING', '### 标题\n请输入内容')}),
  descriptor('field.rating', 'INT32', {count: property('count', 'INTEGER', '5')}),
  ...['cascader', 'transfer'].map(type => descriptor('field.' + type, 'JSON', {options: property('options', 'JSON', '[]')})),
  descriptor('field.switch', 'BOOLEAN'), descriptor('field.time', 'TIME'),
];
