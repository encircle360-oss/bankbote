package com.encircle360.bankbote.delivery;

import com.encircle360.bankbote.account.Iban;

/** The one target an account of the source is delivered to. */
public record Route(Iban iban, String target) {}
