package org.simplepoint.plugin.storage.api.model;

/** Minimal source-owned reference metadata; never exposes provider credentials or object keys. */
public record ObjectStorageSourceMetadata(String id, String fileName, long contentLength, String createdBy) {}
