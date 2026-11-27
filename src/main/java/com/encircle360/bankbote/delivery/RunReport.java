package com.encircle360.bankbote.delivery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The summary of a run, per target and account, without secrets or personal data.
 *
 * @param startedAt when the run started
 * @param finishedAt when it ended
 * @param from the first booking date of the window
 * @param sourceError why the source could not be read at all, or null
 * @param targets the targets in their configured order
 * @param unmapped the accounts of the source without a target
 */
public record RunReport(
    Instant startedAt,
    Instant finishedAt,
    LocalDate from,
    String sourceError,
    List<TargetReport> targets,
    List<UnmappedAccount> unmapped) {

  public RunReport {
    targets = List.copyOf(targets);
    unmapped = List.copyOf(unmapped);
  }

  /** Whether the source was read and every target went through without a failure. */
  public boolean successful() {
    return sourceError == null && targets.stream().allMatch(TargetReport::successful);
  }
}
