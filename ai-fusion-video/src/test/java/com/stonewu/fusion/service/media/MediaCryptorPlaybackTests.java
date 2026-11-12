package com.stonewu.fusion.service.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCryptorPlaybackTests {

    @Test
    void derivesAnAesKeyThatAuthenticatesExistingTinkSegments(@TempDir Path temp) throws Exception {
        int block = 1024 * 1024;
        byte[] content = new byte[block + 257];
        new Random(309).nextBytes(content);
        Path file = Files.write(temp.resolve("original"), content);
        var cryptor = new MediaCryptor();
        byte[] keyset = cryptor.newKeyset();
        var encrypted = new ByteArrayOutputStream();
        cryptor.encrypt(file, encrypted, keyset, "managed-resource");
        byte[] object = encrypted.toByteArray();
        byte[] header = Arrays.copyOf(object, 24);
        var key = new SecretKeySpec(cryptor.playbackKey(keyset, header, "managed-resource"), "AES");

        assertThat(decrypt(object, 24, block, header, key, 0, false))
                .isEqualTo(Arrays.copyOf(content, block - 40));
        assertThat(decrypt(object, block, object.length, header, key, 1, true))
                .isEqualTo(Arrays.copyOfRange(content, block - 40, content.length));
    }

    private byte[] decrypt(byte[] object, int start, int end, byte[] header, SecretKeySpec key, int index,
                           boolean last) throws Exception {
        byte[] nonce = ByteBuffer.allocate(12).put(header, 17, 7).putInt(index)
                .put(last ? (byte) 1 : (byte) 0).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(object, start, end - start);
    }
}
