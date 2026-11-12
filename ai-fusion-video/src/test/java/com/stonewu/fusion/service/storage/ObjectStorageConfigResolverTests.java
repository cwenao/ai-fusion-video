package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.storage.StorageConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStorageConfigResolverTests {

    private final ObjectStorageConfigResolver resolver = new ObjectStorageConfigResolver(new StorageProviderRegistry());

    @Test
    void resolvesAliyunProfileEndpointAndDefaults() {
        StorageConfig config = StorageConfig.builder()
                .type("s3")
                .provider("aliyun_oss")
                .bucketName("bucket")
                .accessKey("ak")
                .secretKey("sk")
                .region("cn-hangzhou")
                .build();

        ResolvedObjectStorageConfig resolved = resolver.resolve(config);

        assertThat(resolved.provider()).isEqualTo("aliyun_oss");
        assertThat(resolved.endpoint()).isEqualTo("https://s3.oss-cn-hangzhou.aliyuncs.com");
        assertThat(resolved.signingRegion()).isEqualTo("cn-hangzhou");
        assertThat(resolved.pathStyleAccessEnabled()).isFalse();
        assertThat(resolved.chunkedEncodingEnabled()).isFalse();
    }

    @Test
    void normalizesLegacyTencentTypeToProviderProfile() {
        StorageConfig config = StorageConfig.builder()
                .type("tencent_cos")
                .bucketName("bucket")
                .accessKey("ak")
                .secretKey("sk")
                .region("ap-shanghai")
                .build();

        ResolvedObjectStorageConfig resolved = resolver.resolve(config);

        assertThat(resolved.provider()).isEqualTo("tencent_cos");
        assertThat(resolved.endpoint()).isEqualTo("https://cos.ap-shanghai.myqcloud.com");
        assertThat(resolved.pathStyleAccessEnabled()).isFalse();
    }

    @Test
    void appliesExplicitOptionsOverProfileDefaults() {
        StorageConfig config = StorageConfig.builder()
                .type("s3")
                .provider("aliyun_oss")
                .bucketName("bucket")
                .accessKey("ak")
                .secretKey("sk")
                .region("cn-hangzhou")
                .options("""
                        {"pathStyleAccessEnabled":true,"chunkedEncodingEnabled":true,"signingRegion":"oss-cn-hangzhou"}
                        """)
                .build();

        ResolvedObjectStorageConfig resolved = resolver.resolve(config);

        assertThat(resolved.pathStyleAccessEnabled()).isTrue();
        assertThat(resolved.chunkedEncodingEnabled()).isTrue();
        assertThat(resolved.signingRegion()).isEqualTo("oss-cn-hangzhou");
    }

    @Test
    void derivesTosAccessDomainsFromTheBucketRegion() {
        StorageConfig config = StorageConfig.builder().type("s3").provider(StorageProviderRegistry.VOLCENGINE_TOS)
                .bucketName("my-bucket").accessKey("ak").secretKey("sk").region(" cn-beijing ").build();

        var resolved = resolver.resolve(config);
        var urls = new StorageUrlResolver();
        assertThat(resolved.endpoint()).isEqualTo("https://tos-cn-beijing.volces.com");
        assertThat(urls.resolvePublicUrl(resolved, "测试/图 片.png"))
                .isEqualTo("https://my-bucket.tos-cn-beijing.volces.com/%E6%B5%8B%E8%AF%95/%E5%9B%BE%20%E7%89%87.png");

        config.setEndpoint("tos-cn-beijing.ivolces.com/");
        var intranet = resolver.resolve(config);
        assertThat(intranet.endpoint()).isEqualTo("https://tos-cn-beijing.ivolces.com");
        assertThat(urls.resolvePublicUrl(intranet, "video.mp4"))
                .isEqualTo("https://my-bucket.tos-cn-beijing.volces.com/video.mp4");
    }

    @Test
    void requiresTosRegionEvenWithAManualEndpoint() {
        StorageConfig config = StorageConfig.builder().type("s3").provider(StorageProviderRegistry.VOLCENGINE_TOS)
                .bucketName("bucket").accessKey("ak").secretKey("sk").endpoint("tos-cn-beijing.ivolces.com").build();

        assertThatThrownBy(() -> resolver.resolve(config))
                .isInstanceOf(BusinessException.class).hasMessageContaining("存储桶地域");
    }

    @Test
    void requiresEndpointForGenericS3() {
        StorageConfig config = StorageConfig.builder()
                .type("s3")
                .provider("generic_s3")
                .bucketName("bucket")
                .accessKey("ak")
                .secretKey("sk")
                .build();

        assertThatThrownBy(() -> resolver.resolve(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("endpoint");
    }
}
