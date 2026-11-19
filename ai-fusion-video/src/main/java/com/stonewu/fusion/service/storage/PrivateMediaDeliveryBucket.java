package com.stonewu.fusion.service.storage;

import java.io.InputStream;
import java.time.Duration;

/** 在专用桶中保存可通过预签名 GET 读取的短期媒体副本。 */
public interface PrivateMediaDeliveryBucket {
    String PREFIX = "afv-media-delivery/";
    String CACHE_CONTROL = "private, no-store";
    Duration LINK_LIFETIME = Duration.ofHours(72);
    int OBJECT_RETENTION_DAYS = Math.toIntExact(LINK_LIFETIME.toDays() + 1);

    void verify(String key);

    void put(String key, String mimeType, InputStream data, long length);

    String signRead(String key);

    void delete(String key);
}
