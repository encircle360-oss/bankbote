package com.encircle360.oss.bankbote.source;

import java.util.List;

/**
 * What the source holds for an account in a window: the transactions it could read and those it
 * could not, which are never delivered.
 */
public record SourceEntries(List<SourceTransaction> transactions, List<Unreadable> unreadable) {

  public SourceEntries {
    transactions = List.copyOf(transactions);
    unreadable = List.copyOf(unreadable);
  }

  /**
   * A transaction the source holds but Bankbote cannot read safely.
   *
   * @param id the id at the source, or empty when even that is missing
   * @param reason why, without the content of the transaction
   */
  public record Unreadable(String id, String reason) {}
}
