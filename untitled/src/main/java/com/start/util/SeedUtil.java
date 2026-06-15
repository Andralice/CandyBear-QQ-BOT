package com.start.util;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 用 SHA-256 混合种子，避免 String.hashCode() 导致相近字符串产生相近种子。
 */
public class SeedUtil {

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    });

    /** 从多个字符串片段生成 64 位种子 */
    public static long seed(String... parts) {
        MessageDigest md = SHA256.get();
        md.reset();
        for (String p : parts) {
            md.update(p.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] hash = md.digest();
        return ByteBuffer.wrap(hash, 0, 8).getLong();
    }
}
