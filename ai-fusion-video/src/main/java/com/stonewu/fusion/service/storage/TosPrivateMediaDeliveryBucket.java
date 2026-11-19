package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.service.media.model.MediaException;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.comm.common.CertStatusType;
import com.volcengine.tos.comm.common.GranteeType;
import com.volcengine.tos.comm.common.PermissionType;
import com.volcengine.tos.comm.common.StatusType;
import com.volcengine.tos.comm.common.StorageClassType;
import com.volcengine.tos.model.acl.GrantV2;
import com.volcengine.tos.model.bucket.GetBucketACLInput;
import com.volcengine.tos.model.bucket.GetBucketInfoInput;
import com.volcengine.tos.model.bucket.GetBucketLifecycleInput;
import com.volcengine.tos.model.bucket.LifecycleRule;
import com.volcengine.tos.model.bucket.ListBucketCustomDomainInput;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PutObjectInput;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

record TosPrivateMediaDeliveryBucket(TOSV2 client, String bucket, URI browserOrigin, boolean customDomain)
        implements PrivateMediaDeliveryBucket {

    @Override
    public void verify(String key) {
        var acl = client.getBucketACL(new GetBucketACLInput().setBucket(bucket));
        if (acl.getOwner() == null || !StringUtils.hasText(acl.getOwner().getId())
                || CollectionUtils.isEmpty(acl.getGrants()) || !acl.getGrants().stream().allMatch(this::allowsOnlyNamedWrites)) {
            throw MediaException.unavailable("TOS 交付桶的写入和权限修改必须仅授权给明确的账号");
        }
        requireNoBucketPolicy();

        var info = client.getBucketInfo(new GetBucketInfoInput().setBucket(bucket)).getBucketInfo();
        if (info == null || StringUtils.hasText(info.getVersioning())
                || info.getCrossRegionReplication() == StatusType.STATUS_ENABLED) {
            throw MediaException.unavailable("TOS 交付桶必须关闭版本控制和跨地域复制");
        }
        requireReadableLifecycle(key);
        if (customDomain) {
            var domains = client.listBucketCustomDomain(new ListBucketCustomDomainInput().setBucket(bucket)).getRule();
            if (CollectionUtils.isEmpty(domains) || domains.stream().noneMatch(domain ->
                    browserOrigin.getHost().equalsIgnoreCase(domain.getDomain()) && !domain.isForbidden()
                            && domain.getCertStatus() == CertStatusType.CERT_STATUS_BOUND)) {
                throw MediaException.unavailable("TOS 交付桶需要绑定已配置证书的 HTTPS 自定义域名");
            }
        }
    }

    private boolean allowsOnlyNamedWrites(GrantV2 grant) {
        var grantee = grant.getGrantee();
        if (grantee == null) {
            return false;
        }
        // TOS 显式私有对象 ACL 优先于桶 ACL。桶的公开读可以保留，对象存储的写入权限须限制到账号。
        return (grantee.getType() == GranteeType.GRANTEE_USER && StringUtils.hasText(grantee.getId()))
                || (grantee.getType() == GranteeType.GRANTEE_GROUP && (grant.getPermission() == PermissionType.PERMISSION_READ
                || grant.getPermission() == PermissionType.PERMISSION_READ_ACP
                || grant.getPermission() == PermissionType.PERMISSION_READ_NON_LIST));
    }

    private void requireReadableLifecycle(String key) {
        List<LifecycleRule> rules;
        try {
            rules = client.getBucketLifecycle(new GetBucketLifecycleInput().setBucket(bucket)).getRules();
        } catch (TosServerException failure) {
            if (failure.getStatusCode() == 404 && "NoSuchLifecycleConfiguration".equals(failure.getCode())) {
                return;
            }
            throw failure;
        }
        Instant retentionCutoff = Instant.now().plus(OBJECT_RETENTION_DAYS, ChronoUnit.DAYS);
        if (!CollectionUtils.isEmpty(rules) && rules.stream()
                .filter(rule -> rule.getStatus() == StatusType.STATUS_ENABLED && CollectionUtils.isEmpty(rule.getTags()))
                .filter(rule -> rule.getPrefix() == null || key.startsWith(rule.getPrefix()))
                .anyMatch(rule -> archivesBeforeRetention(rule, retentionCutoff))) {
            throw MediaException.unavailable("TOS 交付对象存在提前归档规则，无法保证链接在 72 小时内可读");
        }
    }

    private boolean archivesBeforeRetention(LifecycleRule rule, Instant retentionCutoff) {
        return (!CollectionUtils.isEmpty(rule.getTransitions()) && rule.getTransitions().stream().anyMatch(transition ->
                requiresRestore(transition.getStorageClass()) && (transition.getDate() == null
                        ? transition.getDays() < OBJECT_RETENTION_DAYS
                        : transition.getDate().toInstant().isBefore(retentionCutoff))))
                || (!CollectionUtils.isEmpty(rule.getAccessTimeTransitions()) && rule.getAccessTimeTransitions().stream()
                .anyMatch(transition -> requiresRestore(transition.getStorageClass())
                        && transition.getDays() < OBJECT_RETENTION_DAYS));
    }

    private boolean requiresRestore(StorageClassType storageClass) {
        return storageClass == StorageClassType.STORAGE_CLASS_ARCHIVE
                || storageClass == StorageClassType.STORAGE_CLASS_COLD_ARCHIVE
                || storageClass == StorageClassType.STORAGE_CLASS_DEEP_COLD_ARCHIVE;
    }

    private void requireNoBucketPolicy() {
        try {
            client.getBucketPolicy(bucket);
        } catch (TosServerException failure) {
            if (failure.getStatusCode() == 404 && "NoSuchBucketPolicy".equals(failure.getCode())) {
                return;
            }
            throw failure;
        }
        throw MediaException.unavailable("TOS 交付桶应使用 IAM 授权，桶策略需为空");
    }

    @Override
    public void put(String key, String mimeType, InputStream data, long length) {
        var options = new ObjectMetaRequestOptions().setContentType(mimeType).setContentDisposition("inline")
                .setCacheControl(CACHE_CONTROL).setAclType(ACLType.ACL_PRIVATE).setServerSideEncryption("AES256")
                .setStorageClass(StorageClassType.STORAGE_CLASS_STANDARD);
        // 对象过期从上传计时，额外保留一天为签发及跨零点的清理边界留余量。
        client.putObject(new PutObjectInput().setBucket(bucket).setKey(key).setOptions(options)
                .setContent(data).setContentLength(length).setObjectExpires(OBJECT_RETENTION_DAYS));
    }

    @Override
    public String signRead(String key) {
        return client.preSignedURL(new PreSignedURLInput().setBucket(bucket).setKey(key).setHttpMethod(HttpMethod.GET)
                .setExpires(LINK_LIFETIME.toSeconds()).setAlternativeEndpoint(browserOrigin.toString())
                .setCustomDomain(customDomain)).getSignedUrl();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(new DeleteObjectInput().setBucket(bucket).setKey(key));
    }
}
