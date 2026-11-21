package org.simplepoint.plugin.dna.runtime.service.record;

/** A mandatory current target read, independent of new-reference eligibility. */
record RecordReferenceRead(String relationId, String sourceModelId, String targetRecordId) {}
