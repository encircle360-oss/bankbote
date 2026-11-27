package com.encircle360.bankbote.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.bankbote.erpnext.BankTransactionDocument;
import com.encircle360.bankbote.source.SourceTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BankTransactionMapperTest {

  private static SourceTransaction transaction(String amount, String counterpartyAccount) {
    return new SourceTransaction(
        "41",
        LocalDate.of(2026, 11, 25),
        new BigDecimal(amount),
        false,
        "Invoice 2026-0042",
        "Example Trading GmbH",
        counterpartyAccount,
        "SEPA transfer",
        "E2E-1");
  }

  @Test
  void buildsTheTransactionIdFromTheSourceKeyAndTheSourceId() {
    assertThat(BankTransactionMapper.transactionId("hibiscus", "41")).isEqualTo("hibiscus:41");
  }

  @Test
  void mapsAWithdrawal() {
    BankTransactionDocument document =
        BankTransactionMapper.map(
            transaction("-1234.56", "de87 1234 5678 0000 0000 02"), "hibiscus", "Main - EX", "EUR");

    assertThat(document)
        .isEqualTo(
            new BankTransactionDocument(
                LocalDate.of(2026, 11, 25),
                "Main - EX",
                "EUR",
                "Invoice 2026-0042",
                "E2E-1",
                "hibiscus:41",
                new BigDecimal("0.00"),
                new BigDecimal("1234.56"),
                "SEPA transfer",
                "Example Trading GmbH",
                "DE87123456780000000002",
                ""));
  }

  @Test
  void mapsADepositAndAnAccountNumberThatIsNoIban() {
    BankTransactionDocument document =
        BankTransactionMapper.map(transaction("99.10", "1234567"), "hibiscus", "Main - EX", "EUR");

    assertThat(document.deposit()).isEqualTo(new BigDecimal("99.10"));
    assertThat(document.withdrawal()).isEqualTo(new BigDecimal("0.00"));
    assertThat(document.bankPartyIban()).isEmpty();
    assertThat(document.bankPartyAccountNumber()).isEqualTo("1234567");
  }

  @Test
  void mapsAZeroAmountToNeitherSide() {
    BankTransactionDocument document =
        BankTransactionMapper.map(transaction("0.00", ""), "hibiscus", "Main - EX", "EUR");

    assertThat(document.deposit()).isEqualTo(new BigDecimal("0.00"));
    assertThat(document.withdrawal()).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void shortensDataFieldsToTheLengthOfERPNext() {
    SourceTransaction transaction =
        new SourceTransaction(
            "1",
            LocalDate.of(2026, 11, 25),
            BigDecimal.ONE,
            false,
            "p".repeat(500),
            "n".repeat(200),
            "a".repeat(200),
            "t".repeat(200),
            "r".repeat(200));

    BankTransactionDocument document =
        BankTransactionMapper.map(transaction, "hibiscus", "Main - EX", "EUR");

    assertThat(document.description()).hasSize(500);
    assertThat(document.bankPartyName()).hasSize(140);
    assertThat(document.bankPartyAccountNumber()).hasSize(140);
    assertThat(document.transactionType()).hasSize(140);
    assertThat(document.referenceNumber()).hasSize(140);
  }
}
