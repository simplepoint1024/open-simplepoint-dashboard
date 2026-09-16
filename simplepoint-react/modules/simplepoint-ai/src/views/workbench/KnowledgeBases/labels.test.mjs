import assert from 'node:assert/strict';
import test from 'node:test';
import {
  knowledgeDocumentErrorLabel,
  knowledgeDocumentSourceLabel,
  knowledgeDocumentStatusLabel,
  knowledgeRetrievalModeLabel,
} from './labels.ts';

const keyOf = (key) => key;

test('knowledge lifecycle and source values resolve through local keys', () => {
  assert.deepEqual(
    ['UPLOAD', 'TEXT'].map((value) => knowledgeDocumentSourceLabel(keyOf, value)),
    ['ai.knowledge-documents.source.UPLOAD', 'ai.knowledge-documents.source.TEXT'],
  );
  assert.deepEqual(
    ['PENDING', 'PROCESSING', 'READY', 'FAILED', 'REINDEXING', 'REINDEX_FAILED'].map(
      (value) => knowledgeDocumentStatusLabel(keyOf, value),
    ),
    [
      'ai.knowledge-documents.status.PENDING',
      'ai.knowledge-documents.status.PROCESSING',
      'ai.knowledge-documents.status.READY',
      'ai.knowledge-documents.status.FAILED',
      'ai.knowledge-documents.status.REINDEXING',
      'ai.knowledge-documents.status.REINDEX_FAILED',
    ],
  );
  assert.deepEqual(
    ['HYBRID', 'VECTOR', 'KEYWORD'].map(
      (value) => knowledgeRetrievalModeLabel(keyOf, value),
    ),
    [
      'ai.knowledge-bases.mode.HYBRID',
      'ai.knowledge-bases.mode.VECTOR',
      'ai.knowledge-bases.mode.KEYWORD',
    ],
  );
});

test('unknown knowledge values never become user-facing protocol text', () => {
  assert.equal(knowledgeDocumentSourceLabel(keyOf, 'PRIVATE'), 'ai.knowledge-documents.source.UNKNOWN');
  assert.equal(knowledgeDocumentStatusLabel(keyOf, 'PRIVATE'), 'ai.knowledge-documents.status.UNKNOWN');
  assert.equal(knowledgeRetrievalModeLabel(keyOf, 'PRIVATE'), 'ai.knowledge-bases.mode.UNKNOWN');
});

test('knowledge indexing diagnostics resolve to safe local messages', () => {
  assert.equal(
    knowledgeDocumentErrorLabel(keyOf, 'AI_KNOWLEDGE_INDEX_FAILED'),
    'ai.knowledge-documents.error.indexFailed',
  );
  assert.equal(
    knowledgeDocumentErrorLabel(keyOf, 'private backend details'),
    'ai.knowledge-documents.error.unknown',
  );
});
