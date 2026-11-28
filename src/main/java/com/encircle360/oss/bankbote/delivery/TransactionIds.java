package com.encircle360.oss.bankbote.delivery;

import com.encircle360.oss.bankbote.account.Iban;
import com.encircle360.oss.bankbote.source.SourceTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code transaction_id} of a booked transaction, derived from the transaction itself and not
 * from the database of the source, so that a rebuilt source or another source with the same bank
 * data gives the same identifier. The canonical form is specified in {@code docs/architektur.md};
 * whatever changes it changes every identifier and needs a new scheme.
 *
 * <p>An identifier is {@code b1-<kind>-<hash>-<number>}: kind {@code r} when the bank gave the
 * transaction an id of its own, {@code c} otherwise; the first 128 bits of a SHA-256 hash in lower
 * case hex; and the rank of the transaction among the booked transactions of the account with the
 * same key, in the order of the source, starting at 1.
 */
public final class TransactionIds {

  static final String SCHEME = "b1";

  /** Cannot occur in a field after canonicalisation. */
  private static final String SEPARATOR = "|";

  /** What banks and SEPA put where there is no id, compared after canonicalisation. */
  private static final Set<String> NO_BANK_ID =
      Set.of("NONREF", "NOTPROVIDED", "NOTAVAILABLE", "NOREF");

  private static final String SHA_256 = "SHA-256";

  private static final int HASH_BYTES = 16;

  /** Ids of the source as numbers: shorter digits first once leading zeros are dropped. */
  static final Comparator<String> SOURCE_ID_ORDER =
      Comparator.comparingInt((String id) -> withoutLeadingZeros(id).length())
          .thenComparing(TransactionIds::withoutLeadingZeros)
          .thenComparing(Comparator.naturalOrder());

  private TransactionIds() {}

  /**
   * The identifiers of the booked transactions of one account, in the order given.
   *
   * @param transactionIds the identifier of each transaction, at the same position
   * @param withBankId how many of the transactions carry an id of the bank
   * @param sharedBankIds how many share an id of the bank with a transaction of other content
   */
  record Assignment(List<String> transactionIds, int withBankId, int sharedBankIds) {}

  /**
   * Gives every booked transaction of an account its identifier. The running number needs every
   * booked transaction of the account on the booking dates involved, and no pending one.
   */
  static Assignment assign(Iban account, List<SourceTransaction> booked) {
    List<SourceTransaction> inSourceOrder = new ArrayList<>(booked);
    inSourceOrder.sort(Comparator.comparing(SourceTransaction::id, SOURCE_ID_ORDER));
    Map<SourceTransaction, String> ids = new IdentityHashMap<>();
    Map<String, Integer> ranks = new HashMap<>();
    Map<String, Set<String>> contentsByBankKey = new HashMap<>();
    Map<String, Integer> sizesByBankKey = new HashMap<>();
    int withBankId = 0;
    for (SourceTransaction transaction : inSourceOrder) {
      String key = key(account, transaction);
      int rank = ranks.merge(key, 1, Integer::sum);
      ids.put(transaction, key + "-" + rank);
      if (key.startsWith(SCHEME + "-r-")) {
        withBankId++;
        contentsByBankKey
            .computeIfAbsent(key, k -> new HashSet<>())
            .add(contentKey(account, transaction));
        sizesByBankKey.merge(key, 1, Integer::sum);
      }
    }
    int shared =
        contentsByBankKey.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .mapToInt(entry -> sizesByBankKey.get(entry.getKey()))
            .sum();
    return new Assignment(booked.stream().map(ids::get).toList(), withBankId, shared);
  }

  /** The identifier of a booked transaction that has the given running number. */
  public static String transactionId(Iban account, SourceTransaction transaction, int number) {
    return key(account, transaction) + "-" + number;
  }

  /** The identifier without its running number: the bank reference key or the content key. */
  static String key(Iban account, SourceTransaction transaction) {
    String bankId = canonicalBankId(transaction.bankId());
    if (bankId.isEmpty()) {
      return contentKey(account, transaction);
    }
    return hashed(
        "r",
        account.value(),
        date(transaction.bookingDate()),
        amount(transaction.amount()),
        bankId);
  }

  private static String contentKey(Iban account, SourceTransaction transaction) {
    LocalDate valueDate =
        transaction.valueDate() == null ? transaction.bookingDate() : transaction.valueDate();
    return hashed(
        "c",
        account.value(),
        date(transaction.bookingDate()),
        date(valueDate),
        amount(transaction.amount()),
        text(transaction.counterpartyAccount()),
        text(transaction.purpose()));
  }

  /** The id of the bank in canonical form, empty when there is none or only a placeholder. */
  static String canonicalBankId(String bankId) {
    String canonical = text(bankId);
    return NO_BANK_ID.contains(canonical) ? "" : canonical;
  }

  /** NFKC, upper case without regard to a locale, and nothing but letters and decimal digits. */
  static String text(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder canonical = new StringBuilder(value.length());
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toUpperCase(Locale.ROOT)
        .codePoints()
        .filter(Character::isLetterOrDigit)
        .forEach(canonical::appendCodePoint);
    return canonical.toString();
  }

  /** Signed, without trailing zeros and without exponent, so the scale of the source is lost. */
  static String amount(BigDecimal amount) {
    return amount.stripTrailingZeros().toPlainString();
  }

  private static String date(LocalDate date) {
    return date.toString();
  }

  /** The canonical string that is hashed, as specified in {@code docs/architektur.md}. */
  static String canonical(String kind, String... fields) {
    return SCHEME + SEPARATOR + kind + SEPARATOR + String.join(SEPARATOR, fields);
  }

  private static String hashed(String kind, String... fields) {
    byte[] hash = digest(SHA_256).digest(canonical(kind, fields).getBytes(StandardCharsets.UTF_8));
    return SCHEME + "-" + kind + "-" + HexFormat.of().formatHex(hash, 0, HASH_BYTES);
  }

  static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("the Java runtime has no " + algorithm, e);
    }
  }

  private static String withoutLeadingZeros(String id) {
    int start = 0;
    while (start < id.length() && id.charAt(start) == '0') {
      start++;
    }
    return id.substring(start);
  }
}
