import test from 'node:test';
import assert from 'node:assert/strict';
import {businessPattern,validateBusinessPatterns} from './businessPattern.ts';
test('bounded RE2 rejects unsupported patterns and handles nested repetition',()=>{
  assert.throws(()=>businessPattern('(?=a)'));
  assert.throws(()=>businessPattern('a'.repeat(513)));
  assert.equal(businessPattern('^(a+)+$').matcher('a'.repeat(10000)+'!').find(),false);
  assert.equal(businessPattern('^[汉字日本語😀]+$').matcher('日本語😀').find(),true);
});
test('parent form validates inline detail paths and honors custom text / protected fields',()=>{
  const messages=[];
  const schema={properties:{detail:{items:{properties:{values:{properties:{code:{'x-business-pattern':'^[A-Z]{2}-[0-9]+$','x-validation-text':{pattern:'编号格式为 AB-123'}},secret:{readOnly:true,'x-business-pattern':'^x$'}}}}}}}};
  validateBusinessPatterns(schema,{detail:[{values:{code:'bad',secret:'masked'}}]},{detail:{0:{values:{code:{addError:m=>messages.push(m)},secret:{addError:()=>assert.fail('protected field')}}}}},'invalid');
  assert.deepEqual(messages,['编号格式为 AB-123']);
});
