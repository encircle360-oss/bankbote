package com.encircle360.bankbote.delivery;

/**
 * What a run did with one account. It holds counts and the masked IBAN, never a purpose text or a
 * name.
 *
 * @param iban the masked IBAN
 * @param found the transactions the source holds for the account in the window
 * @param delivered the transactions inserted and submitted in ERPNext in this run
 * @param existing the transactions ERPNext already held, submitted or cancelled
 * @param pending the transactions the bank has not booked yet, never delivered
 * @param failed the transactions that could not be read or delivered
 * @param error the last error, or null
 */
public record AccountReport(
    String iban, int found, int delivered, int existing, int pending, int failed, String error) {}
