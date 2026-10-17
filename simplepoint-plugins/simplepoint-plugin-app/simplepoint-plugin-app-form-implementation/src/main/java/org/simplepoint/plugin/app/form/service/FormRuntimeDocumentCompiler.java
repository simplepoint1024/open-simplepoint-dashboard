package org.simplepoint.plugin.app.form.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.springframework.stereotype.Component;

/** Compiles design snapshots to a storage-free runtime document using effective field policy. */
@Component
public class FormRuntimeDocumentCompiler {

  /** Removes hidden field metadata and forces non-editable components to read-only. */
  public ApplicationFormRuntimeDocument compile(final PublishedApplicationForm form) {
    Map<String, String> permissions = fieldPermissions(form.resourceCode());
    Map<String, FieldAccess> accessByField = new HashMap<>();
    form.document().fields().forEach(field -> accessByField.put(
        field.fieldId(),
        access(field, RecordPermissionModel.access(permissions, form.physicalModelId(), field.fieldId(), field.storageCode()))
    ));
    List<ApplicationFormRuntimeFieldDefinition> fields = form.document().fields().stream()
        .filter(field -> accessByField.get(field.fieldId()) != FieldAccess.HIDDEN)
        .map(field -> runtimeField(field, accessByField.get(field.fieldId())))
        .toList();
    List<FormSceneDefinition> scenes = form.document().scenes().stream()
        .map(scene -> runtimeScene(scene, accessByField))
        .toList();
    Set<String> componentIds = new HashSet<>();
    scenes.forEach(scene -> collectComponentIds(scene.root(), componentIds));
    Set<String> readableFields = accessByField.entrySet().stream()
        .filter(entry -> entry.getValue() == FieldAccess.EDITABLE || entry.getValue() == FieldAccess.READ_ONLY)
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    List<FormRuleDefinition> rules = form.document().rules().stream()
        .filter(rule -> componentIds.contains(rule.targetComponentId()))
        .filter(rule -> referencesOnly(rule.condition(), readableFields))
        .toList();
    return new ApplicationFormRuntimeDocument(
        form.document().contractVersion(),
        fields,
        scenes,
        rules,
        form.document().details().stream().map(detail -> {
          var binding = form.detailBindings().stream().filter(item -> item.detailId().equals(detail.detailId()))
              .findFirst().orElseThrow(() -> new IllegalStateException("Published detail binding is missing"));
          return new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeDetailDefinition(
              detail.detailId(), detail.label(), detail.fields().stream()
                  .filter(field -> access(field, RecordPermissionModel.access(permissions, binding.physicalModelId(), field.fieldId(), field.storageCode())) != FieldAccess.HIDDEN)
                  .map(field -> runtimeField(field, access(field, RecordPermissionModel.access(permissions, binding.physicalModelId(), field.fieldId(), field.storageCode())))).toList(),
              detail.fields().stream().filter(field -> access(field, RecordPermissionModel.access(permissions, binding.physicalModelId(), field.fieldId(), field.storageCode())) != FieldAccess.EDITABLE)
                  .map(FormFieldDefinition::fieldId).toList());
        }).toList(),
        form.document().constraints().stream().filter(rule ->
            (rule.fieldId() == null || readableFields.contains(rule.fieldId()))
                && (rule.otherFieldId() == null || readableFields.contains(rule.otherFieldId()))
                && (rule.detailFieldId() == null || form.document().details().stream()
                    .filter(detail -> detail.detailId().equals(rule.detailId()))
                    .flatMap(detail -> detail.fields().stream())
                    .filter(field -> field.fieldId().equals(rule.detailFieldId())).anyMatch(field -> {
                      var binding = form.detailBindings().stream()
                          .filter(item -> item.detailId().equals(rule.detailId())).findFirst().orElseThrow();
                      FieldAccess visible = access(field, RecordPermissionModel.access(permissions, binding.physicalModelId(), field.fieldId(), field.storageCode()));
                      return visible == FieldAccess.EDITABLE || visible == FieldAccess.READ_ONLY;
                    }))).toList(), form.document().presentation() == null ? null
            : new org.simplepoint.plugin.app.form.api.model.FormPresentation(
                form.document().presentation().listFieldOrder().stream().filter(id -> accessByField.get(id) != FieldAccess.HIDDEN).toList(),
                form.document().presentation().labelWidth())
    );
  }

