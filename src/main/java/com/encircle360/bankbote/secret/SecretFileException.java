package com.encircle360.bankbote.secret;

/** A secret file is missing, unreadable or malformed. The message names the path, never content. */
public class SecretFileException extends RuntimeException {

  public SecretFileException(String message) {
    super(message);
  }
}
