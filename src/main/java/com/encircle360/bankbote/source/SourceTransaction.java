package com.encircle360.bankbote.source;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A transaction of the source. Amounts are exact: negative for money leaving the account. Text
 * fields are empty, never null.
 *
 * <p>{@link #toString()} shows only the id: purpose and counterparty are personal data and stay out
 * of logs.
 *
 * @param id the id of the transaction at the source, stable for a booked transaction
 * @param bookingDate the booking date
 * @param amount the amount in the account currency
 * @param pending whether the bank has not booked the transaction yet
 * @param purpose the purpose text
 * @param counterpartyName the name of the counterparty
 * @param counterpartyAccount the IBAN or account number of the counterparty
 * @param type the kind of transaction as the bank names it
 * @param reference the reference that identifies the payment, such as the SEPA end-to-end id
 */
public record SourceTransaction(
    String id,
    LocalDate bookingDate,
    BigDecimal amount,
    boolean pending,
    String purpose,
    String counterpartyName,
    String counterpartyAccount,
    String type,
    String reference) {

  @Override
  public String toString() {
    return "SourceTransaction[id=" + id + "]";
  }
}
