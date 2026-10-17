import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseFormAttachments} from './formAttachments.ts';
import {displayOnlyProjection} from '../displayOnly.ts';
test('attachment references reject arbitrary URLs, duplicates and size violations',()=>{
 const file={id:'file-1',name:'交通凭证.pdf',size:128};
 assert.deepEqual(parseFormAttachments(JSON.stringify([file])),[file]);
 for(const value of ['https://example.test/file',JSON.stringify([file,file]),JSON.stringify([{...file,id:'../x'}]),JSON.stringify([{...file,size:0}])])assert.throws(()=>parseFormAttachments(value));
});
test('readonly approval attachments retain download widget without editable schema',()=>{
 const result=displayOnlyProjection({'x-display-only':true,type:'string',title:'凭证'},{'ui:widget':'FormAttachments'});
 assert.equal(result.uiSchema['ui:widget'],'FormAttachments');
 assert.equal(result.uiSchema['ui:readonly'],true);
 assert.equal(result.schema.readOnly,true);
});
