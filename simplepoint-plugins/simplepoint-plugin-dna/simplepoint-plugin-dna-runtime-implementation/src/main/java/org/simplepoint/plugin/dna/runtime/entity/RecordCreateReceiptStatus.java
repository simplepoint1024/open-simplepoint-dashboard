package org.simplepoint.plugin.dna.runtime.entity;

/** Durable state of an idempotent single-record create request. */
public enum RecordCreateReceiptStatus {
  PENDING,
  SUCCEEDED
}
