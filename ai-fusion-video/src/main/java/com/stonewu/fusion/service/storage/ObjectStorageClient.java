package com.stonewu.fusion.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** 已绑定存储桶的对象存储操作。 */
public interface ObjectStorageClient {

    void putBytes(String key, String contentType, byte[] data);

    void putStream(String key, String contentType, InputStream data, long length);

    void putFile(String key, String contentType, Path file) throws IOException;

    byte[] getBytes(String key) throws IOException;

    ObjectMetadata head(String key);

    byte[] getRange(String key, String etag, long start, long end, long objectSize) throws IOException;

    void delete(String key);

    record ObjectMetadata(long size, String etag) {
    }
}
