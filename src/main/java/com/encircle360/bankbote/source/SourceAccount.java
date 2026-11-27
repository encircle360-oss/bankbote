package com.encircle360.bankbote.source;

import com.encircle360.bankbote.account.Iban;
import java.util.Optional;

/**
 * An account of the source.
 *
 * @param id the id of the account at the source
 * @param iban the IBAN as the source holds it, possibly empty or not an IBAN
 * @param currency the ISO 4217 code of the account currency, possibly empty
 */
public record SourceAccount(String id, String iban, String currency) {

  /** The IBAN, when the source holds a valid one. */
  public Optional<Iban> validIban() {
    return Iban.tryParse(iban);
  }

  @Override
  public String toString() {
    return "SourceAccount[id=" + id + ", iban=" + validIban().map(Iban::masked).orElse("none") + "]";
  }
}
