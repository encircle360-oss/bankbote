package com.encircle360.bankbote.hibiscus;

import com.encircle360.bankbote.source.SourceAccount;
import com.encircle360.bankbote.source.SourceEntries;
import com.encircle360.bankbote.source.SourceEntries.Unreadable;
import com.encircle360.bankbote.source.SourceException;
import com.encircle360.bankbote.source.SourceTransaction;
import com.encircle360.bankbote.source.TransactionSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Hibiscus Server as the source, through its JSON beans {@code hibiscus/konto/list} and {@code
 * hibiscus/konto/<id>/umsaetze/days/<n>} (hibiscus.server, {@code KontoBean} and {@code
 * UmsatzBean}).
 *
 * <p>The beans hold every column of a transaction, among them {@code flags} with {@code
 * FLAG_NOTBOOKED}, which marks a pending transaction. The XML-RPC service {@code umsatz.list} does
 * not return the flags, which is why Bankbote reads the beans.
 */
public final class HibiscusSource implements TransactionSource {

  /** {@code ([0-9]{1,4})} in the path of {@code UmsatzBean}. */
  private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{1,4}");

  /** {@code ([0-9]{1,4})} for the days in the path of {@code UmsatzBean}. */
  static final int MAX_DAYS = 9999;

  private final HibiscusClient client;
  private final Clock clock;

  public HibiscusSource(HibiscusClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  @Override
  public List<SourceAccount> accounts() throws SourceException {
    List<SourceAccount> accounts = new ArrayList<>();
    for (JsonNode node : client.list("hibiscus/konto/list")) {
      accounts.add(new SourceAccount(text(node, "id"), text(node, "iban"), text(node, "waehrung")));
    }
    return accounts;
  }

  /**
   * The bean gives the transactions booked since the start of the day {@code n} days ago, as the
   * clock of the server sees it; one more day than the window needs covers a server in another time
   * zone, and the transactions before the window are dropped here.
   */
  @Override
  public SourceEntries transactions(SourceAccount account, LocalDate from) throws SourceException {
    if (!ACCOUNT_ID.matcher(account.id()).matches()) {
      throw new SourceException(
          "Hibiscus Server gives transactions only for account ids of up to four digits");
    }
    try {
      HibiscusValues.fractionDigits(account.currency());
    } catch (IllegalArgumentException e) {
      throw new SourceException(
          "account " + account.id() + " has no valid currency: " + e.getMessage());
    }
    long days = Math.max(1, ChronoUnit.DAYS.between(from, LocalDate.now(clock)) + 1);
    if (days > MAX_DAYS) {
      throw new SourceException(
          "Hibiscus Server gives at most "
              + MAX_DAYS
              + " days of transactions, the window needs "
              + days);
    }
    JsonNode list = client.list("hibiscus/konto/" + account.id() + "/umsaetze/days/" + days);
    List<SourceTransaction> transactions = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    for (JsonNode node : list) {
      if (!node.isObject()) {
        unreadable.add(new Unreadable("", "is not an object"));
        continue;
      }
      String id = text(node, "id");
      if (id.isEmpty()) {
        unreadable.add(new Unreadable("", "has no id"));
        continue;
      }
      if (!account.id().equals(text(node, "konto_id"))) {
        unreadable.add(new Unreadable(id, "belongs to another account"));
        continue;
      }
      try {
        SourceTransaction transaction = transaction(id, node, account.currency());
        if (transaction.pending() || !transaction.bookingDate().isBefore(from)) {
          transactions.add(transaction);
        }
      } catch (IllegalArgumentException e) {
        unreadable.add(new Unreadable(id, e.getMessage()));
      }
    }
    return new SourceEntries(transactions, unreadable);
  }

  /**
   * A pending transaction is never delivered, so it carries nothing but its id: whatever else it
   * holds may change until the bank books it, under a new id.
   */
  private static SourceTransaction transaction(String id, JsonNode node, String currency) {
    if (HibiscusValues.pending(text(node, "flags"))) {
      return new SourceTransaction(id, null, BigDecimal.ZERO, true, "", "", "", "", "");
    }
    return new SourceTransaction(
        id,
        HibiscusValues.date(text(node, "datum")),
        HibiscusValues.amount(text(node, "betrag"), currency),
        false,
        HibiscusValues.joinLines(text(node, "zweck"), text(node, "zweck2"), text(node, "zweck3")),
        HibiscusValues.joinWords(text(node, "empfaenger_name"), text(node, "empfaenger_name2")),
        text(node, "empfaenger_konto").strip(),
        text(node, "art").strip(),
        HibiscusValues.reference(text(node, "endtoendid"), text(node, "customerref")));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asString();
  }
}
