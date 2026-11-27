package com.encircle360.oss.bankbote.erpnext;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The fields of a new Bank Transaction of ERPNext 16 that Bankbote fills. The company follows from
 * the bank account.
 *
 * <p>{@link #toString()} shows only the transaction id: description and party are personal data and
 * stay out of logs.
 */
public record BankTransactionDocument(
    LocalDate date,
    String bankAccount,
    String currency,
    String description,
    String referenceNumber,
    String transactionId,
    BigDecimal deposit,
    BigDecimal withdrawal,
    String transactionType,
    String bankPartyName,
    String bankPartyIban,
    String bankPartyAccountNumber) {

  @Override
  public String toString() {
    return "BankTransactionDocument[transactionId=" + transactionId + "]";
  }
}
