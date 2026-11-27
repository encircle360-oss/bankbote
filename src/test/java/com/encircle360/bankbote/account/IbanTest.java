package com.encircle360.bankbote.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

  @Test
  void normalisesSpacesAndCase() {
    Iban iban = Iban.parse(" de17 1234 5678 0000 0000 01 ");

    assertThat(iban.value()).isEqualTo("DE17123456780000000001");
  }

  @Test
  void acceptsOtherCountries() {
    assertThat(Iban.parse("AT701234500000000001").value()).isEqualTo("AT701234500000000001");
  }

  @Test
  void rejectsAWrongCheckSum() {
    assertThatThrownBy(() -> Iban.parse("DE18123456780000000001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not a valid IBAN: check digits do not match")
        .hasMessageNotContaining("1234");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "DE",
        "1E17123456780000000001",
        "DE1X123456780000000001",
        "DE17-23456780000000001"
      })
  void rejectsMalformedValuesWithoutRepeatingThem(String value) {
    assertThatThrownBy(() -> Iban.parse(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("not a valid IBAN");
  }

  @Test
  void rejectsMoreThanThirtyFourCharacters() {
    assertThatThrownBy(() -> Iban.parse("DE17" + "1".repeat(31)))
        .hasMessage("not a valid IBAN: length must be between 15 and 34 characters");
  }

  @Test
  void tryParseGivesEmptyForInvalidValues() {
    assertThat(Iban.tryParse("DE18123456780000000001")).isEmpty();
    assertThat(Iban.tryParse(null)).isEmpty();
    assertThat(Iban.tryParse("DE17123456780000000001"))
        .isEqualTo(Optional.of(Iban.parse("DE17123456780000000001")));
  }

  @Test
  void masksAllButCountryCheckDigitsAndTheLastFourCharacters() {
    Iban iban = Iban.parse("DE17123456780000000001");

    assertThat(iban.masked()).isEqualTo("DE17…0001");
    assertThat(iban.toString()).isEqualTo("DE17…0001");
  }

  @Test
  void comparesByValue() {
    assertThat(Iban.parse("DE17 1234 5678 0000 0000 01"))
        .isEqualTo(Iban.parse("DE17123456780000000001"))
        .hasSameHashCodeAs(Iban.parse("DE17123456780000000001"))
        .isNotEqualTo(Iban.parse("DE87123456780000000002"))
        .isNotEqualTo("DE17123456780000000001");
  }
}
