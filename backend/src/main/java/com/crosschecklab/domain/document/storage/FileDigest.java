package com.crosschecklab.domain.document.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// SHA-256 체크섬 계산 유틸. 같은 파일이면 언제나 같은 64자 hex 가 나온다.
final class FileDigest {

    private static final String ALGORITHM = "SHA-256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FileDigest() {
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 반드시 제공하므로 여기에 도달하면 런타임이 깨진 것이다.
            throw new IllegalStateException(ALGORITHM + " 을 사용할 수 없습니다.", e);
        }
    }

    static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            hex[i * 2] = HEX[value >>> 4];
            hex[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(hex);
    }
}
