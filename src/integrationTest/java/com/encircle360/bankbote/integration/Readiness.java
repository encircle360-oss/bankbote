package com.encircle360.bankbote.integration;

import java.io.IOException;
import java.time.Duration;

/**
 * Waits until a server answers HTTP 200. Right after a container's port opens, a server may refuse
 * the connection, close it during the TLS handshake, time out or answer 5xx; each of these counts
 * as "not ready yet" until the deadline.
 */
public final class Readiness {

  /** One attempt: the HTTP status, or an {@link IOException} when there was no answer. */
  @FunctionalInterface
  public interface Probe {
    int status() throws IOException, InterruptedException;
  }

  private Readiness() {}

  /**
   * Probes every {@code interval} until the probe answers 200.
   *
   * @return the number of attempts it took
   * @throws IllegalStateException with the last answer or failure when the deadline passes, or at
   *     once when the server answers a status that waiting cannot change, such as 401
   */
  public static int await(Probe probe, Duration timeout, Duration interval)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String last = "no attempt";
    for (int attempt = 1; ; attempt++) {
      try {
        int status = probe.status();
        if (status == 200) {
          return attempt;
        }
        if (status < 500) {
          throw new IllegalStateException("the server answered HTTP " + status);
        }
        last = "HTTP " + status;
      } catch (IOException e) {
        last = e.getClass().getSimpleName() + ": " + e.getMessage();
      }
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException(
            "the server is not ready after "
                + timeout
                + " and "
                + attempt
                + " attempts, last: "
                + last);
      }
      Thread.sleep(interval.toMillis());
    }
  }
}
