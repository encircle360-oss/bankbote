package com.encircle360.oss.bankbote.web;

import com.encircle360.oss.bankbote.delivery.AccountReport;
import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.delivery.RunReport;
import com.encircle360.oss.bankbote.delivery.TargetReport;
import com.encircle360.oss.bankbote.delivery.UnmappedAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The health component {@code delivery}: the last run per target and account, with counts, masked
 * IBANs and error messages, which never carry a secret or personal data. Down after a run with a
 * failure, up otherwise; liveness and readiness do not depend on it.
 */
@Component("delivery")
public class DeliveryHealthIndicator implements HealthIndicator {

  private final RunCoordinator coordinator;

  public DeliveryHealthIndicator(RunCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public Health health() {
    Optional<RunReport> last = coordinator.lastReport();
    Health.Builder health =
        last.map(report -> report.successful() ? Health.up() : Health.down()).orElseGet(Health::up);
    health.withDetail("running", coordinator.running());
    last.ifPresent(report -> health.withDetail("lastRun", details(report)));
    return health.build();
  }

  static Map<String, Object> details(RunReport report) {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("startedAt", String.valueOf(report.startedAt()));
    run.put("finishedAt", String.valueOf(report.finishedAt()));
    run.put("from", report.from().toString());
    run.put("successful", report.successful());
    run.put("sourceError", report.sourceError());
    run.put("targets", report.targets().stream().map(DeliveryHealthIndicator::target).toList());
    run.put("unmapped", report.unmapped().stream().map(DeliveryHealthIndicator::unmapped).toList());
    return run;
  }

  private static Map<String, Object> target(TargetReport target) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("name", target.name());
    details.put("successful", target.successful());
    details.put("error", target.error());
    List<Map<String, Object>> accounts =
        target.accounts().stream().map(DeliveryHealthIndicator::account).toList();
    details.put("accounts", accounts);
    return details;
  }

  private static Map<String, Object> account(AccountReport account) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("iban", account.iban());
    details.put("found", account.found());
    details.put("delivered", account.delivered());
    details.put("existing", account.existing());
    details.put("pending", account.pending());
    details.put("failed", account.failed());
    details.put("withBankId", account.withBankId());
    details.put("error", account.error());
    return details;
  }

  private static Map<String, Object> unmapped(UnmappedAccount account) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("iban", account.iban());
    details.put("sourceId", account.sourceId());
    details.put("found", account.found());
    return details;
  }
}
