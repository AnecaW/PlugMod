package org.wannes.plugModCore.util;

import java.security.SecureRandom;

public class IdUtils {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    public static String newInternalId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62[RNG.nextInt(BASE62.length)]);
        }
        return "pmc-" + sb;
    }
}
