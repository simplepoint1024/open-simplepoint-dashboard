package org.simplepoint.plugin.storage.s3.support;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteRequest;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteResult;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3CompatibleObjectStorageDriverTest {

  private final S3CompatibleObjectStorageDriver driver = new S3CompatibleObjectStorageDriver();

  // ── supports ───────────────────────────────────────────────────────────────

  @Test
  void supports_minio_returnsTrue() {
    assertThat(driver.supports(ObjectStoragePlatformType.MINIO)).isTrue();
  }

  @Test
  void supports_s3_returnsTrue() {
    assertThat(driver.supports(ObjectStoragePlatformType.S3)).isTrue();
  }

  @Test
  void supports_aliyunOss_returnsTrue() {
    assertThat(driver.supports(ObjectStoragePlatformType.ALIYUN_OSS)).isTrue();
  }

  @Test
  void supports_tencentCos_returnsTrue() {
    assertThat(driver.supports(ObjectStoragePlatformType.TENCENT_COS)).isTrue();
  }

  @Test
  void supports_ceph_returnsTrue() {
    assertThat(driver.supports(ObjectStoragePlatformType.CEPH)).isTrue();
  }

  // ── missing required fields ─────────────────────────────────────────────────

  @Test
  void read_missingAccessKey_throwsIllegalState() {
    ObjectStorageProperties.ProviderProperties props = new ObjectStorageProperties.ProviderProperties();
    props.setSecretKey("secret");
    props.setBucket("bucket");

    assertThatThrownBy(() -> driver.read(props, "bucket", "key", "file"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accessKey");
  }

  @Test
  void read_missingSecretKey_throwsIllegalState() {
    ObjectStorageProperties.ProviderProperties props = new ObjectStorageProperties.ProviderProperties();
    props.setAccessKey("access");
    props.setBucket("bucket");

    assertThatThrownBy(() -> driver.read(props, "bucket", "key", "file"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("secretKey");
  }

  @Test
  void read_missingBucket_throwsIllegalState() {
    ObjectStorageProperties.ProviderProperties props = new ObjectStorageProperties.ProviderProperties();
    props.setAccessKey("access");
    props.setSecretKey("secret");

    assertThatThrownBy(() -> driver.read(props, "bucket", "key", "file"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bucket");
  }

  // ── buildClient: missing endpoint for non-S3 ──────────────────────────────

  @Test
  void read_nonS3TypeWithoutEndpoint_throwsIllegalState() {
    ObjectStorageProperties.ProviderProperties props = minioProps();
    props.setEndpoint(null);

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(mock(S3Client.class));

      assertThatThrownBy(() -> driver.read(props, "bucket", "key", "file"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("endpoint");
    }
  }

  // ── write happy path ──────────────────────────────────────────────────────

  @Test
  void write_happyPath_returnsResult() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      PutObjectResponse response = PutObjectResponse.builder().eTag("etag123").build();
      when(clientMock.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(response);

      ObjectStorageWriteRequest request = new ObjectStorageWriteRequest(
          "bucket", "path/to/object.txt", "object.txt", "text/plain",
          4L, Map.of(), new ByteArrayInputStream("test".getBytes())
      );

      ObjectStorageWriteResult result = driver.write(props, request);

      assertThat(result.getObjectKey()).isEqualTo("path/to/object.txt");
      assertThat(result.getETag()).isEqualTo("etag123");
    }
  }

  // ── read happy path ───────────────────────────────────────────────────────

  @Test
  void read_happyPath_returnsInputStream() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      GetObjectResponse getResponse = GetObjectResponse.builder()
          .contentType("text/plain")
          .contentLength(5L)
          .build();
      @SuppressWarnings("unchecked")
      ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
          getResponse, AbortableInputStream.create(new ByteArrayInputStream("hello".getBytes())));
      when(clientMock.getObject(any(GetObjectRequest.class))).thenReturn(stream);

      ObjectStorageReadResult result = driver.read(props, "bucket", "path/obj.txt", "obj.txt");

      assertThat(result.getContentType()).isEqualTo("text/plain");
      assertThat(result.getContentLength()).isEqualTo(5L);
      assertThat(result.getFileName()).isEqualTo("obj.txt");
    }
  }

  // ── read error cases ──────────────────────────────────────────────────────

  @Test
  void read_noSuchKey_throwsNoSuchElementException() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      when(clientMock.getObject(any(GetObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().message("not found").build());

      assertThatThrownBy(() -> driver.read(props, "bucket", "missing.txt", "missing.txt"))
          .isInstanceOf(NoSuchElementException.class)
          .hasMessageContaining("missing.txt");
    }
  }

  @Test
  void read_s3Exception404_throwsNoSuchElementException() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      S3Exception notFound = (S3Exception) S3Exception.builder()
          .statusCode(404)
          .message("not found")
          .build();
      when(clientMock.getObject(any(GetObjectRequest.class))).thenThrow(notFound);

      assertThatThrownBy(() -> driver.read(props, "bucket", "gone.txt", "gone.txt"))
          .isInstanceOf(NoSuchElementException.class);
    }
  }

  // ── delete ────────────────────────────────────────────────────────────────

  @Test
  void delete_happyPath_invokesDeleteObject() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      driver.delete(props, "bucket", "path/obj.txt");

      verify(clientMock).deleteObject(any(DeleteObjectRequest.class));
    }
  }

  @Test
  void delete_404Exception_silentlyIgnored() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      S3Exception notFound = (S3Exception) S3Exception.builder()
          .statusCode(404)
          .message("not found")
          .build();
      when(clientMock.deleteObject(any(DeleteObjectRequest.class))).thenThrow(notFound);

      driver.delete(props, "bucket", "path/obj.txt"); // no exception
    }
  }

  @Test
  void delete_otherS3Exception_throwsIllegalState() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Client clientMock = mock(S3Client.class);
      S3ClientBuilder builder = mockS3Builder(s3Static, s3CfgStatic);
      when(builder.build()).thenReturn(clientMock);

      software.amazon.awssdk.awscore.exception.AwsErrorDetails details =
          software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
              .errorMessage("server error")
              .build();
      S3Exception serverError = (S3Exception) S3Exception.builder()
          .statusCode(500)
          .awsErrorDetails(details)
          .build();
      when(clientMock.deleteObject(any(DeleteObjectRequest.class))).thenThrow(serverError);

      assertThatThrownBy(() -> driver.delete(props, "bucket", "path/obj.txt"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("删除失败");
    }
  }

  // ── resolvePathStyleAccess ────────────────────────────────────────────────

  @Test
  void write_minioType_usesPathStyle() {
    ObjectStorageProperties.ProviderProperties props = minioProps();

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Configuration.Builder configBuilder = mock(S3Configuration.Builder.class);
      S3Configuration config = mock(S3Configuration.class);
      s3CfgStatic.when(S3Configuration::builder).thenReturn(configBuilder);
      when(configBuilder.pathStyleAccessEnabled(true)).thenReturn(configBuilder);
      when(configBuilder.checksumValidationEnabled(anyBoolean())).thenReturn(configBuilder);
      when(configBuilder.build()).thenReturn(config);

      S3ClientBuilder builder = mock(S3ClientBuilder.class);
      s3Static.when(S3Client::builder).thenReturn(builder);
      when(builder.region(any(Region.class))).thenReturn(builder);
      when(builder.credentialsProvider(any(StaticCredentialsProvider.class))).thenReturn(builder);
      when(builder.serviceConfiguration(any(S3Configuration.class))).thenReturn(builder);
      when(builder.endpointOverride(any())).thenReturn(builder);
      S3Client clientMock = mock(S3Client.class);
      when(builder.build()).thenReturn(clientMock);

      PutObjectResponse response = PutObjectResponse.builder().eTag("e").build();
      when(clientMock.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(response);

      ObjectStorageWriteRequest request = new ObjectStorageWriteRequest(
          "bucket", "k.txt", "k.txt", "text/plain",
          4L, Map.of(), new ByteArrayInputStream("test".getBytes())
      );
      driver.write(props, request);

      verify(configBuilder).pathStyleAccessEnabled(true);
    }
  }

  @Test
  void write_explicitPathStyleFalse_overridesMinioDefault() {
    ObjectStorageProperties.ProviderProperties props = minioProps();
    props.setPathStyleAccess(false);

    try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class);
         MockedStatic<S3Configuration> s3CfgStatic = mockStatic(S3Configuration.class)) {

      S3Configuration.Builder configBuilder = mock(S3Configuration.Builder.class);
      S3Configuration config = mock(S3Configuration.class);
      s3CfgStatic.when(S3Configuration::builder).thenReturn(configBuilder);
      when(configBuilder.pathStyleAccessEnabled(false)).thenReturn(configBuilder);
      when(configBuilder.checksumValidationEnabled(anyBoolean())).thenReturn(configBuilder);
      when(configBuilder.build()).thenReturn(config);

      S3ClientBuilder builder = mock(S3ClientBuilder.class);
      s3Static.when(S3Client::builder).thenReturn(builder);
      when(builder.region(any(Region.class))).thenReturn(builder);
      when(builder.credentialsProvider(any(StaticCredentialsProvider.class))).thenReturn(builder);
      when(builder.serviceConfiguration(any(S3Configuration.class))).thenReturn(builder);
      when(builder.endpointOverride(any())).thenReturn(builder);
      S3Client clientMock = mock(S3Client.class);
      when(builder.build()).thenReturn(clientMock);

      PutObjectResponse response = PutObjectResponse.builder().eTag("e").build();
      when(clientMock.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(response);

      ObjectStorageWriteRequest request = new ObjectStorageWriteRequest(
          "bucket", "k.txt", "k.txt", "text/plain",
          4L, Map.of(), new ByteArrayInputStream("test".getBytes())
      );
      driver.write(props, request);

      verify(configBuilder).pathStyleAccessEnabled(false);
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static ObjectStorageProperties.ProviderProperties minioProps() {
    ObjectStorageProperties.ProviderProperties props = new ObjectStorageProperties.ProviderProperties();
    props.setType(ObjectStoragePlatformType.MINIO);
    props.setAccessKey("accessKey");
    props.setSecretKey("secretKey");
    props.setBucket("bucket");
    props.setEndpoint("http://localhost:9000");
    return props;
  }

  private static S3ClientBuilder mockS3Builder(
      MockedStatic<S3Client> s3Static,
      MockedStatic<S3Configuration> s3CfgStatic
  ) {
    S3Configuration.Builder configBuilder = mock(S3Configuration.Builder.class);
    S3Configuration config = mock(S3Configuration.class);
    s3CfgStatic.when(S3Configuration::builder).thenReturn(configBuilder);
    when(configBuilder.pathStyleAccessEnabled(anyBoolean())).thenReturn(configBuilder);
    when(configBuilder.checksumValidationEnabled(anyBoolean())).thenReturn(configBuilder);
    when(configBuilder.build()).thenReturn(config);

    S3ClientBuilder builder = mock(S3ClientBuilder.class);
    s3Static.when(S3Client::builder).thenReturn(builder);
    when(builder.region(any(Region.class))).thenReturn(builder);
    when(builder.credentialsProvider(any(StaticCredentialsProvider.class))).thenReturn(builder);
    when(builder.serviceConfiguration(any(S3Configuration.class))).thenReturn(builder);
    when(builder.endpointOverride(any())).thenReturn(builder);

    return builder;
  }
}
