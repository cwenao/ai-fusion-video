package com.stonewu.fusion.service.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.GetObjectV2Input;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PutObjectFromFileInput;
import com.volcengine.tos.model.object.PutObjectInput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

record TosObjectStorageClient(TOSV2 client, String bucket) implements ObjectStorageClient {

    @Override
    public void putBytes(String key, String contentType, byte[] data) {
        putStream(key, contentType, new ByteArrayInputStream(data), data.length);
    }

    @Override
    public void putStream(String key, String contentType, InputStream data, long length) {
        client.putObject(new PutObjectInput().setBucket(bucket).setKey(key).setContent(data)
                .setContentLength(length).setOptions(new ObjectMetaRequestOptions().setContentType(contentType)));
    }

    @Override
    public void putFile(String key, String contentType, Path file) {
        client.putObjectFromFile(new PutObjectFromFileInput().setBucket(bucket).setKey(key)
                .setFilePath(file.toString()).setOptions(new ObjectMetaRequestOptions().setContentType(contentType)));
    }

    @Override
    public byte[] getBytes(String key) throws IOException {
        try (var response = client.getObject(new GetObjectV2Input().setBucket(bucket).setKey(key))) {
            return response.getContent().readAllBytes();
        }
    }

    @Override
    public ObjectMetadata head(String key) {
        var response = client.headObject(new HeadObjectV2Input().setBucket(bucket).setKey(key));
        return new ObjectMetadata(response.getContentLength(), response.getEtag());
    }

    @Override
    public byte[] getRange(String key, String etag, long start, long end, long objectSize) throws IOException {
        var options = new ObjectMetaRequestOptions().setRange(start, end).setIfMatch(etag);
        var request = new GetObjectV2Input().setBucket(bucket).setKey(key).setOptions(options);
        int length = Math.toIntExact(end - start + 1);
        String range = "bytes " + start + "-" + end + "/" + objectSize;
        try (var response = client.getObject(request)) {
            if (!range.equals(response.getContentRange()) || response.getContentLength() != length) {
                response.forceClose();
                throw new IOException("媒体存储未按范围返回内容");
            }
            byte[] bytes = response.getContent().readNBytes(length + 1);
            if (bytes.length != length) {
                response.forceClose();
                throw new IOException("媒体存储返回了不完整内容");
            }
            return bytes;
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(new DeleteObjectInput().setBucket(bucket).setKey(key));
    }
}
