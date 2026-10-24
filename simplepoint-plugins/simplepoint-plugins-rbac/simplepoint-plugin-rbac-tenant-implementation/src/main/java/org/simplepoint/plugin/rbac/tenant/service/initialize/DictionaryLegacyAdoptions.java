package org.simplepoint.plugin.rbac.tenant.service.initialize;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Command;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Preview;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Receipt;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local installer capability: preserves existing business rows while adopting their authority. */
@Service
public class DictionaryLegacyAdoptions {
  private final EntityManager entityManager;
  private final DictionaryCatalogWriteBoundary boundary;

  /** Reads only identity-owned storage; the installer supplies a validated registry receipt. */
  public DictionaryLegacyAdoptions(
      EntityManager entityManager, DictionaryCatalogWriteBoundary boundary) {
    this.entityManager = entityManager;
    this.boundary = boundary;
  }

  /** Captures complete native rows, including disabled/deleted state, without request filters. */
  @Transactional(readOnly = true)
  public Preview preview(DictionaryContribution declaration) {
    requireUnmanaged(declaration);
    return snapshot(declaration);
  }

  /**
   * Claims all declared codes and persists completion atomically without rewriting dictionary data.
   */
  @Transactional(rollbackFor = Exception.class)
  public Receipt adopt(Command command) {
    boundary.enter();
    var declaration = command.declaration();
    var previous = completed(declaration.moduleCode());
    var receipt =
        new Receipt(
            command.completion().operationId(),
            declaration.moduleCode(),
            command.expected().declarationFingerprint(),
            command.completion().sourceFingerprint(),
            command.expected().dataFingerprint(),
            command.expected().dictionaries(),
            command.expected().items());
    if (previous != null) {
      if (!previous.equals(receipt)) {
        throw failure("CONFLICT");
      }
      return previous;
    }
    requireUnmanaged(declaration);
    if (!snapshot(declaration).equals(command.expected())) {
      throw failure("PREVIEW_CHANGED");
    }
    for (var dictionary : declaration.dictionaries()) {
      query(
              "insert into simpoint_dictionary_registration_owners(dictionary_code,module_code)"
                  + " values (:code,:module)")
          .setParameter("code", dictionary.code())
          .setParameter("module", declaration.moduleCode())
          .executeUpdate();
    }
    query(
            """
insert into simpoint_dictionary_legacy_adoptions
  (module_code,operation_id,contribution_key,version,legacy_checksum,source_fingerprint,
   declaration_fingerprint,data_fingerprint,dictionary_count,item_count)
values (:module,:operation,:key,:version,:checksum,:source,:declaration,:data,:dictionaries,:items)
""")
        .setParameter("module", declaration.moduleCode())
        .setParameter("operation", receipt.operationId().toString())
        .setParameter("key", declaration.contributionKey())
        .setParameter("version", declaration.version())
        .setParameter("checksum", command.completion().checksum())
        .setParameter("source", receipt.sourceFingerprint())
        .setParameter("declaration", receipt.declarationFingerprint())
        .setParameter("data", receipt.dataFingerprint())
        .setParameter("dictionaries", command.expected().dictionaries())
        .setParameter("items", command.expected().items())
        .executeUpdate();
    return receipt;
  }

  /** Reads durable adoption evidence without granting installation or replaying dictionary writes. */
  @Transactional(readOnly = true)
  public java.util.Optional<Receipt> findCompletion(String module) {
    return java.util.Optional.ofNullable(completed(module));
  }

  private void requireUnmanaged(DictionaryContribution declaration) {
    if (!query(
                "select generation from simpoint_dictionary_registration_approvals where"
                    + " module_code=:module")
            .setParameter("module", declaration.moduleCode())
            .setMaxResults(1)
            .getResultList()
            .isEmpty()
        || completed(declaration.moduleCode()) != null) {
      throw failure("ALREADY_MANAGED");
    }
    for (var dictionary : declaration.dictionaries()) {
      if (!query(
              "select module_code from simpoint_dictionary_registration_owners where"
                  + " dictionary_code=:code")
          .setParameter("code", dictionary.code())
          .getResultList()
          .isEmpty()) {
        throw failure("OWNERSHIP_CONFLICT");
      }
    }
  }

