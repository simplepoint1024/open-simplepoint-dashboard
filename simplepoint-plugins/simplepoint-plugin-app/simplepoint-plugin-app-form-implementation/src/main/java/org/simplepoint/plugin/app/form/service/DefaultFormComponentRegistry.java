package org.simplepoint.plugin.app.form.service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormComponentPropertyDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;
import org.simplepoint.plugin.app.form.api.service.FormComponentRegistry;

/** Built-in v1 form components; applications may replace this bean with a composed registry. */
public class DefaultFormComponentRegistry implements FormComponentRegistry {

  private static final Set<FormFieldType> ANY_FIELD =
      Set.copyOf(EnumSet.allOf(FormFieldType.class));

  private static final List<FormComponentDescriptor> BUILT_INS =
      List.of(
          container("layout.form"),
          container("layout.grid"),
          container("layout.group"),
          container("layout.tabs"),
          display("display.text"),
          field("field.text", FormFieldType.STRING),
          field("field.textarea", FormFieldType.STRING, FormFieldType.TEXT),
          field("field.json", FormFieldType.JSON),
          field("field.number", FormFieldType.INT32, FormFieldType.INT64, FormFieldType.DECIMAL),
          field("field.switch", FormFieldType.BOOLEAN),
          field("field.select", ANY_FIELD),
          field("field.date", FormFieldType.DATE),
          field("field.time", FormFieldType.TIME),
          field("field.datetime", FormFieldType.DATETIME),
          field("field.user", FormFieldType.STRING),
          field("field.role", FormFieldType.STRING),
          field("field.organization", FormFieldType.STRING),
          field("field.tenant", FormFieldType.STRING),
          field("field.attachment", FormFieldType.JSON),
          action("action.save"),
          action("action.submit"),
          action("action.reset"),
          action("action.open-dialog"),
          action("action.open-drawer"),
          action("action.close"),
          action("action.apply"));

  private final List<FormComponentDescriptor> descriptors;
  private final Map<String, FormComponentContributor> validators = new LinkedHashMap<>();

  /** Creates a registry containing only platform built-ins. */
  public DefaultFormComponentRegistry() {
    this(List.of());
  }

  /** Composes platform built-ins and trusted contributors with duplicate rejection. */
  public DefaultFormComponentRegistry(final List<FormComponentContributor> contributors) {
    Map<String, FormComponentDescriptor> indexed = new LinkedHashMap<>();
    BUILT_INS.forEach(descriptor -> add(indexed, descriptor));
    var extensions = new java.util.ArrayList<FormComponentContributor>();
    extensions.add(new org.simplepoint.plugin.app.form.component.MatrixComponentContributor());
    extensions.add(new org.simplepoint.plugin.app.form.component.DetailComponentContributor());
    extensions.add(new org.simplepoint.plugin.app.form.component.StandardComponentsContributor());
    extensions.addAll(contributors);
    extensions.forEach(
        contributor ->
            contributor
                .descriptors()
                .forEach(
                    descriptor -> {
                      add(indexed, descriptor);
                      validators.put(descriptor.type() + "@" + descriptor.version(), contributor);
                    }));
    descriptors = List.copyOf(indexed.values());
  }

  @Override
  public List<FormComponentDescriptor> descriptors() {
    return descriptors;
  }

  @Override
  public void validate(
      final org.simplepoint.plugin.app.form.api.model.FormComponentDefinition component) {
    var contributor =
        validators.get(component.componentType() + "@" + component.componentVersion());
    if (contributor != null) {
      contributor.validate(component);
    }
  }

  private static FormComponentDescriptor container(final String type) {
    return descriptor(type, true, false, Set.of());
  }

  private static FormComponentDescriptor display(final String type) {
    return descriptor(type, false, false, Set.of());
  }

  private static FormComponentDescriptor action(final String type) {
    return descriptor(type, false, false, Set.of());
  }

  private static FormComponentDescriptor field(
      final String type, final FormFieldType... fieldTypes) {
    return descriptor(type, false, true, Set.of(fieldTypes));
  }

  private static FormComponentDescriptor field(
      final String type, final Set<FormFieldType> fieldTypes) {
    return descriptor(type, false, true, fieldTypes);
  }

  private static FormComponentDescriptor descriptor(
      final String type,
      final boolean container,
      final boolean fieldRequired,
      final Set<FormFieldType> fieldTypes) {
    return new FormComponentDescriptor(
        type,
        ApplicationFormApiVersion.RENDERER_V1,
        container,
        fieldRequired,
        fieldTypes,
        Map.of(),
        Set.of(),
        Set.of());
  }

  private static void add(
      final Map<String, FormComponentDescriptor> indexed,
      final FormComponentDescriptor descriptor) {
    validateDescriptor(descriptor);
    String key = descriptor.type() + "@" + descriptor.version();
    if (indexed.putIfAbsent(key, descriptor) != null) {
      throw new IllegalStateException("Duplicate form component contract: " + key);
    }
  }

  private static void validateDescriptor(final FormComponentDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "Form component descriptor is required");
    if (descriptor.type() == null
        || descriptor.type().isBlank()
        || descriptor.version() == null
        || descriptor.version().isBlank()
        || descriptor.fieldRequired() && descriptor.supportedFieldTypes().isEmpty()
        || !descriptor.fieldRequired() && !descriptor.supportedFieldTypes().isEmpty()) {
      throw new IllegalStateException("Invalid form component descriptor");
    }
    descriptor.propertySchema().forEach((name, property) -> validateProperty(name, property));
  }

  private static void validateProperty(
      final String name, final FormComponentPropertyDescriptor property) {
    if (property == null
        || !name.equals(property.name())
        || property.acceptedKinds().isEmpty()
        || property.defaultValue() != null
            && !property.acceptedKinds().contains(property.defaultValue().kind())) {
      throw new IllegalStateException("Invalid form component property descriptor: " + name);
    }
  }
}
