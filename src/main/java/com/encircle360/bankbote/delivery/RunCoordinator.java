package com.encircle360.bankbote.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts runs, one at a time. Runs execute on a single thread, so two never overlap; while one
 * runs, at most one more waits, and every further request until it starts joins that one.
 *
 * <p>Hibiscus Server notifies once per account and before it has stored the transactions of that
 * synchronisation, so a notification starts a run only after a delay, and all notifications within
 * the delay start the same run.
 */
public final class RunCoordinator implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(RunCoordinator.class);

  private final Function<LocalDate, RunReport> run;
  private final Clock clock;
  private final int lookbackDays;
  private final LocalDate backfillFrom;
  private final Duration notifyDelay;
  private final Consumer<RunReport> listener;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("bankbote-run").daemon().factory());
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("bankbote-notify").daemon().factory());
  private final AtomicBoolean queued = new AtomicBoolean();
  private final AtomicBoolean notifyPending = new AtomicBoolean();
  private final AtomicBoolean backfillDone = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile boolean running;
  private volatile RunReport last;

  /**
   * @param run delivers what is missing from the given first booking date on
   * @param clock the clock whose zone decides which day today is
   * @param lookbackDays how many days back a regular run looks
   * @param backfillFrom the first booking date of the first run, or null
   * @param notifyDelay how long after a notification the run starts
   * @param listener receives every report, on the thread of the run
   */
  public RunCoordinator(
      Function<LocalDate, RunReport> run,
      Clock clock,
      int lookbackDays,
      LocalDate backfillFrom,
      Duration notifyDelay,
      Consumer<RunReport> listener) {
    this.run = run;
    this.clock = clock;
    this.lookbackDays = lookbackDays;
    this.backfillFrom = backfillFrom;
    this.notifyDelay = notifyDelay;
    this.listener = listener;
  }

  /**
   * Asks for a run as soon as possible.
   *
   * @return whether a new run was queued; false when one is already waiting
   */
  public boolean requestRun() {
    if (closed.get() || !queued.compareAndSet(false, true)) {
      return false;
    }
    worker.execute(this::runOnce);
    return true;
  }

  /** Asks for a run after the delay of a notification of Hibiscus Server. */
  public void notified() {
    if (closed.get() || !notifyPending.compareAndSet(false, true)) {
      return;
    }
    timer.schedule(
        () -> {
          notifyPending.set(false);
          requestRun();
        },
        notifyDelay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /** The report of the last run that ended. */
  public Optional<RunReport> lastReport() {
    return Optional.ofNullable(last);
  }

  /** Whether a run is in progress. */
  public boolean running() {
    return running;
  }

  private void runOnce() {
    queued.set(false);
    running = true;
    LocalDate from =
        backfillFrom != null && backfillDone.compareAndSet(false, true)
            ? backfillFrom
            : LocalDate.now(clock).minusDays(lookbackDays);
    RunReport report;
    try {
      report = run.apply(from);
    } catch (RuntimeException e) {
      LOG.error("the run from {} failed unexpectedly", from, e);
      report =
          new RunReport(
              clock.instant(),
              clock.instant(),
              from,
              "the run failed unexpectedly: " + e.getClass().getSimpleName(),
              List.of(),
              List.of());
    }
    last = report;
    try {
      listener.accept(report);
    } finally {
      running = false;
    }
  }

  @Override
  public void close() {
    closed.set(true);
    worker.shutdownNow();
    timer.shutdownNow();
  }
}
