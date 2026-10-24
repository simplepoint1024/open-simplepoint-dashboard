package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.permission.PermissionModelDescriptor;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Compiles permission discovery from the immutable published version, including owned detail models. */
@Service
public class FormPermissionDescriptorService {
  private static final List<String> EQUALITY = List.of("equals", "not:equals", "in", "not:in", "is:null", "is:not:null");
  private static final List<String> COMPARABLE = List.of("equals", "not:equals", "in", "not:in", "is:null", "is:not:null",
      "between", "not:between", "than:greater", "than:less", "than:equal:greater", "than:equal:less");
  private final EntityManager entityManager;
  private final FormDocumentCodec codec;

  /** Creates the compiler over APP-owned immutable version metadata. */
  public FormPermissionDescriptorService(EntityManager entityManager, FormDocumentCodec codec) {
    this.entityManager = entityManager;
    this.codec = codec;
  }

  /** Describes exactly the main and detail models of one bound publication. */
  @Transactional(readOnly = true)
  public PermissionPageDescriptor describe(BindingWork work) {
    var version = entityManager.createQuery("from ApplicationFormVersionEntity v where v.tenantId=:tenant "
        + "and v.formId=:form and v.versionNumber=:version", ApplicationFormVersionEntity.class)
        .setParameter("tenant", work.tenantId()).setParameter("form", work.formId())
        .setParameter("version", work.formVersion()).getSingleResult();
    var document = codec.decode(version.getDocumentJson());
    var bindings = codec.decodeDetailBindings(version.getDetailBindingsJson());
    var models = new ArrayList<PermissionModelDescriptor>();
    models.add(model(work.physicalModelId(), document.fields(), ""));
    for (var detail : document.details()) {
      var binding = bindings.stream().filter(item -> item.detailId().equals(detail.detailId())).findFirst().orElseThrow();
      models.add(model(binding.physicalModelId(), detail.fields(), detail.label() + " / ", detail.labelLocalization()));
    }
    return new PermissionPageDescriptor(models);
  }

  static PermissionModelDescriptor model(String model, List<FormFieldDefinition> fields, String prefix) {
    return model(model, fields, prefix, null);
  }

  private static PermissionModelDescriptor model(String model, List<FormFieldDefinition> fields, String prefix,
      org.simplepoint.api.locale.LocalizedText prefixLocalization) {
    Map<String, PermissionModelDescriptor.Field> properties = new LinkedHashMap<>();
    for (var base : RecordSystemField.values()) {
      boolean temporal = base.type() == org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.DATETIME;
      properties.put(base.storageCode(), new PermissionModelDescriptor.Field(
          temporal ? "java.time.Instant" : "java.lang.String", prefix + base.title(),
          temporal ? COMPARABLE : EQUALITY, List.of(), true));
    }
    for (var field : fields) {
      if (!field.persistent()) {
        continue;
      }
      String type = switch (field.type()) {
        case BOOLEAN -> "java.lang.Boolean";
        case INT32 -> "java.lang.Integer";
        case INT64 -> "java.lang.Long";
        case DECIMAL -> "java.math.BigDecimal";
        case DATE -> "java.time.LocalDate";
        case TIME -> "java.time.LocalTime";
        case DATETIME -> "java.time.Instant";
        default -> "java.lang.String";
      };
      List<String> operators = switch (field.type()) {
        case JSON -> List.of("is:null", "is:not:null");
        case BOOLEAN, UUID -> EQUALITY;
        case STRING, TEXT -> java.util.stream.Stream.concat(COMPARABLE.stream(), java.util.stream.Stream.of("like", "not:like")).toList();
        default -> COMPARABLE;
      };
      properties.put(RecordPermissionModel.field(field.fieldId()),
          new PermissionModelDescriptor.Field(type, prefix + field.label(), operators, List.of(), field.storageMode() == FormStorageMode.COMPUTED, localizedTitle(prefix, prefixLocalization, field)));
    }
    return new PermissionModelDescriptor(RecordPermissionModel.key(model), true, true,
        "created_by", "created_org_dept_id", properties);
  }
  private static org.simplepoint.api.locale.LocalizedText localizedTitle(String prefix,
      org.simplepoint.api.locale.LocalizedText parent, FormFieldDefinition field) {
    var value = field.labelLocalization();
    if (value == null && parent == null) return null;
    var languages = new java.util.TreeSet<String>();
    if (value != null) languages.addAll(value.translations().keySet());
    if (parent != null) languages.addAll(parent.translations().keySet());
    var translations = new LinkedHashMap<String, String>();
    for (var language : languages) {
      String parentLabel = org.simplepoint.api.locale.LocalizedTexts.override(parent, language);
      String fieldLabel = org.simplepoint.api.locale.LocalizedTexts.override(value, language);
      translations.put(language, (parentLabel == null ? prefix : parentLabel + " / ") + (fieldLabel == null ? field.label() : fieldLabel));
    }
    return new org.simplepoint.api.locale.LocalizedText(prefix + field.label(), value == null ? parent.defaultLocale() : value.defaultLocale(), translations);
  }

}