  private static ApplicationFormRuntimeFieldDefinition runtimeField(
      final FormFieldDefinition field,
      final FieldAccess access
  ) {
    return new ApplicationFormRuntimeFieldDefinition(
        field.fieldId(),
        field.label(),
        field.type(),
        field.submitRequired(),
        field.length(),
        field.precision(),
        field.scale(),
        access == FieldAccess.MASKED ? null : field.defaultValue(),
        access == FieldAccess.MASKED ? List.of() : field.choices(),
        field.writePolicy(),
        access == FieldAccess.MASKED ? null : field.validation(), field.access()
    );
  }

  private static FormSceneDefinition runtimeScene(
      final FormSceneDefinition scene,
      final Map<String, FieldAccess> accessByField
  ) {
    return new FormSceneDefinition(
        scene.sceneId(),
        scene.code(),
        scene.name(),
        scene.type(),
        scene.width(),
        runtimeComponent(scene.root(), accessByField)
    );
  }

  private static FormComponentDefinition runtimeComponent(
      final FormComponentDefinition component,
      final Map<String, FieldAccess> accessByField
  ) {
    List<FormComponentDefinition> children = component.children().stream()
        .filter(child -> visible(child, accessByField))
        .map(child -> runtimeComponent(child, accessByField))
        .toList();
    FieldAccess access = component.fieldId() == null
        ? FieldAccess.EDITABLE : accessByField.get(component.fieldId());
    return new FormComponentDefinition(
        component.componentId(),
        component.componentType(),
        component.componentVersion(),
        component.fieldId(),
        component.columnSpan(),
        component.label(),
        component.description(),
        component.placeholder(),
        component.targetSceneId(),
        component.readOnly() || access != FieldAccess.EDITABLE,
        component.properties(),
        children
    );
  }

  private static boolean visible(
      final FormComponentDefinition component,
      final Map<String, FieldAccess> accessByField
  ) {
    return component.fieldId() == null
        || accessByField.get(component.fieldId()) != FieldAccess.HIDDEN;
  }

  private static void collectComponentIds(
      final FormComponentDefinition component,
      final Set<String> componentIds
  ) {
    componentIds.add(component.componentId());
    component.children().forEach(child -> collectComponentIds(child, componentIds));
  }

  private static boolean referencesOnly(
      final FormCondition condition,
      final Set<String> visibleFields
  ) {
    if (condition.fieldId() != null && !visibleFields.contains(condition.fieldId())) {
      return false;
    }
    return condition.children().stream()
        .allMatch(child -> referencesOnly(child, visibleFields));
  }

  private static FieldAccess access(
      final FormFieldDefinition field,
      final String permission
  ) {
    if ("HIDDEN".equals(permission)) {
      return FieldAccess.HIDDEN;
    }
    if ("MASKED".equals(permission)) {
      return FieldAccess.MASKED;
    }
    if (field.storageMode() == FormStorageMode.COMPUTED) {
      return FieldAccess.READ_ONLY;
    }
    if (permission == null || "EDITABLE".equals(permission)) {
      return FieldAccess.EDITABLE;
    }
    return FieldAccess.READ_ONLY;
  }

  private static Map<String, String> fieldPermissions(final String resourceCode) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null || context.getResourceFieldPermissions() == null) {
      return Map.of();
    }
    Map<String, String> permissions = context.getResourceFieldPermissions().get(resourceCode);
    return permissions == null ? Map.of() : permissions;
  }

  private enum FieldAccess {
    EDITABLE,
    READ_ONLY,
    HIDDEN,
    MASKED
  }
}
