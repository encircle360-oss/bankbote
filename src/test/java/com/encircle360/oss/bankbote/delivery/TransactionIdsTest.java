package com.encircle360.oss.bankbote.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.oss.bankbote.account.Iban;
import com.encircle360.oss.bankbote.source.SourceTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TransactionIdsTest {

  private static final Iban A1 = Iban.parse("DE17123456780000000001");
  private static final Iban A2 = Iban.parse("DE87123456780000000002");
  private static final String INVOICE = "Rechnung 2026-0042\nfür Müller & Söhne";

  private static SourceTransaction transaction(
      String id,
      String booking,
      String value,
      String amount,
      String counterparty,
      String purpose,
      String bankId) {
    return new SourceTransaction(
        id,
        LocalDate.parse(booking),
        value == null ? null : LocalDate.parse(value),
        new BigDecimal(amount),
        false,
        purpose,
        "Example Trading GmbH",
        counterparty,
        "SEPA transfer",
        "E2E-1",
        bankId);
  }

  private static SourceTransaction invoice(String id, String bankId) {
    return transaction(
        id, "2026-11-20", "2026-11-20", "-119.00", "DE87 1234 5678 0000 0000 02", INVOICE, bankId);
  }

  private static String key(SourceTransaction transaction) {
    return TransactionIds.key(A1, transaction);
  }

  private static List<String> ids(List<SourceTransaction> booked) {
    return TransactionIds.assign(A1, booked).transactionIds();
  }

  /** The vectors of the table in docs/architektur.md; both must stay identical. */
  @Test
  void derivesTheDocumentedVectors() {
    assertThat(TransactionIds.transactionId(A1, invoice("1", ""), 1))
        .isEqualTo("b1-c-e2a692a01368e5b6c192945bb8bcc12c-1");
    assertThat(TransactionIds.transactionId(A1, invoice("1", "NOTPROVIDED"), 1))
        .isEqualTo("b1-c-e2a692a01368e5b6c192945bb8bcc12c-1");
    assertThat(TransactionIds.transactionId(A1, invoice("1", "TX-2026-0001"), 1))
        .isEqualTo("b1-r-99684f9a4eb07a1404badb1bf21830fd-1");
    assertThat(
            TransactionIds.transactionId(
                A1,
                transaction("2", "2026-11-21", null, "-4.50", "", "Kartenzahlung Bäckerei", ""),
                2))
        .isEqualTo("b1-c-dabc3fbdda7aa130d25f753ffb4c3807-2");
    assertThat(
            TransactionIds.transactionId(
                A1,
                transaction("3", "2026-11-22", "2026-11-23", "1234.560", "", "Gutschrift", ""),
                1))
        .isEqualTo("b1-c-21c5d87925d386bbfb59abd480bc2a32-1");
  }

  @Test
  void hashesTheDocumentedCanonicalForm() {
    assertThat(
            TransactionIds.canonical(
                "c",
                A1.value(),
                "2026-11-20",
                "2026-11-20",
                "-119",
                TransactionIds.text("DE87 1234 5678 0000 0000 02"),
                TransactionIds.text(INVOICE)))
        .isEqualTo(
            "b1|c|DE17123456780000000001|2026-11-20|2026-11-20|-119|DE87123456780000000002"
                + "|RECHNUNG20260042FÜRMÜLLERSÖHNE");
  }

  @Test
  void anIdentifierIsShortAndCannotBeMistakenForOneOfAnEarlierVersion() {
    for (String bankId : List.of("", "TX-2026-0001")) {
      assertThat(TransactionIds.transactionId(A1, invoice("1", bankId), 12345))
          .matches("b1-[rc]-[0-9a-f]{32}-12345")
          .hasSizeLessThan(140)
          .doesNotContain(":");
    }
  }

  @Test
  void textKeepsOnlyLettersAndDigitsInUpperCaseAfterNfkc() {
    assertThat(TransactionIds.text(" Rechnung\r\n2026-0042,  für\tMüller & Söhne. "))
        .isEqualTo("RECHNUNG20260042FÜRMÜLLERSÖHNE");
    assertThat(TransactionIds.text("ﬁle Ｎｒ．１２ straße")).isEqualTo("FILENR12STRASSE");
    assertThat(TransactionIds.text("Müller")).isEqualTo(TransactionIds.text("Müller"));
    assertThat(TransactionIds.text(null)).isEmpty();
  }

  @Test
  void spacingCasePunctuationAndLineBreaksDoNotChangeTheKey() {
    String key = key(invoice("1", ""));

    assertThat(
            key(
                transaction(
                    "2",
                    "2026-11-20",
                    "2026-11-20",
                    "-119",
                    "de87123456780000000002",
                    "RECHNUNG 2026 0042 FÜR MÜLLER söhne",
                    "")))
        .isEqualTo(key);
    assertThat(
            key(
                transaction(
                    "3",
                    "2026-11-20",
                    "2026-11-20",
                    "-119.0000",
                    "DE87-1234-5678-0000-0000-02",
                    "Rechnung 2026/0042 für\nMüller\n& Söhne",
                    "")))
        .isEqualTo(key);
    assertThat(key(invoice("4", " tx-2026-0001 "))).isEqualTo(key(invoice("5", "TX 2026 0001")));
  }

  @Test
  void theScaleOfTheAmountDoesNotChangeTheKeyButItsValueAndSignDo() {
    assertThat(TransactionIds.amount(new BigDecimal("-119.00"))).isEqualTo("-119");
    assertThat(TransactionIds.amount(new BigDecimal("0.50"))).isEqualTo("0.5");
    assertThat(TransactionIds.amount(new BigDecimal("1234.56"))).isEqualTo("1234.56");
    assertThat(TransactionIds.amount(new BigDecimal("0.00"))).isEqualTo("0");
    assertThat(TransactionIds.amount(new BigDecimal("1.2E+3"))).isEqualTo("1200");
    assertThat(TransactionIds.amount(new BigDecimal("12345678.90"))).isEqualTo("12345678.9");
  }

  @Test
  void anotherIbanDateAmountCounterpartyPurposeOrBankIdGivesAnotherKey() {
    String content = key(invoice("1", ""));
    String reference = key(invoice("1", "TX-2026-0001"));

    assertThat(TransactionIds.key(A2, invoice("1", ""))).isNotEqualTo(content);
    assertThat(key(transaction("1", "2026-11-21", "2026-11-20", "-119", "DE87", INVOICE, "")))
        .isNotEqualTo(content);
    assertThat(key(transaction("1", "2026-11-20", "2026-11-21", "-119.00", "", INVOICE, "")))
        .isNotEqualTo(
            key(transaction("1", "2026-11-20", "2026-11-20", "-119.00", "", INVOICE, "")));
    assertThat(key(transaction("1", "2026-11-20", "2026-11-20", "119.00", "", INVOICE, "")))
        .isNotEqualTo(
            key(transaction("1", "2026-11-20", "2026-11-20", "-119.00", "", INVOICE, "")));
    assertThat(
            key(
                transaction(
                    "1",
                    "2026-11-20",
                    "2026-11-20",
                    "-119",
                    "DE87123456780000000003",
                    INVOICE,
                    "")))
        .isNotEqualTo(content);
    assertThat(
            key(
                transaction(
                    "1",
                    "2026-11-20",
                    "2026-11-20",
                    "-119",
                    "DE87123456780000000002",
                    "Rechnung 2026-0043",
                    "")))
        .isNotEqualTo(content);
    assertThat(TransactionIds.key(A2, invoice("1", "TX-2026-0001"))).isNotEqualTo(reference);
    assertThat(key(invoice("1", "TX-2026-0002"))).isNotEqualTo(reference);
    assertThat(key(transaction("1", "2026-11-21", null, "-119", "", "", "TX-2026-0001")))
        .isNotEqualTo(reference);
    assertThat(key(transaction("1", "2026-11-20", null, "-118", "", "", "TX-2026-0001")))
        .isNotEqualTo(reference);
  }

  @Test
  void theBankReferenceKeyIgnoresValueDateCounterpartyAndPurpose() {
    assertThat(key(transaction("9", "2026-11-20", "2026-11-25", "-119", "", "other", "TX-1")))
        .isEqualTo(key(invoice("1", "TX-1")))
        .startsWith("b1-r-");
  }

  @Test
  void placeholderAndBlankBankIdsFallBackToTheContentKey() {
    String content = key(invoice("1", ""));

    for (String placeholder :
        List.of(
            "NONREF",
            "nonref",
            "NOTPROVIDED",
            "NotProvided",
            "NOTAVAILABLE",
            "NOREF",
            " ",
            " NOTPROVIDED ",
            "--",
            "NOT PROVIDED")) {
      assertThat(key(invoice("1", placeholder))).as(placeholder).isEqualTo(content);
    }
    assertThat(key(invoice("1", null))).isEqualTo(content);
    assertThat(content).startsWith("b1-c-");
  }

  @Test
  void aMissingValueDateCountsAsTheBookingDate() {
    assertThat(key(transaction("1", "2026-11-20", null, "-119", "", INVOICE, "")))
        .isEqualTo(key(transaction("1", "2026-11-20", "2026-11-20", "-119", "", INVOICE, "")));
  }

  @Test
  void identicalTransactionsGetRunningNumbersInTheOrderOfTheirSourceIds() {
    String key = key(invoice("1", ""));

    assertThat(ids(List.of(invoice("12", ""), invoice("10", ""), invoice("11", ""))))
        .containsExactly(key + "-3", key + "-1", key + "-2");
  }

  @Test
  void runningNumbersFollowTheSourceIdsAsNumbersNotAsText() {
    String key = key(invoice("1", ""));

    assertThat(ids(List.of(invoice("10", ""), invoice("9", ""), invoice("0100", ""))))
        .containsExactly(key + "-2", key + "-1", key + "-3");
    assertThat(
            List.of("10", "9", "009", "100", "0").stream()
                .sorted(TransactionIds.SOURCE_ID_ORDER)
                .toList())
        .containsExactly("0", "009", "9", "10", "100");
  }

  @Test
  void eachKeyCountsOnItsOwn() {
    SourceTransaction card = transaction("5", "2026-11-21", null, "-4.50", "", "Karte", "");
    SourceTransaction sameCard = transaction("7", "2026-11-21", null, "-4.50", "", "Karte", "");

    List<String> ids =
        ids(List.of(invoice("4", ""), card, invoice("6", ""), sameCard, invoice("8", "TX")));

    assertThat(ids)
        .containsExactly(
            key(invoice("4", "")) + "-1",
            key(card) + "-1",
            key(invoice("4", "")) + "-2",
            key(card) + "-2",
            key(invoice("8", "TX")) + "-1");
  }

  @Test
  void aLaterArrivalOfTheSameDayGetsTheNextNumberAndTheEarlierOnesKeepTheirs() {
    List<SourceTransaction> first = List.of(invoice("20", ""), invoice("21", ""));
    List<SourceTransaction> later = new ArrayList<>(first);
    later.add(invoice("35", ""));

    assertThat(ids(later)).startsWith(ids(first).toArray(String[]::new)).hasSize(3);
    assertThat(ids(later).getLast()).endsWith("-3");
  }

  @Test
  void aSourceWithOtherIdsInAnotherOrderGivesTheSameIdentifiers() {
    List<SourceTransaction> original =
        List.of(
            invoice("101", ""),
            invoice("102", ""),
            transaction("103", "2026-11-21", null, "-4.50", "", "Karte", ""),
            invoice("104", "TX-1"));
    List<SourceTransaction> rebuilt =
        List.of(
            invoice("7", "TX-1"),
            transaction("3", "2026-11-21", null, "-4.50", "", "Karte", ""),
            invoice("5", ""),
            invoice("1", ""));

    assertThat(ids(rebuilt)).containsExactlyInAnyOrderElementsOf(ids(original));
  }

  @Test
  void countsTheTransactionsWithABankIdAndThoseThatShareOneWithOtherContent() {
    SourceTransaction reused = transaction("3", "2026-11-20", null, "-119", "", "other", "TX-1");

    TransactionIds.Assignment assignment =
        TransactionIds.assign(
            A1,
            List.of(
                invoice("1", "TX-1"),
                invoice("2", "TX-1"),
                reused,
                invoice("4", "TX-2"),
                invoice("5", "TX-2"),
                invoice("6", "")));

    assertThat(assignment.withBankId()).isEqualTo(5);
    assertThat(assignment.sharedBankIds()).isEqualTo(3);
    assertThat(assignment.transactionIds())
        .containsExactly(
            key(reused) + "-1",
            key(reused) + "-2",
            key(reused) + "-3",
            key(invoice("4", "TX-2")) + "-1",
            key(invoice("4", "TX-2")) + "-2",
            key(invoice("6", "")) + "-1");
  }

  @Test
  void manyIdenticalTransactionsAllGetTheirOwnNumber() {
    List<SourceTransaction> many =
        IntStream.rangeClosed(1, 120).mapToObj(i -> invoice(Integer.toString(i), "")).toList();

    assertThat(ids(many)).doesNotHaveDuplicates().last().asString().endsWith("-120");
  }

  @Test
  void aMissingDigestIsAnError() {
    assertThatThrownBy(() -> TransactionIds.digest("NO-SUCH-DIGEST"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the Java runtime has no NO-SUCH-DIGEST");
  }
}
