package com.encircle360.bankbote.integration;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

/**
 * Waits until the output of a container shows a line a given number of times, and fails at once
 * when the container ends before, with its exit code and the end of its output, instead of waiting
 * for the deadline.
 */
public final class LogOrExit extends AbstractWaitStrategy {

  private static final int LINES = 80;

  private final Pattern line;
  private final int times;

  private LogOrExit(Pattern line, int times, Duration timeout) {
    this.line = line;
    this.times = times;
    withStartupTimeout(timeout);
  }

  /** Waits for {@code times} lines that match {@code regex}, for at most {@code timeout}. */
  public static LogOrExit line(String regex, int times, Duration timeout) {
    return new LogOrExit(Pattern.compile(regex), times, timeout);
  }

  @Override
  protected void waitUntilReady() {
    long deadline = System.nanoTime() + startupTimeout.toNanos();
    while (true) {
      String logs = waitStrategyTarget.getLogs();
      if (logs.lines().filter(text -> line.matcher(text).matches()).count() >= times) {
        return;
      }
      if (!waitStrategyTarget.isRunning()) {
        InspectContainerResponse.ContainerState state =
            waitStrategyTarget.getCurrentContainerInfo().getState();
        throw new ContainerLaunchException(
            "the container ended with exit code "
                + state.getExitCodeLong()
                + " before its output showed "
                + line
                + "\n"
                + tail(waitStrategyTarget.getLogs()));
      }
      if (System.nanoTime() > deadline) {
        throw new ContainerLaunchException(
            "the output of the container did not show "
                + line
                + " within "
                + startupTimeout
                + "\n"
                + tail(logs));
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ContainerLaunchException("interrupted while waiting for the container");
      }
    }
  }

  private static String tail(String logs) {
    List<String> lines = logs.lines().toList();
    return "--- last "
        + LINES
        + " lines of its output ---\n"
        + String.join("\n", lines.subList(Math.max(0, lines.size() - LINES), lines.size()));
  }
}
