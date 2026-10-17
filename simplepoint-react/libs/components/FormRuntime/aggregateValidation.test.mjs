import {test} from 'node:test';
import assert from 'node:assert/strict';
import {aggregateIssues, deriveFormTotals} from './aggregateValidation.ts';
const document = {fields: [{fieldId:'start',type:'DATETIME'}],constraints:[
  {kind:'DETAIL_COUNT',detailId:'lines',minimum:'1',maximum:'40',message:'明细不能为空'},
  {kind:'DETAIL_RANGE',detailId:'lines',detailFieldId:'amount',minimum:'0.01',message:'金额须为正数'},
  {kind:'DETAIL_SUM',fieldId:'total',detailId:'lines',detailFieldId:'amount',message:'合计不一致'},
]};
test('totals use exact decimals and reject mismatched and empty detail submissions',()=>{
 const values=deriveFormTotals(document,{lines:[{values:{amount:'9007199254740993.01'}},{values:{amount:'0.09'}}]});
 assert.equal(values.total,'9007199254740993.10');
 assert.deepEqual(aggregateIssues(document,values),[]);
 assert.equal(aggregateIssues(document,{...values,total:'9007199254740993.11'})[0].message,'合计不一致');
 assert(aggregateIssues(document,{lines:[],total:'0'}).some(x=>x.message==='明细不能为空'));
 assert(aggregateIssues(document,{lines:[{values:{amount:'-1'}}],total:'-1'}).some(x=>x.message==='金额须为正数'));
});
test('temporal ordering compares instants across offsets',()=>{
 const doc={fields:[{fieldId:'end',type:'DATETIME'}],constraints:[{kind:'AFTER',fieldId:'end',otherFieldId:'start',message:'结束时间须晚于开始时间'}]};
 assert.deepEqual(aggregateIssues(doc,{start:'2026-10-16T09:00:00+08:00',end:'2026-10-16T02:00:00Z'}),[]);
 assert.equal(aggregateIssues(doc,{start:'2026-10-16T09:00:00+08:00',end:'2026-10-16T00:00:00Z'}).length,1);
});
