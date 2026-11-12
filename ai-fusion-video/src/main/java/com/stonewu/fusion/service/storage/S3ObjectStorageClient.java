package com.stonewu.fusion.service.storage;

import cn.hutool.core.util.StrUtil;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

record S3ObjectStorageClient(S3Client client, String bucket, boolean managedMedia) implements ObjectStorageClient {

    @Override
    public void putBytes(String key, String contentType, byte[] data) {
        client.putObject(putRequest(key, contentType, data.length).build(), RequestBody.fromBytes(data));
    }

    @Override
    public void putStream(String key, String contentType, InputStream data, long length) {
        client.putObject(putRequest(key, contentType, length).build(), RequestBody.fromInputStream(data, length));
    }

    @Override
    public void putFile(String key, String contentType, Path file) throws IOException {
        var request = putRequest(key, contentType, Files.size(file));
        if (managedMedia) {
            request.overrideConfiguration(options -> options.apiCallTimeout(Duration.ofMinutes(10)));
        }
        client.putObject(request.build(), RequestBody.fromFile(file));
    }

    @Override
    public byte[] getBytes(String key) {
        return client.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
    }

    @Override
    public ObjectMetadata head(String key) {
        var response = client.headObject(request -> request.bucket(bucket).key(key));
        return new ObjectMetadata(response.contentLength(), response.eTag());
    }

    @Override
    public byte[] getRange(String key, String etag, long start, long end, long objectSize) throws IOException {
        var request = GetObjectRequest.builder().bucket(bucket).key(key).ifMatch(etag)
                .range("bytes=" + start + "-" + end).build();
        int length = Math.toIntExact(end - start + 1);
        String range = "bytes " + start + "-" + end + "/" + objectSize;
        try (var response = client.getObject(request)) {
            if (!range.equals(response.response().contentRange()) || response.response().contentLength() != length) {
                response.abort();
                throw new IOException("媒体存储未按范围返回内容");
            }
            byte[] bytes = response.readNBytes(length + 1);
            if (bytes.length != length) {
                response.abort();
                throw new IOException("媒体存储返回了不完整内容");
            }
            return bytes;
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(request -> {
            request.bucket(bucket).key(key);
            if (managedMedia) {
                request.overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(30)));
            }
        });
    }

    private PutObjectRequest.Builder putRequest(String key, String contentType, long length) {
        var request = PutObjectRequest.builder().bucket(bucket).key(key).contentLength(length);
        if (StrUtil.isNotBlank(contentType)) {
            request.contentType(contentType);
        }
        return request;
    }
}
