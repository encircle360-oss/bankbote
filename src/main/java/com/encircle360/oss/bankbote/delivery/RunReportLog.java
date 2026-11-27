package com.encircle360.oss.bankbote.delivery;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes the summary of a run to the log: counts, masked IBANs and target names, nothing else. */
public final class RunReportLog {

  private static final Logger LOG = LoggerFactory.getLogger(RunReportLog.class);

  private RunReportLog() {}

  public static void log(RunReport report) {
    if (report.sourceError() != null) {
      LOG.error("run from {} failed: {}", report.from(), report.sourceError());
      return;
    }
    for (TargetReport target : report.targets()) {
      if (target.error() != null) {
        LOG.warn("target {}: {}", target.name(), target.error());
      }
      for (AccountReport account : target.accounts()) {
        String line =
            "target {} account {}: found {}, delivered {}, existing {}, pending {}, failed {}{}";
        Object[] values = {
          target.name(),
          account.iban(),
          account.found(),
          account.delivered(),
          account.existing(),
          account.pending(),
          account.failed(),
          account.error() == null ? "" : " (" + account.error() + ")"
        };
        if (account.failed() > 0 || account.error() != null) {
          LOG.warn(line, values);
        } else {
          LOG.info(line, values);
        }
      }
    }
    for (UnmappedAccount account : report.unmapped()) {
      LOG.warn(
          "account {} (id {} at the source) is mapped to no target; its {} transaction(s) in the"
              + " window go nowhere",
          account.iban(),
          account.sourceId(),
          account.found() < 0 ? "unknown number of" : account.found());
    }
    LOG.info(
        "run from {} {} in {} ms",
        report.from(),
        report.successful() ? "succeeded" : "ended with failures",
        report.startedAt() == null || report.finishedAt() == null
            ? 0
            : Duration.between(report.startedAt(), report.finishedAt()).toMillis());
  }
}
