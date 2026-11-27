package com.encircle360.bankbote.delivery;

/**
 * An account of the source that no target is configured for. Its transactions go nowhere.
 *
 * @param iban the masked IBAN, or {@code none} when the source holds no valid IBAN
 * @param sourceId the id of the account at the source
 * @param found the transactions the source holds for it in the window, -1 when they cannot be read
 */
public record UnmappedAccount(String iban, String sourceId, int found) {}
