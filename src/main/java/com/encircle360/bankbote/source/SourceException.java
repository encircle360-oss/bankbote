package com.encircle360.bankbote.source;

/** The source cannot be reached or answers with something Bankbote cannot read. */
public class SourceException extends Exception {

  public SourceException(String message) {
    super(message);
  }

  public SourceException(String message, Throwable cause) {
    super(message, cause);
  }
}
