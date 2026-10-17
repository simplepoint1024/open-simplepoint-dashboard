package org.simplepoint.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FormulaSyntaxContractTest {
  @Test
  void sharesCanonicalFixturesWithBrowserParser() throws Exception {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                getClass().getResourceAsStream("/formula-syntax.tsv"), StandardCharsets.UTF_8))) {
      for (String line : reader.lines().toList()) {
        String[] parts = line.split("\t", -1);
        var tree = FormulaSyntax.parse(parts[0]);
        assertEquals(parts[1], FormulaSyntax.print(tree), parts[0]);
        assertEquals(tree, FormulaSyntax.parse(parts[1]), parts[0]);
      }
    }
  }
}
