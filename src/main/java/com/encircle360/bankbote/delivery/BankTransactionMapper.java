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

  private BankTransactionMapper() {}

  /**
   * The {@code transaction_id} of the Bank Transaction: the configured key of the source and the
   * id of the transaction there. It stays the same for every run, which makes delivery idempotent.
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
        data(transaction.reference()),
        transactionId(sourceKey, transaction.id()),
        amount.signum() > 0 ? amount : zero,
        amount.signum() < 0 ? amount.negate() : zero,
        data(transaction.type()),
        data(transaction.counterpartyName()),
        partyIban.map(Iban::value).orElse(""),
        partyIban.isPresent() ? "" : data(transaction.counterpartyAccount()));
  }

  private static String data(String value) {
    return value.length() > DATA_LENGTH ? value.substring(0, DATA_LENGTH) : value;
  }
}
