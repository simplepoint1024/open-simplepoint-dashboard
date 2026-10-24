package org.simplepoint.installation.host;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;

/** Binds reviewed actions to actual database namespaces, without storing URLs or credentials. */
final class InstallationStorage {
  private InstallationStorage() {}

  static String fingerprint(Map<String, DataSource> sources) throws Exception {
    var values = new ArrayList<String>();
    for (var entry : new TreeMap<>(sources).entrySet()) {
      try (var connection = entry.getValue().getConnection()) {
        String jdbc = connection.getMetaData().getURL();
        if (!jdbc.startsWith("jdbc:postgresql://")) {
          throw new IllegalArgumentException("Reviewed legacy installation requires PostgreSQL");
        }
        URI endpoint = URI.create(jdbc.substring("jdbc:".length()));
        if (endpoint.getHost() == null) {
          throw new IllegalArgumentException("Installation database endpoint must have one explicit host");
        }
        values.add(entry.getKey());
        values.add(endpoint.getHost());
        values.add(Integer.toString(endpoint.getPort() == -1 ? 5432 : endpoint.getPort()));
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("select current_database(),current_schema()")) {
          rows.next();
          values.add(rows.getString(1));
          values.add(rows.getString(2));
        }
      }
    }
    return InstallationFingerprint.of(values.toArray(String[]::new));
  }

  static List<InstallationAction> bind(List<InstallationAction> actions, String storage) {
    return actions.stream().map(action -> scoped(action, storage)).toList();
  }

  private static InstallationAction scoped(InstallationAction action, String storage) {
    var original = action.definition();
    var definition = new InstallationPlan.Step(original.id(), original.kind(), original.version(),
        InstallationFingerprint.of(original.inputFingerprint(), storage), original.retry());
    return new InstallationAction() {
      @Override
      public InstallationPlan.Step definition() {
        return definition;
      }

      @Override
      public String apply() throws Exception {
        return InstallationFingerprint.of(action.apply(), storage);
      }

      @Override
      public Optional<String> completedReceipt() {
        return action.completedReceipt().map(receipt -> InstallationFingerprint.of(receipt, storage));
      }
    };
  }
}
