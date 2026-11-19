package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.media.model.MediaException;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.comm.common.GranteeType;
import com.volcengine.tos.comm.common.PermissionType;
import com.volcengine.tos.comm.common.StatusType;
import com.volcengine.tos.comm.common.StorageClassType;
import com.volcengine.tos.model.acl.GrantV2;
import com.volcengine.tos.model.acl.GranteeV2;
import com.volcengine.tos.model.acl.Owner;
import com.volcengine.tos.model.bucket.BucketInfo;
import com.volcengine.tos.model.bucket.GetBucketACLInput;
import com.volcengine.tos.model.bucket.GetBucketACLOutput;
import com.volcengine.tos.model.bucket.GetBucketInfoInput;
import com.volcengine.tos.model.bucket.GetBucketInfoOutput;
import com.volcengine.tos.model.bucket.GetBucketLifecycleInput;
import com.volcengine.tos.model.bucket.GetBucketLifecycleOutput;
import com.volcengine.tos.model.bucket.LifecycleRule;
import com.volcengine.tos.model.bucket.Transition;
import com.volcengine.tos.model.object.PutObjectInput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateMediaDeliveryBucketTests {
    private static final String BUCKET = "media-delivery-fixture";
    private static final String PREFIX = PrivateMediaDeliveryBucket.PREFIX;
    private static final String KEY = PREFIX + "fixture";
    private static final String REGION_ORIGIN = "https://tos-cn-shanghai.volces.com";
    private static final String CUSTOM_ORIGIN = "https://delivery.example.test";
    private final ObjectStorageConfigResolver resolver = new ObjectStorageConfigResolver(new StorageProviderRegistry());

    @Test
    void writesPrivateEncryptedTosObjectsWithObjectLevelExpiryAndInlineMetadata() {
        var client = privateTosClient();
        when(client.getBucketLifecycle(any(GetBucketLifecycleInput.class)))
                .thenThrow(new TosServerException(404).setCode("NoSuchLifecycleConfiguration"));
        var target = new TosPrivateMediaDeliveryBucket(client, BUCKET, URI.create(REGION_ORIGIN), false);

        target.verify(KEY);
        var stream = new ByteArrayInputStream(new byte[]{9, 3});
        target.put(KEY, "video/mp4", stream, 2);

        var upload = ArgumentCaptor.forClass(PutObjectInput.class);
        verify(client).putObject(upload.capture());
        assertThat(upload.getValue().getBucket()).isEqualTo(BUCKET);
        assertThat(upload.getValue().getKey()).isEqualTo(KEY);
        assertThat(upload.getValue().getContentLength()).isEqualTo(2);
        assertThat(upload.getValue().getObjectExpires()).isEqualTo(4);
        var metadata = upload.getValue().getOptions();
        assertThat(metadata.getAclType()).isEqualTo(ACLType.ACL_PRIVATE);
        assertThat(metadata.getContentType()).isEqualTo("video/mp4");
        assertThat(metadata.getContentDisposition()).isEqualTo("inline");
        assertThat(metadata.getCacheControl()).isEqualTo("private, no-store");
        assertThat(metadata.getServerSideEncryption()).isEqualTo("AES256");
    }

    @Test
    void refusesTosBucketPoliciesBeforePreparingAnyDeliveryCopies() {
        var client = mock(TOSV2.class);
        when(client.getBucketACL(any(GetBucketACLInput.class))).thenReturn(ownerOnlyAcl());
        var target = new TosPrivateMediaDeliveryBucket(client, BUCKET, URI.create(REGION_ORIGIN), false);

        assertThatThrownBy(() -> target.verify(KEY)).isInstanceOf(MediaException.class).hasMessageContaining("桶策略需为空");
    }

    @Test
    void tosAllowsBucketPublicReadWithExplicitPrivateObjectsAndDeniesGroupWrites() {
        var client = privateTosClient();
        var groupGrant = new GrantV2().setGrantee(new GranteeV2().setType(GranteeType.GRANTEE_GROUP))
                .setPermission(PermissionType.PERMISSION_READ);
        var acl = ownerOnlyAcl();
        acl.setGrants(List.of(acl.getGrants().getFirst(), groupGrant));
        when(client.getBucketACL(any(GetBucketACLInput.class))).thenReturn(acl);
        when(client.getBucketLifecycle(any(GetBucketLifecycleInput.class)))
                .thenThrow(new TosServerException(404).setCode("NoSuchLifecycleConfiguration"));
        var target = new TosPrivateMediaDeliveryBucket(client, BUCKET, URI.create(REGION_ORIGIN), false);

        target.verify(KEY);
        groupGrant.setPermission(PermissionType.PERMISSION_WRITE_ACP);
        assertThatThrownBy(() -> target.verify(KEY)).isInstanceOf(MediaException.class).hasMessageContaining("权限修改");
    }

    @Test
    void tosRejectsEarlyArchiveOnlyWhenItAppliesToTheCopy() {
        var client = privateTosClient();
        var early = new LifecycleRule().setPrefix("archive/").setStatus(StatusType.STATUS_ENABLED)
                .setTransitions(List.of(new Transition().setDays(2).setStorageClass(StorageClassType.STORAGE_CLASS_ARCHIVE)));
        when(client.getBucketLifecycle(any(GetBucketLifecycleInput.class)))
                .thenReturn(new GetBucketLifecycleOutput().setRules(List.of(early)));
        var target = new TosPrivateMediaDeliveryBucket(client, BUCKET, URI.create(REGION_ORIGIN), false);

        target.verify(KEY);
        early.setPrefix("");
        assertThatThrownBy(() -> target.verify(KEY)).isInstanceOf(MediaException.class).hasMessageContaining("提前归档");
    }

    @Test
    void signsTosGetForBothTheRegionalBucketAndTheBoundCustomDomain() {
        var settings = StorageConfig.builder().type("s3").provider(StorageProviderRegistry.VOLCENGINE_TOS)
                .bucketName(BUCKET).region("cn-shanghai").accessKey("fixture-ak").secretKey("fixture-sk").build();
        var clients = new ObjectStorageClients(mock(S3ClientFactory.class));
        try {
            var regional = URI.create(clients.forPrivateMediaDelivery(resolver.resolve(settings)).signRead(KEY));
            assertThat(regional.getScheme()).isEqualTo("https");
            assertThat(regional.getHost()).isEqualTo(BUCKET + ".tos-cn-shanghai.volces.com");
            assertThat(regional.getPath()).isEqualTo("/" + KEY);
            assertThat(regional.getRawQuery()).contains("X-Tos-Expires=259200", "X-Tos-Signature=", "X-Tos-SignedHeaders=host");

            settings.setCustomDomain(CUSTOM_ORIGIN);
            var custom = URI.create(clients.forPrivateMediaDelivery(resolver.resolve(settings)).signRead(KEY));
            assertThat(custom.getScheme() + "://" + custom.getHost()).isEqualTo(CUSTOM_ORIGIN);
            assertThat(custom.getPath()).isEqualTo("/" + KEY);
            assertThat(custom.getRawQuery()).contains("X-Tos-Expires=259200", "X-Tos-Signature=");
        } finally {
            clients.closeAll();
        }
    }

    @Test
    void s3RejectsPublicAccessAndSignsAGetWithNoExtraBrowserHeaders() {
        var client = mock(S3Client.class);
        when(client.getPublicAccessBlock(PrivateMediaDeliveryBucketTests.<GetPublicAccessBlockRequest.Builder>request()))
                .thenReturn(GetPublicAccessBlockResponse.builder().publicAccessBlockConfiguration(
                        PublicAccessBlockConfiguration.builder().blockPublicAcls(true).build()).build());
        var target = s3Target(client);

        assertThatThrownBy(() -> target.verify(KEY)).isInstanceOf(MediaException.class).hasMessageContaining("阻止公共访问");
        var signed = URI.create(target.signRead(KEY));
        assertThat(signed.getScheme()).isEqualTo("https");
        assertThat(signed.getHost()).isEqualTo(BUCKET + ".s3.us-east-1.amazonaws.com");
        assertThat(signed.getPath()).isEqualTo("/" + KEY);
        assertThat(signed.getRawQuery()).contains("X-Amz-Expires=259200", "X-Amz-Signature=", "X-Amz-SignedHeaders=host");
    }

    @Test
    void s3RequiresFourDayRetentionAndRejectsAnOverlappingShorterRule() {
        var client = mock(S3Client.class);
        when(client.getPublicAccessBlock(PrivateMediaDeliveryBucketTests.<GetPublicAccessBlockRequest.Builder>request()))
                .thenReturn(GetPublicAccessBlockResponse.builder().publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(true).ignorePublicAcls(true).blockPublicPolicy(true).restrictPublicBuckets(true).build()).build());
        when(client.getBucketPolicy(PrivateMediaDeliveryBucketTests.<GetBucketPolicyRequest.Builder>request()))
                .thenThrow(S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucketPolicy").build()).build());
        when(client.getBucketVersioning(PrivateMediaDeliveryBucketTests.<GetBucketVersioningRequest.Builder>request()))
                .thenReturn(GetBucketVersioningResponse.builder().build());
        var lifecycle = GetBucketLifecycleConfigurationResponse.builder();
        when(client.getBucketLifecycleConfiguration(PrivateMediaDeliveryBucketTests.<GetBucketLifecycleConfigurationRequest.Builder>request()))
                .thenReturn(lifecycle.rules(rule -> rule.status(ExpirationStatus.ENABLED)
                                .filter(filter -> filter.prefix(PREFIX)).expiration(expiry -> expiry.days(3))).build(),
                        lifecycle.rules(rule -> rule.status(ExpirationStatus.ENABLED)
                                        .filter(filter -> filter.prefix(PREFIX)).expiration(expiry -> expiry.days(4)),
                                rule -> rule.status(ExpirationStatus.ENABLED).expiration(expiry -> expiry.days(1))).build(),
                        lifecycle.rules(rule -> rule.status(ExpirationStatus.ENABLED)
                                .filter(filter -> filter.prefix(PREFIX)).expiration(expiry -> expiry.days(4))).build());
        var target = s3Target(client);

        assertThatThrownBy(() -> target.verify(KEY)).isInstanceOf(MediaException.class).hasMessageContaining("至少 4 天");
        assertThatThrownBy(() -> target.verify(KEY)).isInstanceOf(MediaException.class).hasMessageContaining("提前删除");
        target.verify(KEY);
    }

    private S3PrivateMediaDeliveryBucket s3Target(S3Client client) {
        var settings = StorageConfig.builder().type("s3").provider("aws_s3").bucketName(BUCKET)
                .region("us-east-1").accessKey("fixture-ak").secretKey("fixture-sk").build();
        var config = resolver.resolve(settings);
        return new S3PrivateMediaDeliveryBucket(client, config, URI.create(config.endpoint()));
    }

    private TOSV2 privateTosClient() {
        var client = mock(TOSV2.class);
        when(client.getBucketACL(any(GetBucketACLInput.class))).thenReturn(ownerOnlyAcl());
        when(client.getBucketPolicy(BUCKET)).thenThrow(new TosServerException(404).setCode("NoSuchBucketPolicy"));
        when(client.getBucketInfo(any(GetBucketInfoInput.class))).thenReturn(new GetBucketInfoOutput().setBucketInfo(new BucketInfo()));
        return client;
    }

    private static <T> Consumer<T> request() {
        return any();
    }

    private GetBucketACLOutput ownerOnlyAcl() {
        return new GetBucketACLOutput().setOwner(new Owner().setId("owner"))
                .setGrants(List.of(new GrantV2().setGrantee(new GranteeV2().setType(GranteeType.GRANTEE_USER).setId("owner"))));
    }

}
