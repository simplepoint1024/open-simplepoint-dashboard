package org.simplepoint.plugin.app.form.api.model.relation;

/** Browser capability only; neither physical identity nor target authorization is exposed. */
public record FormRuntimeReference(
    String relationId,
    boolean allowDrillThrough,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean contextual,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean searchable,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.List<ContextInput> contextInputs,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.List<Mapping> mappings,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean mappingWritable) {
  /** Keeps legacy runtime metadata absent when a reference has no mapping capabilities. */
  public FormRuntimeReference(
      String relationId,
      boolean allowDrillThrough,
      Boolean contextual,
      Boolean searchable,
      java.util.List<ContextInput> contextInputs) {
    this(
        relationId,
        allowDrillThrough,
        contextual,
        searchable,
        contextInputs,
        java.util.List.of(),
        null);
  }

  /** Preserves optional metadata absence in legacy runtime descriptors. */
  public FormRuntimeReference(String relationId, boolean allowDrillThrough) {
    this(relationId, allowDrillThrough, null, null, java.util.List.of());
  }

  /** Copies the bounded list of declared browser inputs. */
  public FormRuntimeReference {
    relationId = FormRelationIdentity.require(relationId);
    contextInputs =
        contextInputs == null ? java.util.List.of() : java.util.List.copyOf(contextInputs);
    mappings = mappings == null ? java.util.List.of() : java.util.List.copyOf(mappings);
    if (mappings.size() > 32
        || mappings.stream().map(Mapping::mappingId).distinct().count() != mappings.size()
        || mappings.stream().map(Mapping::destinationFieldId).distinct().count()
            != mappings.size()) {
      throw new IllegalArgumentException("Invalid runtime mapping capabilities");
    }
    if (contextInputs.size() > 16) {
      throw new IllegalArgumentException("Reference context input budget exceeded");
    }
  }

  /** Local capabilities only; target field IDs and conversion details remain server-owned. */
  public record Mapping(
      String mappingId,
      String destinationFieldId,
      FormReferenceMapping.Mode mode,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          FormReferenceMapping.NullPolicy nullPolicy) {
    /** Preserves runtime descriptors written before null-policy projection. */
    public Mapping(String mappingId, String destinationFieldId, FormReferenceMapping.Mode mode) {
      this(mappingId, destinationFieldId, mode, null);
    }

    /** Uses stable field identities rather than language-dependent labels or physical columns. */
    public Mapping {
      mappingId = FormRelationIdentity.require(mappingId);
      destinationFieldId = FormRelationIdentity.require(destinationFieldId);
      java.util.Objects.requireNonNull(mode, "mode");
    }
  }

  /** Only readable source inputs; no target fields, physical names or organization values. */
  public record ContextInput(
      FormReferenceContextFilter.Source source,
      String fieldId,
      org.simplepoint.plugin.app.form.api.model.FormFieldType type) {
    /** Excludes organization context from browser-supplied values. */
    public ContextInput {
      if (source == null
          || source == FormReferenceContextFilter.Source.ORGANIZATION
          || type == null) {
        throw new IllegalArgumentException("Invalid browser candidate input");
      }
      fieldId = FormRelationIdentity.require(fieldId);
    }
  }
}
