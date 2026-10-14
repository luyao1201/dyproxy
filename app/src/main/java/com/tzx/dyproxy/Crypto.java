package com.tzx.dyproxy;

import android.app.AndroidAppHelper;
import android.provider.Settings;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 配置加密存储（AES-256-GCM）。
 * 密钥 = SHA-256("dyproxy-v1|" + ANDROID_ID)，设备绑定、无需额外权限。
 * 文件格式： "DPX1:" + Base64(IV(12B) || GCM密文+Tag)
 */
public final class Crypto {
    private static final String PREFIX = "DPX1:";
    private static byte[] sKey;

    private Crypto() {}

    private static synchronized byte[] key() throws Exception {
        if (sKey != null) return sKey;
        String id = "";
        try {
            android.content.Context ctx = AndroidAppHelper.currentApplication();
            if (ctx != null) {
                id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            }
        } catch (Throwable ignored) {}
        if (id == null) id = "";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        sKey = md.digest(("dyproxy-v1|" + id).getBytes(StandardCharsets.UTF_8));
        return sKey;
    }

    public static boolean isEncrypted(String content) {
        return content != null && content.startsWith(PREFIX);
    }

    public static String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String decrypt(String content) {
        try {
            if (!isEncrypted(content)) return null;
            byte[] all = Base64.decode(content.substring(PREFIX.length()), Base64.DEFAULT);
            if (all.length < 13) return null;
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
