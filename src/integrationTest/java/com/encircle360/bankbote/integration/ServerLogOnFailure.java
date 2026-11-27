package com.encircle360.bankbote.integration;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Attaches the end of the logs of the servers to every failure of a test or of its set-up, so that
 * a red run explains what the servers saw. Secrets are masked by each {@link LogSource}.
 */
public final class ServerLogOnFailure
    implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {

  private static final int LINES = 120;

  private final List<Supplier<? extends LogSource>> servers;

  public ServerLogOnFailure(Supplier<? extends LogSource> server) {
    this.servers = List.of(server);
  }

  public ServerLogOnFailure(
      Supplier<? extends LogSource> first, Supplier<? extends LogSource> second) {
    this.servers = List.of(first, second);
  }

  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable failure)
      throws Throwable {
    throw withLogs(failure);
  }

  @Override
  public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable failure)
      throws Throwable {
    throw withLogs(failure);
  }

  private Throwable withLogs(Throwable failure) {
    StringBuilder logs = new StringBuilder();
    servers.stream()
        .map(Supplier::get)
        .filter(Objects::nonNull)
        .forEach(server -> logs.append('\n').append(server.excerpt(LINES)));
    if (logs.isEmpty()) {
      return failure;
    }
    return new AssertionError(failure + "\n" + logs, failure);
  }
}
