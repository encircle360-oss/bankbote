package com.encircle360.bankbote.integration;

import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Attaches the end of the log of Jameica to every failure of a test or of its set-up, so that a red
 * run explains what the server saw. Passwords are masked.
 */
public final class ServerLogOnFailure
    implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {

  private static final int LINES = 120;

  private final Supplier<HibiscusServer> server;

  public ServerLogOnFailure(Supplier<HibiscusServer> server) {
    this.server = server;
  }

  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable failure)
      throws Throwable {
    throw withLog(failure);
  }

  @Override
  public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable failure)
      throws Throwable {
    throw withLog(failure);
  }

  private Throwable withLog(Throwable failure) {
    HibiscusServer current = server.get();
    if (current == null) {
      return failure;
    }
    AssertionError explained =
        new AssertionError(
            failure
                + "\n--- last "
                + LINES
                + " lines of /data/jameica.log ---\n"
                + current.jameicaLog(LINES),
            failure);
    return explained;
  }
}
