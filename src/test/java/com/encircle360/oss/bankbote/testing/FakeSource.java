package com.encircle360.oss.bankbote.testing;

import com.encircle360.oss.bankbote.source.SourceAccount;
import com.encircle360.oss.bankbote.source.SourceEntries;
import com.encircle360.oss.bankbote.source.SourceException;
import com.encircle360.oss.bankbote.source.SourceTransaction;
import com.encircle360.oss.bankbote.source.TransactionSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A transaction source in memory, with invented accounts and transactions. */
public final class FakeSource implements TransactionSource {

  private final List<SourceAccount> accounts = new CopyOnWriteArrayList<>();
  private final Map<String, List<SourceTransaction>> transactions = new ConcurrentHashMap<>();
  private final Map<String, List<SourceEntries.Unreadable>> unreadable = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();
  private final List<LocalDate> windows = new CopyOnWriteArrayList<>();
  private volatile String accountsFailure;

  public FakeSource account(String id, String iban) {
    accounts.add(new SourceAccount(id, iban, "EUR"));
    return this;
  }

  public FakeSource booked(String accountId, String id, String date, String amount) {
    return add(
        accountId,
        new SourceTransaction(
            id,
            LocalDate.parse(date),
            new BigDecimal(amount),
            false,
            "Invoice " + id,
            "Example Customer " + id,
            "DE87123456780000000002",
            "SEPA transfer",
            "E2E-" + id));
  }

  public FakeSource pending(String accountId, String id) {
    return add(
        accountId, new SourceTransaction(id, null, BigDecimal.ZERO, true, "", "", "", "", ""));
  }

  public FakeSource unreadable(String accountId, String id) {
    unreadable
        .computeIfAbsent(accountId, key -> new CopyOnWriteArrayList<>())
        .add(new SourceEntries.Unreadable(id, "amount is not a decimal number"));
    return this;
  }

  public FakeSource failing(String accountId, String message) {
    failures.put(accountId, message);
    return this;
  }

  public FakeSource failingAccounts(String message) {
    accountsFailure = message;
    return this;
  }

  private FakeSource add(String accountId, SourceTransaction transaction) {
    transactions.computeIfAbsent(accountId, key -> new CopyOnWriteArrayList<>()).add(transaction);
    return this;
  }

  public List<LocalDate> windows() {
    return List.copyOf(windows);
  }

  @Override
  public List<SourceAccount> accounts() throws SourceException {
    if (accountsFailure != null) {
      throw new SourceException(accountsFailure);
    }
    return List.copyOf(accounts);
  }

  @Override
  public SourceEntries transactions(SourceAccount account, LocalDate from) throws SourceException {
    windows.add(from);
    if (failures.containsKey(account.id())) {
      throw new SourceException(failures.get(account.id()));
    }
    List<SourceTransaction> inWindow = new ArrayList<>();
    for (SourceTransaction transaction : transactions.getOrDefault(account.id(), List.of())) {
      if (transaction.pending() || !transaction.bookingDate().isBefore(from)) {
        inWindow.add(transaction);
      }
    }
    return new SourceEntries(inWindow, unreadable.getOrDefault(account.id(), List.of()));
  }
}
