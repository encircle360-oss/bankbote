package com.encircle360.bankbote.delivery;

import com.encircle360.bankbote.account.Iban;
import com.encircle360.bankbote.erpnext.BankTransactionDocument;
import com.encircle360.bankbote.source.SourceTransaction;
import java.math.BigDecimal;
import java.util.Optional;

/** Makes the Bank Transaction of ERPNext from a booked transaction of the source. */
final class BankTransactionMapper {

  /** The length of a field of type Data in Frappe. */
  static final int DATA_LENGTH = 140;

  /** The length of {@code transaction_type} in the DocType Bank Transaction of ERPNext 16. */
  static final int TYPE_LENGTH = 50;

  private BankTransactionMapper() {}

  /**
   * The {@code transaction_id} of the Bank Transaction: the configured key of the source and the id
   * of the transaction there. It stays the same for every run, which makes delivery idempotent.
   */
  static String transactionId(String sourceKey, String sourceId) {
    return sourceKey + ":" + sourceId;
  }

  static BankTransactionDocument map(
      SourceTransaction transaction, String sourceKey, String bankAccount, String currency) {
    BigDecimal amount = transaction.amount();
    BigDecimal zero = BigDecimal.ZERO.setScale(amount.scale());
    Optional<Iban> partyIban = Iban.tryParse(transaction.counterpartyAccount());
    return new BankTransactionDocument(
        transaction.bookingDate(),
        bankAccount,
        currency,
        transaction.purpose(),
        transaction.reference(),
        transactionId(sourceKey, transaction.id()),
        amount.signum() > 0 ? amount : zero,
        amount.signum() < 0 ? amount.negate() : zero,
        cut(transaction.type(), TYPE_LENGTH),
        data(transaction.counterpartyName()),
        partyIban.map(Iban::value).orElse(""),
        partyIban.isPresent() ? "" : data(transaction.counterpartyAccount()));
  }

  private static String data(String value) {
    return cut(value, DATA_LENGTH);
  }

  private static String cut(String value, int length) {
    return value.length() > length ? value.substring(0, length) : value;
  }
}
