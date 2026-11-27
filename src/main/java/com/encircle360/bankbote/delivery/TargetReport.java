package com.encircle360.bankbote.delivery;

import java.util.List;

/**
 * What a run did for one target.
 *
 * @param name the name of the target
 * @param accounts the accounts mapped to the target
 * @param error the error that stopped deliveries to the target in this run, or null
 */
public record TargetReport(String name, List<AccountReport> accounts, String error) {

  public TargetReport {
    accounts = List.copyOf(accounts);
  }

  /** Whether every account of the target went through without a failure. */
  public boolean successful() {
    return error == null
        && accounts.stream().allMatch(account -> account.failed() == 0 && account.error() == null);
  }
}
