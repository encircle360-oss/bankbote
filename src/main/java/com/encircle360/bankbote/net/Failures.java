package com.encircle360.bankbote.net;

/** Describes why a connection failed, for a log line. */
public final class Failures {

  private Failures() {}

  /** The simple class name of the failure and, when it has one, its message. */
  public static String describe(Exception failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
