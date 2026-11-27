package com.encircle360.bankbote.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RunCoordinatorTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-27T09:00:00Z"), ZoneId.of("Europe/Berlin"));
  private final BlockingQueue<RunReport> reports = new LinkedBlockingQueue<>();
  private final List<LocalDate> windows = new CopyOnWriteArrayList<>();
  private RunCoordinator coordinator;

  @AfterEach
  void stop() {
    coordinator.close();
  }

  private static RunReport report(LocalDate from) {
    return new RunReport(CLOCK.instant(), CLOCK.instant(), from, null, List.of(), List.of());
  }

  private RunCoordinator coordinator(
      Function<LocalDate, RunReport> run, LocalDate backfill, Duration delay) {
    coordinator =
        new RunCoordinator(
            from -> {
              windows.add(from);
              return run.apply(from);
            },
            CLOCK,
            14,
            backfill,
            delay,
            reports::add);
    return coordinator;
  }

  private RunReport next() throws InterruptedException {
    RunReport report = reports.poll(10, TimeUnit.SECONDS);
    assertThat(report).as("a run ended").isNotNull();
    return report;
  }

  @Test
  void looksBackTheConfiguredDaysAndKeepsTheLastReport() throws InterruptedException {
    coordinator(RunCoordinatorTest::report, null, Duration.ZERO);
    assertThat(coordinator.lastReport()).isEmpty();

    assertThat(coordinator.requestRun()).isTrue();

    assertThat(next().from()).isEqualTo(LocalDate.of(2026, 11, 13));
    assertThat(coordinator.lastReport()).map(RunReport::from).contains(LocalDate.of(2026, 11, 13));
  }

  @Test
  void backfillsOnlyInTheFirstRun() throws InterruptedException {
    coordinator(RunCoordinatorTest::report, LocalDate.of(2026, 1, 1), Duration.ZERO);

    coordinator.requestRun();
    next();
    coordinator.requestRun();
    next();

    assertThat(windows).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 13));
  }

  @Test
  void neverRunsTwiceAtOnceAndCollapsesRequestsDuringARun() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    coordinator(
        from -> {
          maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          concurrent.decrementAndGet();
          return report(from);
        },
        null,
        Duration.ZERO);

    assertThat(coordinator.requestRun()).isTrue();
    while (!coordinator.running()) {
      Thread.onSpinWait();
    }
    assertThat(coordinator.requestRun()).isTrue();
    assertThat(coordinator.requestRun()).isFalse();
    assertThat(coordinator.requestRun()).isFalse();
    release.countDown();
    next();
    next();

    assertThat(reports.poll(500, TimeUnit.MILLISECONDS)).isNull();
    assertThat(windows).hasSize(2);
    assertThat(maxConcurrent).hasValue(1);
  }

  @Test
  void aNotificationStartsARunAfterTheDelayAndCollapsesWithOthers() throws InterruptedException {
    coordinator(RunCoordinatorTest::report, null, Duration.ofMillis(300));

    long start = System.nanoTime();
    coordinator.notified();
    coordinator.notified();
    coordinator.notified();
    next();

    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(300).toNanos());
    assertThat(reports.poll(800, TimeUnit.MILLISECONDS)).isNull();
    assertThat(windows).hasSize(1);

    coordinator.notified();
    next();
    assertThat(windows).hasSize(2);
  }

  @Test
  void anUnexpectedErrorEndsTheRunWithAReportAndTheNextRunStillHappens()
      throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    coordinator(
        from -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("contains no secret");
          }
          return report(from);
        },
        null,
        Duration.ZERO);

    coordinator.requestRun();
    RunReport failed = next();
    coordinator.requestRun();
    RunReport ok = next();

    assertThat(failed.sourceError())
        .isEqualTo("the run failed unexpectedly: IllegalStateException");
    assertThat(failed.successful()).isFalse();
    assertThat(ok.successful()).isTrue();
  }

  @Test
  void refusesRequestsAfterClose() {
    coordinator(RunCoordinatorTest::report, null, Duration.ZERO);
    coordinator.close();

    assertThat(coordinator.requestRun()).isFalse();
    coordinator.notified();
    assertThat(coordinator.running()).isFalse();
  }

  private static RunReport sourceFailed(LocalDate from) {
    return new RunReport(
        CLOCK.instant(),
        CLOCK.instant(),
        from,
        "Hibiscus Server answered HTTP 500",
        List.of(),
        List.of());
  }

  @Test
  void aRunThatCannotReadTheSourceIsRepeatedOnceAfterTheDelay() throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    coordinator(
        from -> calls.incrementAndGet() == 1 ? sourceFailed(from) : report(from),
        null,
        Duration.ofMillis(300));

    long start = System.nanoTime();
    coordinator.requestRun();

    assertThat(next().sourceError()).isEqualTo("Hibiscus Server answered HTTP 500");
    assertThat(next().successful()).isTrue();
    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(300).toNanos());
    assertThat(reports.poll(800, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void aSourceThatStaysUnreadableIsRetriedOnlyOnceUntilARunSucceeds() throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    coordinator(
        from -> calls.incrementAndGet() == 3 ? report(from) : sourceFailed(from),
        null,
        Duration.ofMillis(100));

    coordinator.requestRun();
    next();
    next();
    assertThat(reports.poll(600, TimeUnit.MILLISECONDS)).isNull();
    coordinator.requestRun();
    assertThat(next().successful()).isTrue();
    assertThat(calls).hasValue(3);
  }

  @Test
  void aClosedCoordinatorRetriesNothing() throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    coordinator(
        from -> {
          calls.incrementAndGet();
          coordinator.close();
          return sourceFailed(from);
        },
        null,
        Duration.ZERO);

    coordinator.requestRun();

    assertThat(next().successful()).isFalse();
    assertThat(reports.poll(300, TimeUnit.MILLISECONDS)).isNull();
    assertThat(calls).hasValue(1);
  }
}
