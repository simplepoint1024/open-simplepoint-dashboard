/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.s3.support;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteRequest;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteResult;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible driver covering MinIO, AWS S3, Aliyun OSS, Tencent COS, Qiniu Kodo,
 * and Ceph RGW.
 */
public class S3CompatibleObjectStorageDriver implements ObjectStorageDriver {

  @Override
  public boolean supports(final ObjectStoragePlatformType type) {
    return type == ObjectStoragePlatformType.MINIO
        || type == ObjectStoragePlatformType.S3
        || type == ObjectStoragePlatformType.ALIYUN_OSS
        || type == ObjectStoragePlatformType.TENCENT_COS
        || type == ObjectStoragePlatformType.QINIU_KODO
        || type == ObjectStoragePlatformType.CEPH;
  }

  @Override
  public void testConnection(final ObjectStorageProperties.ProviderProperties properties) {
    try (S3Client client = buildClient(properties)) {
      client.headBucket(HeadBucketRequest.builder()
          .bucket(require(properties.getBucket(), "对象存储 bucket 未配置"))
          .build());
    } catch (S3Exception ex) {
      String message = ex.awsErrorDetails() == null
          ? ex.getMessage()
          : ex.awsErrorDetails().errorMessage();
      throw new IllegalStateException("对象存储连接失败: " + message, ex);
    }
  }

  @Override
  public ObjectStorageWriteResult write(
      final ObjectStorageProperties.ProviderProperties properties,
      final ObjectStorageWriteRequest request
  ) {
    try (S3Client client = buildClient(properties)) {
      PutObjectRequest putObjectRequest = PutObjectRequest.builder()
          .bucket(request.getBucket())
          .key(request.getObjectKey())
          .contentType(request.getContentType())
          .metadata(normalizeMetadata(request.getMetadata()))
          .build();
      PutObjectResponse response = client.putObject(
          putObjectRequest,
          RequestBody.fromInputStream(request.getInputStream(), request.getContentLength())
      );
      return new ObjectStorageWriteResult(
          request.getBucket(),
          request.getObjectKey(),
          response.eTag(),
          buildAccessUrl(properties, request.getBucket(), request.getObjectKey())
      );
    } catch (Exception ex) {
      throw new IllegalStateException("对象存储上传失败: " + ex.getMessage(), ex);
    }
  }

  @Override
  public ObjectStorageReadResult read(
      final ObjectStorageProperties.ProviderProperties properties,
      final String bucket,
      final String objectKey,
      final String fileName
  ) {
    S3Client client = buildClient(properties);
    try {
      ResponseInputStream<GetObjectResponse> stream = client.getObject(
          GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
      );
      GetObjectResponse response = stream.response();
      return new ObjectStorageReadResult(
          new ManagedS3InputStream(stream, client),
          response.contentType() == null ? "application/octet-stream" : response.contentType(),
          response.contentLength(),
          fileName
      );
    } catch (NoSuchKeyException ex) {
      client.close();
      throw new NoSuchElementException("对象不存在: " + objectKey);
    } catch (S3Exception ex) {
      client.close();
      if (ex.statusCode() == 404) {
        throw new NoSuchElementException("对象不存在: " + objectKey);
      }
      throw new IllegalStateException("对象存储下载失败: " + ex.awsErrorDetails().errorMessage(), ex);
    } catch (RuntimeException ex) {
      client.close();
      throw new IllegalStateException("对象存储下载失败: " + ex.getMessage(), ex);
    }
  }

  @Override
  public void delete(
      final ObjectStorageProperties.ProviderProperties properties,
      final String bucket,
      final String objectKey
  ) {
    try (S3Client client = buildClient(properties)) {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) {
        return;
      }
      throw new IllegalStateException("对象存储删除失败: " + ex.awsErrorDetails().errorMessage(), ex);
    }
  }

  private S3Client buildClient(final ObjectStorageProperties.ProviderProperties properties) {
    String accessKey = require(properties.getAccessKey(), "对象存储 accessKey 未配置");
    String secretKey = require(properties.getSecretKey(), "对象存储 secretKey 未配置");
    String bucket = require(properties.getBucket(), "对象存储 bucket 未配置");
    String region = trimToNull(properties.getRegion());
    S3ClientBuilder builder = S3Client.builder()
        .region(Region.of(region == null ? "us-east-1" : region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(resolvePathStyleAccess(properties))
            .checksumValidationEnabled(false)
            .build());
    String endpoint = trimToNull(properties.getEndpoint());
    if (endpoint != null) {
      builder.endpointOverride(URI.create(endpoint));
    } else if (properties.getType() != ObjectStoragePlatformType.S3) {
      throw new IllegalStateException("对象存储 endpoint 未配置");
    }
    return builder.build();
  }

  private boolean resolvePathStyleAccess(final ObjectStorageProperties.ProviderProperties properties) {
    if (properties.getPathStyleAccess() != null) {
      return properties.getPathStyleAccess();
    }
    return properties.getType() == ObjectStoragePlatformType.MINIO
        || properties.getType() == ObjectStoragePlatformType.CEPH;
  }

  private String buildAccessUrl(
      final ObjectStorageProperties.ProviderProperties properties,
      final String bucket,
      final String objectKey
  ) {
    String publicBaseUrl = trimToNull(properties.getPublicBaseUrl());
    if (publicBaseUrl != null) {
      return joinPath(publicBaseUrl, objectKey);
    }
    String endpoint = trimToNull(properties.getEndpoint());
    if (endpoint != null && resolvePathStyleAccess(properties)) {
      return joinPath(joinPath(endpoint, bucket), objectKey);
    }
    return null;
  }

  private static String joinPath(final String left, final String right) {
    String normalizedLeft = left.replaceAll("/+$", "");
    String normalizedRight = right.replaceAll("^/+", "");
    return normalizedLeft + '/' + normalizedRight;
  }

  private static Map<String, String> normalizeMetadata(final Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    metadata.forEach((key, value) -> {
      String normalizedKey = trimToNull(key);
      if (normalizedKey != null && value != null) {
        normalized.put(normalizedKey, normalizeMetadataValue(value));
      }
    });
    return normalized;
  }

  private static String normalizeMetadataValue(final String value) {
    if (value.chars().allMatch(character -> character >= ' ' && character <= '~')) {
      return value;
    }
    return "utf8-base64:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String require(final String value, final String message) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      throw new IllegalStateException(message);
    }
    return trimmed;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static final class ManagedS3InputStream extends FilterInputStream {

    private final S3Client client;

    private ManagedS3InputStream(final InputStream inputStream, final S3Client client) {
      super(inputStream);
      this.client = client;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        client.close();
      }
    }
  }
}
