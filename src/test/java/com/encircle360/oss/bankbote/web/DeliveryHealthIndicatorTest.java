package com.encircle360.oss.bankbote.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.oss.bankbote.delivery.AccountReport;
import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.delivery.RunReport;
import com.encircle360.oss.bankbote.delivery.TargetReport;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DeliveryHealthIndicatorTest {

  private static Health healthAfter(RunReport report) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    try (RunCoordinator coordinator =
        new RunCoordinator(
            from -> report, Clock.systemUTC(), 14, null, Duration.ZERO, r -> done.countDown())) {
      DeliveryHealthIndicator indicator = new DeliveryHealthIndicator(coordinator);
      if (report == null) {
        return indicator.health();
      }
      coordinator.requestRun();
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      while (coordinator.running()) {
        Thread.onSpinWait();
      }
      return indicator.health();
    }
  }

  @Test
  void isUpWithoutARunYet() throws InterruptedException {
    Health health = healthAfter(null);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("running", false).doesNotContainKey("lastRun");
  }

  @Test
  void isDownAfterARunWithAFailureAndShowsItPerTarget() throws InterruptedException {
    RunReport report =
        new RunReport(
            null,
            null,
            LocalDate.of(2026, 11, 13),
            null,
            List.of(
                new TargetReport(
                    "company-b",
                    List.of(
                        new AccountReport(
                            "DE60…0003",
                            1,
                            0,
                            0,
                            0,
                            1,
                            2,
                            "ERPNext company-b is unavailable (HTTP 503)")),
                    "ERPNext company-b is unavailable (HTTP 503)")),
            List.of());

    Health health = healthAfter(report);

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails().get("lastRun").toString())
        .contains(
            "name=company-b",
            "iban=DE60…0003",
            "failed=1",
            "withBankId=2",
            "successful=false",
            "from=2026-11-13");
  }
}
