package com.encircle360.oss.bankbote.source;

import java.time.LocalDate;
import java.util.List;

/**
 * Where Bankbote reads accounts and their transactions. Hibiscus Server is the only source today;
 * another one implements this interface and keeps the rules of {@link SourceTransaction}.
 */
public interface TransactionSource {

  /** All accounts the source holds. */
  List<SourceAccount> accounts() throws SourceException;

  /**
   * The transactions of an account booked on {@code from} or later, pending ones marked as such.
   */
  SourceEntries transactions(SourceAccount account, LocalDate from) throws SourceException;
}
