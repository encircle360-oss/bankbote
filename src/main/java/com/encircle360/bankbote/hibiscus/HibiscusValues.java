package com.encircle360.bankbote.hibiscus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the values of the JSON beans of Hibiscus Server.
 *
 * <p>The beans write every column with {@code toString()} of its Java value ({@code JsonUtil} and
 * {@code BeanUtil} of hibiscus.server and datasource): an amount as {@link
 * Double#toString(double)}, such as {@code -1234.56} or {@code 1.2345678E7}, never in the format of
 * a locale and never with thousands separators; a date column as {@code java.sql.Date}, {@code
 * yyyy-MM-dd}. Anything else is refused rather than guessed.
 */
final class HibiscusValues {

  private static final Pattern DOUBLE = Pattern.compile("-?[0-9]+(\\.[0-9]+)?([eE]-?[0-9]+)?");
  private static final Pattern ISO_DATE =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}( [0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?)?");
  private static final Pattern GERMAN_DATE = Pattern.compile("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}");
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter GERMAN =
      DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern DIGITS = Pattern.compile("[0-9]{1,9}");

  /** {@code Umsatz.FLAG_NOTBOOKED} of Hibiscus: the bank has not booked the transaction yet. */
  static final int FLAG_NOT_BOOKED = 1 << 1;

  /** What SEPA puts where the payer gave no reference. */
  private static final Set<String> NO_REFERENCE = Set.of("NOTPROVIDED", "NONREF");

  private HibiscusValues() {}

  /** The amount, exact at the number of decimals of the currency. */
  static BigDecimal amount(String text, String currency) {
    String value = text == null ? "" : text.strip();
    if (!DOUBLE.matcher(value).matches()) {
      throw new IllegalArgumentException("amount is not a decimal number");
    }
    try {
      return new BigDecimal(value).setScale(fractionDigits(currency), RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("amount has more decimals than the currency " + currency);
    }
  }

  /** The decimals of an ISO 4217 currency. */
  static int fractionDigits(String currency) {
    if (currency != null) {
      try {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (digits >= 0) {
          return digits;
        }
      } catch (IllegalArgumentException e) {
        // falls through to the refusal below
      }
    }
    throw new IllegalArgumentException("not an ISO 4217 currency code with fixed decimals");
  }

  /** A date as {@code yyyy-MM-dd}, optionally with a time of day, or as {@code dd.MM.yyyy}. */
  static LocalDate date(String text) {
    String value = text == null ? "" : text.strip();
    try {
      if (ISO_DATE.matcher(value).matches()) {
        return LocalDate.parse(value.substring(0, 10), ISO);
      }
      if (GERMAN_DATE.matcher(value).matches()) {
        return LocalDate.parse(value, GERMAN);
      }
    } catch (DateTimeParseException e) {
      // falls through to the refusal below
    }
    throw new IllegalArgumentException("date is neither yyyy-MM-dd nor dd.MM.yyyy");
  }

  /**
   * Whether a transaction is pending. Hibiscus stores the flags as a number and leaves them out of
   * the JSON when the column is empty; a value that is no number is refused, so a transaction whose
   * state is unclear is never taken for booked.
   */
  static boolean pending(String flags) {
    if (flags == null || flags.isBlank()) {
      return false;
    }
    if (!DIGITS.matcher(flags.strip()).matches()) {
      throw new IllegalArgumentException("flags are not a number");
    }
    return (Integer.parseInt(flags.strip()) & FLAG_NOT_BOOKED) != 0;
  }

  /**
   * The reference that identifies a payment: the SEPA end-to-end id the payer gave, otherwise the
   * customer reference of the bank; the placeholders SEPA uses for none count as none.
   */
  static String reference(String endToEndId, String customerReference) {
    return Stream.of(endToEndId, customerReference)
        .filter(Objects::nonNull)
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .filter(value -> !NO_REFERENCE.contains(value.toUpperCase(Locale.ROOT)))
        .findFirst()
        .orElse("");
  }

  /** The non-blank lines of the parts, each stripped, one per line. */
  static String joinLines(String... parts) {
    List<String> lines = new ArrayList<>();
    for (String part : parts) {
      if (part != null) {
        part.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(lines::add);
      }
    }
    return String.join("\n", lines);
  }

  /** The non-blank parts, each stripped, separated by a space. */
  static String joinWords(String... parts) {
    return Stream.of(parts)
        .filter(Objects::nonNull)
        .map(String::strip)
        .filter(part -> !part.isEmpty())
        .collect(Collectors.joining(" "));
  }
}
