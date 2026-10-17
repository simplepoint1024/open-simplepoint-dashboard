package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;

/** A validated historical projection compiled before taking the target publication lock. */
record RecordWritePolicyInspection(RecordCompletenessQuery query, RecordSelectPlan plan) {
}
