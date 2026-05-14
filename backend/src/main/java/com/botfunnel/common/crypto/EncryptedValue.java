package com.botfunnel.common.crypto;

public record EncryptedValue(byte[] iv, byte[] ciphertext) {
}
