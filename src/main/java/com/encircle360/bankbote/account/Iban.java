package com.encircle360.bankbote.account;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An International Bank Account Number in its electronic form, checked by ISO 13616 (mod 97).
 *
 * <p>{@link #toString()} gives the masked form, so an IBAN that reaches a log message shows only
 * the country, the check digits and the last four characters. Error messages never repeat the
 * value.
 */
public final class Iban {

  private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]+");
  private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);
  private static final int MIN_LENGTH = 15;
  private static final int MAX_LENGTH = 34;

  private final String value;

  private Iban(String value) {
    this.value = value;
  }

  /** Parses an IBAN, ignoring spaces and case. */
  public static Iban parse(String text) {
    if (text == null) {
      throw invalid("no value");
    }
    String compact = text.replace(" ", "").toUpperCase(Locale.ROOT);
    if (compact.length() < MIN_LENGTH || compact.length() > MAX_LENGTH) {
      throw invalid("length must be between 15 and 34 characters");
    }
    if (!SHAPE.matcher(compact).matches()) {
      throw invalid("country code, check digits or characters malformed");
    }
    String rearranged = compact.substring(4) + compact.substring(0, 4);
    StringBuilder digits = new StringBuilder(rearranged.length() * 2);
    for (char c : rearranged.toCharArray()) {
      digits.append(Character.digit(c, 36));
    }
    if (!new BigInteger(digits.toString()).mod(NINETY_SEVEN).equals(BigInteger.ONE)) {
      throw invalid("check digits do not match");
    }
    return new Iban(compact);
  }

  /** Parses an IBAN or gives nothing when the text is not one. */
  public static Optional<Iban> tryParse(String text) {
    try {
      return Optional.of(parse(text));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static IllegalArgumentException invalid(String reason) {
    return new IllegalArgumentException("not a valid IBAN: " + reason);
  }

  /** The full IBAN, for requests to ERPNext. Never for logs. */
  public String value() {
    return value;
  }

  /** Country code, check digits and the last four characters, such as {@code DE17…0001}. */
  public String masked() {
    return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Iban iban && iban.value.equals(value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return masked();
  }
}
