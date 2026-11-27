package com.encircle360.oss.bankbote.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A password, API key or API secret, read from a file. Its {@link #toString()} never shows the
 * value, so neither a log message nor an exception that names a secret can disclose it.
 */
public final class Secret {

  private final String value;

  private Secret(String value) {
    this.value = value;
  }

  /**
   * Reads a secret from a file of one line. A trailing line break is dropped, as editors and {@code
   * echo} add one; anything else in the file is part of the secret.
   */
  public static Secret fromFile(Path file) {
    if (file == null) {
      throw new SecretFileException("no secret file configured");
    }
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SecretFileException("secret file " + file + " cannot be read");
    }
    String value =
        content.endsWith("\r\n")
            ? content.substring(0, content.length() - 2)
            : content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
    if (value.isEmpty()) {
      throw new SecretFileException("secret file " + file + " is empty");
    }
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new SecretFileException("secret file " + file + " holds more than one line");
    }
    return new Secret(value);
  }

  /** The value, only for building a request header. */
  public String reveal() {
    return value;
  }

  @Override
  public String toString() {
    return "Secret[****]";
  }
}
