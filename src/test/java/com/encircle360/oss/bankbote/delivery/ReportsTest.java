package com.encircle360.oss.bankbote.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ReportsTest {

  private static final AccountReport OK = new AccountReport("DE17…0001", 2, 2, 0, 0, 0, 1, null);
  private static final AccountReport FAILED =
      new AccountReport("DE87…0002", 1, 0, 0, 0, 1, 0, "ERPNext a is unavailable (HTTP 503)");
  private static final AccountReport WARNED =
      new AccountReport("DE60…0003", 0, 0, 0, 0, 0, 0, "the source has no account with this IBAN");

  @Test
  void aTargetSucceedsOnlyWithoutAnyErrorOrFailure() {
    assertThat(new TargetReport("a", List.of(OK), null).successful()).isTrue();
    assertThat(new TargetReport("a", List.of(OK), "stopped").successful()).isFalse();
    assertThat(new TargetReport("a", List.of(OK, FAILED), null).successful()).isFalse();
    assertThat(new TargetReport("a", List.of(WARNED), null).successful()).isFalse();
  }

  @Test
  void logsCountsAndMaskedIbansPerTargetAndAccount(CapturedOutput output) {
    RunReport report =
        new RunReport(
            Instant.parse("2026-11-27T09:00:00Z"),
            Instant.parse("2026-11-27T09:00:01.5Z"),
            LocalDate.of(2026, 11, 13),
            null,
            List.of(
                new TargetReport(
                    "company-a",
                    List.of(OK, FAILED, WARNED),
                    "ERPNext company-a is unavailable (HTTP 503)")),
            List.of(
                new UnmappedAccount("DE06…0005", "5", 2), new UnmappedAccount("none", "6", -1)));

    RunReportLog.log(report);

    assertThat(output.getAll())
        .contains("target company-a: ERPNext company-a is unavailable (HTTP 503)")
        .contains(
            "target company-a account DE17…0001: found 2, delivered 2, existing 0, pending 0, failed 0,"
                + " with bank id 1")
        .contains(
            "WARN",
            "target company-a account DE87…0002: found 1, delivered 0, existing 0, pending 0, failed 1,"
                + " with bank id 0 (ERPNext a is unavailable (HTTP 503))")
        .contains(
            "target company-a account DE60…0003: found 0, delivered 0, existing 0, pending 0, failed 0, with bank id 0 (the source has no account with this IBAN)")
        .contains(
            "account DE06…0005 (id 5 at the source) is mapped to no target; its 2 transaction(s)")
        .contains(
            "account none (id 6 at the source) is mapped to no target; its unknown number of transaction(s)")
        .contains("run from 2026-11-13 ended with failures in 1500 ms");
  }

  @Test
  void logsAFailedSourceAndASuccessfulRun(CapturedOutput output) {
    RunReportLog.log(
        new RunReport(
            null,
            null,
            LocalDate.of(2026, 11, 13),
            "Hibiscus Server answered HTTP 500",
            List.of(),
            List.of()));
    RunReportLog.log(
        new RunReport(null, Instant.now(), LocalDate.of(2026, 11, 14), null, List.of(), List.of()));
    RunReportLog.log(
        new RunReport(Instant.now(), null, LocalDate.of(2026, 11, 15), null, List.of(), List.of()));

    assertThat(output.getAll())
        .contains("run from 2026-11-13 failed: Hibiscus Server answered HTTP 500")
        .contains("run from 2026-11-14 succeeded in 0 ms")
        .contains("run from 2026-11-15 succeeded in 0 ms");
  }

  @Test
  void aScheduledRunAndTheRunAfterTheStartAskTheCoordinator() throws InterruptedException {
    BlockingQueue<RunReport> reports = new LinkedBlockingQueue<>();
    try (RunCoordinator coordinator =
        new RunCoordinator(
            from -> new RunReport(null, null, from, null, List.of(), List.of()),
            Clock.systemUTC(),
            14,
            null,
            Duration.ZERO,
            reports::add)) {
      ScheduledRuns scheduled = new ScheduledRuns(coordinator);

      scheduled.afterStart();
      assertThat(reports.poll(5, TimeUnit.SECONDS)).isNotNull();
      scheduled.bySchedule();
      assertThat(reports.poll(5, TimeUnit.SECONDS)).isNotNull();
    }
  }
}
