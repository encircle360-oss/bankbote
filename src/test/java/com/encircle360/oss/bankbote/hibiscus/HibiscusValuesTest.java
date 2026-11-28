package com.encircle360.oss.bankbote.hibiscus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HibiscusValuesTest {

  @ParameterizedTest
  @CsvSource({
    "1234.56, EUR, 1234.56",
    "-1234.56, EUR, -1234.56",
    "-0.5, EUR, -0.50",
    "12.0, EUR, 12.00",
    "-0.0, EUR, 0.00",
    "1.2345678E7, EUR, 12345678.00",
    "-9.87654321E8, EUR, -987654321.00",
    "1.5E-1, EUR, 0.15",
    "100.0, JPY, 100",
    "1.234, KWD, 1.234",
    "' 7.1 ', EUR, 7.10"
  })
  void readsTheAmountAsJavaPrintsADoubleExactlyAtTheScaleOfTheCurrency(
      String text, String currency, String expected) {
    BigDecimal amount = HibiscusValues.amount(text, currency);

    assertThat(amount).isEqualByComparingTo(expected);
    assertThat(amount.scale()).isEqualTo(new BigDecimal(expected).scale());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.234,56", "1,234.56", "1.234.567", "-1.234,56", "1 234.56", "1234,56"})
  void refusesAmountsWithThousandsSeparatorsOrADecimalComma(String text) {
    assertThatThrownBy(() -> HibiscusValues.amount(text, "EUR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount is not a decimal number");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "abc"})
  void refusesMissingOrNonNumericAmounts(String text) {
    assertThatThrownBy(() -> HibiscusValues.amount(text, "EUR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount is not a decimal number");
  }

  @Test
  void refusesAmountsWithMoreDecimalsThanTheCurrency() {
    assertThatThrownBy(() -> HibiscusValues.amount("12.345", "EUR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount has more decimals than the currency EUR");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"XYZ", "euro"})
  void currencyMustBeAnIsoCode(String currency) {
    assertThatThrownBy(() -> HibiscusValues.fractionDigits(currency))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an ISO 4217 currency code with fixed decimals");
  }

  @Test
  void currencyWithoutFixedDecimalsIsRefused() {
    assertThatThrownBy(() -> HibiscusValues.fractionDigits("XAU"))
        .hasMessage("not an ISO 4217 currency code with fixed decimals");
  }

  @ParameterizedTest
  @CsvSource({
    "2026-11-27, 2026-11-27",
    "2026-01-02 00:00:00.0, 2026-01-02",
    "2026-01-02 13:14:15, 2026-01-02",
    "27.11.2026, 2026-11-27",
    "' 2026-11-27 ', 2026-11-27"
  })
  void readsDates(String text, LocalDate expected) {
    assertThat(HibiscusValues.date(text)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"Fri Nov 27 00:00:00 CET 2026", "2026-13-01", "27.11.26", "2026-11-27T10:00"})
  void refusesOtherDates(String text) {
    assertThatThrownBy(() -> HibiscusValues.date(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("date is neither yyyy-MM-dd nor dd.MM.yyyy");
  }

  @ParameterizedTest
  @CsvSource(
      value = {"NULL, false", "'', false", "0, false", "1, false", "2, true", "3, true", "6, true"},
      nullValues = "NULL")
  void pendingWhenTheFlagNotBookedIsSet(String flags, boolean pending) {
    assertThat(HibiscusValues.pending(flags)).isEqualTo(pending);
  }

  @ParameterizedTest
  @ValueSource(strings = {"two", "-2", "2.0"})
  void refusesFlagsThatAreNoNumber(String flags) {
    assertThatThrownBy(() -> HibiscusValues.pending(flags))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("flags are not a number");
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "E2E-4711, REF-1, E2E-4711",
        "NOTPROVIDED, REF-1, REF-1",
        "'', REF-1, REF-1",
        "NULL, NONREF, ''",
        "' notprovided ', ' ', ''"
      },
      nullValues = "NULL")
  void referenceIsTheEndToEndIdOrTheCustomerReferenceWithoutSepaPlaceholders(
      String endToEndId, String customerReference, String expected) {
    assertThat(HibiscusValues.reference(endToEndId, customerReference)).isEqualTo(expected);
  }

  @Test
  void joinsTheLinesOfThePurposeAndTheNames() {
    assertThat(HibiscusValues.joinLines(" Invoice 1 ", null, "", "line 3\nline 4 "))
        .isEqualTo("Invoice 1\nline 3\nline 4");
    assertThat(HibiscusValues.joinWords("Example", null, " Trading ")).isEqualTo("Example Trading");
    assertThat(HibiscusValues.joinWords()).isEmpty();
  }

  @Test
  void dropsBlankLinesWithinAPart() {
    assertThat(HibiscusValues.joinLines("one\n\n  \ntwo")).isEqualTo("one\ntwo");
  }

  @Test
  void readsAnOptionalValueDate() {
    assertThat(HibiscusValues.optionalValueDate("2026-11-26"))
        .isEqualTo(java.time.LocalDate.of(2026, 11, 26));
    assertThat(HibiscusValues.optionalValueDate(null)).isNull();
    assertThat(HibiscusValues.optionalValueDate(" ")).isNull();
    assertThatThrownBy(() -> HibiscusValues.optionalValueDate("26/11/2026"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("value date is neither yyyy-MM-dd nor dd.MM.yyyy");
  }
}
