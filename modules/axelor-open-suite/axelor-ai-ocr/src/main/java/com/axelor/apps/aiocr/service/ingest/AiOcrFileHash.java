package com.axelor.apps.aiocr.service.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure SHA-256 helper for the company-scoped file-level deduplication ledger. Streams the input so
 * a large file is never fully loaded into memory.
 */
public final class AiOcrFileHash {

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final int BUFFER = 8192;

  private AiOcrFileHash() {}

  /** SHA-256 of a file on disk, as a lower-case hex string. */
  public static String sha256(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return sha256(in);
    }
  }

  /** SHA-256 of a stream, as a lower-case hex string. The caller owns/closes the stream. */
  public static String sha256(InputStream in) throws IOException {
    MessageDigest digest = newDigest();
    byte[] buffer = new byte[BUFFER];
    int read;
    while ((read = in.read(buffer)) != -1) {
      digest.update(buffer, 0, read);
    }
    return toHex(digest.digest());
  }

  /** SHA-256 of an in-memory byte array. */
  public static String sha256(byte[] bytes) {
    return toHex(newDigest().digest(bytes));
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed present on every JVM; this cannot happen.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static String toHex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }
}