  private Receipt completed(String module) {
    var rows =
        query(
                "select"
                    + " operation_id,declaration_fingerprint,source_fingerprint,data_fingerprint,dictionary_count,item_count"
                    + " from simpoint_dictionary_legacy_adoptions where module_code=:module")
            .setParameter("module", module)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = (Object[]) rows.getFirst();
    return new Receipt(
        UUID.fromString((String) row[0]),
        module,
        (String) row[1],
        (String) row[2],
        (String) row[3],
        ((Number) row[4]).intValue(),
        ((Number) row[5]).intValue());
  }

  private Preview snapshot(DictionaryContribution declaration) {
    var codes = declaration.dictionaries().stream().map(item -> item.code()).toList();
    List<?> dictionaries =
        query(
                "select id,code,name,description,sort,enabled,deleted_at,created_at,updated_at,"
                    + " created_by,updated_by,create_org_dept_id from simpoint_saas_dictionaries"
                    + " where code in (:codes) order by code,id")
            .setParameter("codes", codes)
            .setMaxResults(65)
            .getResultList();
    List<?> items =
        query(
                "select id,dictionary_code,item_value,name,i18n_key,description,sort,enabled,"
                    + " deleted_at,created_at,updated_at,created_by,updated_by,create_org_dept_id"
                    + " from simpoint_saas_dictionary_items where dictionary_code in (:codes)"
                    + " order by dictionary_code,item_value,id")
            .setParameter("codes", codes)
            .setMaxResults(8193)
            .getResultList();
    if (dictionaries.size() != codes.size() || items.size() > 8192) {
      throw failure("DATA_CONFLICT");
    }
    var found = new HashSet<String>();
    for (Object value : dictionaries) {
      if (!found.add((String) ((Object[]) value)[1])) {
        throw failure("DATA_CONFLICT");
      }
    }
    if (!found.equals(new HashSet<>(codes))) {
      throw failure("DATA_CONFLICT");
    }
    var values = new HashMap<String, HashSet<String>>();
    for (Object value : items) {
      Object[] row = (Object[]) value;
      if (!values
          .computeIfAbsent((String) row[1], ignored -> new HashSet<>())
          .add((String) row[2])) {
        throw failure("DATA_CONFLICT");
      }
    }
    for (var dictionary : declaration.dictionaries()) {
      var persisted = values.get(dictionary.code());
      for (var item : dictionary.items()) {
        if (persisted == null || !persisted.contains(item.value())) {
          throw failure("DATA_CONFLICT");
        }
      }
    }
    return new Preview(
        DictionaryFingerprint.of(declaration),
        fingerprint(dictionaries, items),
        dictionaries.size(),
        items.size());
  }

  private static String fingerprint(List<?> dictionaries, List<?> items) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      output.writeInt(1);
      for (var group : List.of(dictionaries, items)) {
        output.writeInt(group.size());
        for (Object value : group) {
          Object[] row = (Object[]) value;
          output.writeInt(row.length);
          for (Object column : row) {
            if (column == null) {
              output.writeInt(-1);
            } else {
              String normalized =
                  switch (column) {
                    case java.time.OffsetDateTime time -> time.toInstant().toString();
                    case java.time.ZonedDateTime time -> time.toInstant().toString();
                    case java.sql.Timestamp time -> time.toInstant().toString();
                    default -> column.toString();
                  };
              var text = normalized.getBytes(StandardCharsets.UTF_8);
              output.writeInt(text.length);
              output.write(text);
            }
          }
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Cannot fingerprint legacy dictionary data", impossible);
    }
  }

  private jakarta.persistence.Query query(String sql) {
    return entityManager
        .createNativeQuery(sql)
        .setFlushMode(FlushModeType.COMMIT)
        .setHint("jakarta.persistence.query.timeout", 10000);
  }

  private static IllegalStateException failure(String code) {
    return new IllegalStateException("DICTIONARY_ADOPTION_" + code);
  }
}
