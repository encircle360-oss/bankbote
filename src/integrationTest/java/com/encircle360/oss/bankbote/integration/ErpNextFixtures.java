package com.encircle360.oss.bankbote.integration;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The records the ERPNext suite needs, made through the REST API as the Administrator: the setup
 * wizard with a first company, further companies, a bank, bank accounts with ledger accounts, and
 * the service user of Bankbote with exactly the role that {@code docs/betrieb.md} names. Every
 * value is invented.
 */
public final class ErpNextFixtures {

  /** The role of the service user and its permissions, as {@code docs/betrieb.md} names them. */
  public static final String ROLE = "Bankbote";

  public static final Map<String, List<String>> PERMISSIONS =
      Map.of(
          "Bank Transaction", List.of("read", "create", "submit"), "Bank Account", List.of("read"));

  private static final List<String> ALL_RIGHTS =
      List.of(
          "read", "write", "create", "submit", "cancel", "amend", "delete", "report", "export",
          "import", "print", "email", "share");

  /** The API key and secret of a user. */
  public record ApiKey(String key, String secret) {}

  private final FrappeAdmin admin;

  public ErpNextFixtures(FrappeAdmin admin) {
    this.admin = admin;
  }

  /** Completes the setup wizard for Germany with a first company and its chart of accounts. */
  public void setUp(String company, String abbreviation) {
    admin.callForm(
        "frappe.desk.page.setup_wizard.setup_wizard.setup_complete",
        Map.of(
            "args",
            "{\"language\":\"English\",\"country\":\"Germany\",\"timezone\":\"Europe/Berlin\","
                + "\"currency\":\"EUR\",\"full_name\":\"Test Administrator\","
                + "\"email\":\"admin@example.com\",\"company_name\":\""
                + company
                + "\",\"company_abbr\":\""
                + abbreviation
                + "\",\"chart_of_accounts\":\"Standard\",\"fy_start_date\":\"2026-01-01\","
                + "\"fy_end_date\":\"2026-12-31\",\"setup_demo\":0}"));
  }

  /** Another company with the standard chart of accounts. */
  public void company(String name, String abbreviation) {
    admin.insert(
        "Company",
        Map.of(
            "company_name", name,
            "abbr", abbreviation,
            "default_currency", "EUR",
            "country", "Germany",
            "chart_of_accounts", "Standard"));
  }

  public void bank(String name) {
    admin.insert("Bank", Map.of("bank_name", name));
  }

  /**
   * A ledger account of type Bank and a Bank Account on it; gives the name of the Bank Account.
   * {@code iban} is stored as given, also with spaces.
   */
  public String bankAccount(
      String company,
      String abbreviation,
      String name,
      String bank,
      String iban,
      boolean ofCompany) {
    String ledger =
        admin
            .insert(
                "Account",
                Map.of(
                    "account_name",
                    name,
                    "parent_account",
                    "Bank Accounts - " + abbreviation,
                    "account_type",
                    "Bank",
                    "company",
                    company,
                    "account_currency",
                    "EUR"))
            .path("name")
            .asString();
    return admin
        .insert(
            "Bank Account",
            Map.of(
                "account_name", name,
                "bank", bank,
                "account", ledger,
                "is_company_account", ofCompany ? 1 : 0,
                "company", company,
                "iban", iban))
        .path("name")
        .asString();
  }

  /**
   * The role {@link #ROLE} with exactly {@link #PERMISSIONS}, through the permission manager as an
   * administrator would set it up, and a user with only this role and a generated API key.
   */
  public ApiKey serviceUser(String email) {
    admin.insert("Role", Map.of("role_name", ROLE, "desk_access", 1));
    PERMISSIONS.forEach((doctype, rights) -> {
      admin.call(
          "frappe.core.page.permission_manager.permission_manager.add",
          Map.of("parent", doctype, "role", ROLE, "permlevel", 0));
      for (String right : ALL_RIGHTS) {
        admin.call(
            "frappe.core.page.permission_manager.permission_manager.update",
            Map.of(
                "doctype", doctype,
                "role", ROLE,
                "permlevel", 0,
                "ptype", right,
                "value", rights.contains(right) ? 1 : 0));
      }
    });
    admin.insert(
        "User",
        Map.of(
            "email",
            email,
            "first_name",
            "Bankbote",
            "send_welcome_email",
            0,
            "user_type",
            "System User",
            "roles",
            List.of(Map.of("role", ROLE))));
    JsonNode keys =
        admin.call("frappe.core.doctype.user.user.generate_keys", Map.of("user", email));
    return new ApiKey(keys.path("api_key").asString(), keys.path("api_secret").asString());
  }
}
