package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** One ordered mutation in a single-model physical transaction. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RecordCreateMutation.class, name = "CREATE"),
    @JsonSubTypes.Type(value = RecordUpdateMutation.class, name = "UPDATE"),
    @JsonSubTypes.Type(value = RecordDeleteMutation.class, name = "DELETE")
})
public sealed interface RecordMutation
    permits RecordCreateMutation, RecordDeleteMutation, RecordUpdateMutation {

  /** Client correlation value returned unchanged without entering SQL. */
  String reference();
}
