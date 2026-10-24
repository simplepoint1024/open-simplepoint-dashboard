package org.simplepoint.plugin.rbac.tenant.service.impl;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.util.*;
import org.simplepoint.api.locale.LocalizedTexts;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceErrorCode;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceException;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand;

/** Pure bounded graph validation, shared by installation-independent navigation execution. */
final class ManagedNavigationPlan {
  final ManagedNavigationCommand command;
  final List<ManagedNavigationCommand.Directory> ordered;
  final String digest;

  ManagedNavigationPlan(ManagedNavigationCommand command) {
    if (command == null
        || command.revision() < 1
        || command.directories().isEmpty()
        || command.directories().size() > 100
        || command.pages().size() > 500) throw invalid();
    this.command = command;
    var directories = new LinkedHashMap<String, ManagedNavigationCommand.Directory>();
    for (var directory : command.directories()) {
      if (directory.managedKey() == null
          || directories.put(directory.managedKey(), directory) != null) throw invalid();
      label(directory.name(), directory.nameLocalization());
    }
    var root = directories.get(command.rootKey());
    if (root == null || root.parentKey() != null) throw invalid();
    ordered = new ArrayList<>();
    var visiting = new HashSet<String>();
    var depths = new HashMap<String, Integer>();
    for (var directory : directories.values()) visit(directory, directories, visiting, depths);
    var pages = new HashSet<String>();
    for (var page : command.pages()) {
      if (page.managedKey() == null
          || !pages.add(page.managedKey())
          || directories.containsKey(page.managedKey())
          || !directories.containsKey(page.parentKey())) throw invalid();
      label(page.name(), page.nameLocalization());
    }
    try {
      var mapper =
          JsonMapper.builder()
              .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
              .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .build();
      // Stable order makes independently serialized retries equivalent.
      var canonical =
          new ManagedNavigationCommand(
              command.tenantId(),
              command.rootKey(),
              command.revision(),
              command.tenantParentCode(),
              command.directories().stream()
                  .sorted(Comparator.comparing(ManagedNavigationCommand.Directory::managedKey))
                  .toList(),
              command.pages().stream()
                  .sorted(Comparator.comparing(ManagedNavigationCommand.Page::managedKey))
                  .toList());
      digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot encode navigation command", failure);
    }
  }

  private int visit(
      ManagedNavigationCommand.Directory node,
      Map<String, ManagedNavigationCommand.Directory> nodes,
      Set<String> visiting,
      Map<String, Integer> depths) {
    if (depths.containsKey(node.managedKey())) return depths.get(node.managedKey());
    if (!visiting.add(node.managedKey())) throw invalid();
    int depth = 1;
    if (!node.managedKey().equals(command.rootKey())) {
      var parent = nodes.get(node.parentKey());
      if (parent == null) throw invalid();
      depth += visit(parent, nodes, visiting, depths);
    }
    if (depth > 8) throw invalid();
    visiting.remove(node.managedKey());
    depths.put(node.managedKey(), depth);
    ordered.add(node);
    return depth;
  }

  static void label(String name, org.simplepoint.api.locale.LocalizedText value) {
    if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100)
      throw invalid();
    if (value != null) {
      try {
        LocalizedTexts.validate(value, 100, 32);
      } catch (IllegalArgumentException failure) {
        throw invalid();
      }
      if (!name.equals(value.defaultValue())) throw invalid();
    }
  }

  static ManagedTenantResourceException invalid() {
    return new ManagedTenantResourceException(
        ManagedTenantResourceErrorCode.INVALID_REQUEST,
        "Invalid managed navigation graph or localized name");
  }
}
