package org.simplepoint.plugin.dna.runtime.service.support;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalTableNamingStrategy;

/** Generates a 30-character portable identifier from a UUID. */
public class DefaultPhysicalTableNamingStrategy implements PhysicalTableNamingStrategy {

  private static final char[] BASE32 = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
  private static final int ENCODED_UUID_LENGTH = 26;

  @Override
  public String tableName(final String physicalModelId) {
    try {
      UUID uuid = UUID.fromString(physicalModelId);
      ByteBuffer bytes = ByteBuffer.allocate(16)
          .putLong(uuid.getMostSignificantBits())
          .putLong(uuid.getLeastSignificantBits());
      return "spf_" + encode(bytes.array()).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "Physical model id must be a UUID",
          exception
      );
    }
  }

  private static String encode(final byte[] bytes) {
    BigInteger value = new BigInteger(1, bytes);
    char[] encoded = new char[ENCODED_UUID_LENGTH];
    for (int index = encoded.length - 1; index >= 0; index--) {
      encoded[index] = BASE32[value.and(BigInteger.valueOf(31)).intValue()];
      value = value.shiftRight(5);
    }
    return new String(encoded);
  }
}
