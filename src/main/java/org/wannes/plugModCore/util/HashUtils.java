package org.wannes.plugModCore.util;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

public class HashUtils {

    public static String sha256(File file) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);

        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
