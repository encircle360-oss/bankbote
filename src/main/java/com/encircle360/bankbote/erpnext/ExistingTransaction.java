package com.encircle360.bankbote.erpnext;

/**
 * A Bank Transaction that ERPNext already holds under a transaction id of Bankbote.
 *
 * @param docstatus 0 for a draft, 1 for submitted, 2 for cancelled
 */
public record ExistingTransaction(String name, String transactionId, int docstatus) {}
