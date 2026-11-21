package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlWriteOwnership.unavailable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteWritePlan;
import org.simplepoint.data.ownership.PhysicalIdentifierRules;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly;

/**
 * Checks original table spellings against the actual prepared adapter bindings before execution.
 */
final class FederationCalciteObjectBindings {
  private FederationCalciteObjectBindings() {}

  static void verify(
      FederationDmlPlan original,
      CalciteWritePlan prepared,
      FederationCalciteCatalogAssembly assembly,
      String sourceCode)
      throws SQLException {
    var bound = new ArrayList<Bound>();
    var contexts = new IdentityHashMap<Connection, Rules>();
    for (var table : prepared.tables()) {
      Connection connection = assembly.writeConnection(table.dataSource());
      var rules = contexts.get(connection);
      if (rules == null) {
        rules = new Rules(PhysicalIdentifierRules.on(connection));
        contexts.put(connection, rules);
      }
      validatePath(table, rules, sourceCode);
      bound.add(new Bound(table, rules));
    }
    for (var reference : original.objects()) {
      boolean found = false;
      for (var candidate : bound) {
        if (matches(reference, candidate, false)) {
          found = true;
          break;
        }
      }
      if (!found) {
        throw unavailable(null);
      }
    }
    for (var candidate : bound) {
      boolean found = false;
      for (var reference : original.objects()) {
        // UPDATE/DELETE introduce a scan of their target, even without an explicit SQL read.
        if (matches(reference, candidate, true)) {
          found = true;
          break;
        }
      }
      if (!found) {
        throw unavailable(null);
      }
    }
  }

  private static boolean matches(
      FederationDmlPlan.ObjectReference reference, Bound bound, boolean allowTargetScan)
      throws SQLException {
    boolean originalTarget = reference.role() == FederationDmlPlan.Role.TARGET;
    boolean boundTarget = bound.table().role() == CalciteWritePlan.Role.TARGET;
    if (originalTarget != boundTarget && !(allowTargetScan && originalTarget && !boundTarget)) {
      return false;
    }
    var names = reference.names();
    var path = bound.table().logicalPath();
    if (names.size() > path.size()) {
      return false;
    }
    int offset = path.size() - names.size();
    for (int index = 0; index < names.size(); index++) {
      String actual = path.get(offset + index);
      if (offset + index == 0) {
        // The platform's datasource code is a routing identity, not a native database name.
        if (!names.get(index).equalsIgnoreCase(actual)) {
          return false;
        }
      } else if (!bound
          .rules()
          .canonical(names.get(index), reference.quoted().get(index))
          .equals(bound.rules().canonical(actual, true))) {
        return false;
      }
    }
    return true;
  }

  private static void validatePath(CalciteWritePlan.TableUse table, Rules rules, String sourceCode)
      throws SQLException {
    List<String> path = table.logicalPath();
    if (path.size() < 2
        || path.size() > 4
        || !path.getFirst().equalsIgnoreCase(sourceCode)
        || !same(rules, path.getLast(), table.table())) {
      throw unavailable(null);
    }
    if (path.size() == 3) {
      if (!same(rules, path.get(1), table.catalog())
          && !(rules.nativeRules.postgres() && same(rules, path.get(1), table.schema()))) {
        throw unavailable(null);
      }
    } else if (path.size() == 4
        && (!rules.nativeRules.postgres()
            || !same(rules, path.get(1), table.catalog())
            || !same(rules, path.get(2), table.schema()))) {
      throw unavailable(null);
    }
  }

  private static boolean same(Rules rules, String left, String right) throws SQLException {
    return right != null && rules.canonical(left, true).equals(rules.canonical(right, true));
  }

  private record Bound(CalciteWritePlan.TableUse table, Rules rules) {}

  /** Statement-local cache: native parsing grows with distinct names, not matching pairs. */
  private static final class Rules {
    private final PhysicalIdentifierRules nativeRules;
    private final HashMap<Name, String> canonical = new HashMap<>();

    private Rules(PhysicalIdentifierRules nativeRules) {
      this.nativeRules = nativeRules;
    }

    private String canonical(String name, boolean quoted) throws SQLException {
      var key = new Name(name, quoted);
      String value = canonical.get(key);
      if (value == null) {
        value = nativeRules.canonical(name, quoted);
        canonical.put(key, value);
      }
      return value;
    }
  }

  private record Name(String value, boolean quoted) {}
}
