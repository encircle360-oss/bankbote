package com.encircle360.bankbote.erpnext;

/** A Bank Account of ERPNext that belongs to the company, with the IBAN as ERPNext holds it. */
public record CompanyBankAccount(String name, String iban) {

  @Override
  public String toString() {
    return "CompanyBankAccount[name=" + name + "]";
  }
}
