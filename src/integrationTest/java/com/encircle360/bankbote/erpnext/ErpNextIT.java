package com.encircle360.bankbote.erpnext;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.bankbote.account.Iban;
import com.encircle360.bankbote.delivery.AccountReport;
import com.encircle360.bankbote.delivery.Delivery;
import com.encircle360.bankbote.delivery.Route;
import com.encircle360.bankbote.delivery.RunReport;
import com.encircle360.bankbote.integration.ErpNextFixtures;
import com.encircle360.bankbote.integration.ErpNextFixtures.ApiKey;
import com.encircle360.bankbote.integration.ErpNextServer;
import com.encircle360.bankbote.integration.FrappeAdmin;
import com.encircle360.bankbote.integration.ServerLogOnFailure;
import com.encircle360.bankbote.secret.Secret;
import com.encircle360.bankbote.source.SourceAccount;
import com.encircle360.bankbote.source.SourceEntries;
import com.encircle360.bankbote.source.SourceTransaction;
import com.encircle360.bankbote.source.TransactionSource;
import com.encircle360.bankbote.tls.TlsContexts;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Bankbote's ERPNext client and delivery against a real ERPNext 16 with two companies, through a
 * service user whose role has exactly the permissions of {@code docs/betrieb.md}.
 */
@Tag("erpnext")
class ErpNextIT {

  static final String A1 = "DE17123456780000000001";
  static final String A2 = "DE87123456780000000002";
  static final String B1 = "DE60123456780000000003";
  static final String C1 = "DE33123456780000000004";
  static final String COMPANY_A = "Beispiel GmbH";
  static final String COMPANY_B = "Zweite GmbH";
  private static final Clock CLOCK = Clock.system(ZoneId.of("Europe/Berlin"));

  @TempDir static Path dir;
  static ErpNextServer server;
  static FrappeAdmin admin;
  static ApiKey key;
  static String accountA1;
  static String accountA2;
  static String accountB1;
  static String supplierAccount;

  @RegisterExtension
  static final ServerLogOnFailure LOG_ON_FAILURE = new ServerLogOnFailure(() -> server);

  @BeforeAll
  static void start() throws IOException {
    server = ErpNextServer.start(Files.createDirectory(dir.resolve("secrets")));
    admin = server.admin();
    ErpNextFixtures fixtures = new ErpNextFixtures(admin);
    fixtures.setUp(COMPANY_A, "BG");
    fixtures.company(COMPANY_B, "ZG");
    fixtures.bank("Beispielbank");
    accountA1 = fixtures.bankAccount(COMPANY_A, "BG", "Girokonto", "Beispielbank", A1, true);
    accountA2 =
        fixtures.bankAccount(
            COMPANY_A, "BG", "Tagesgeld", "Beispielbank", "DE87 1234 5678 0000 0000 02", true);
    accountB1 = fixtures.bankAccount(COMPANY_B, "ZG", "Geschäftskonto", "Beispielbank", B1, true);
    supplierAccount =
        fixtures.bankAccount(COMPANY_A, "BG", "Lieferantenkonto", "Beispielbank", C1, false);
    key = fixtures.serviceUser("bankbote@example.com");
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  private static ErpNextClient client(int pageLength) {
    try {
      return new ErpNextClient(
          "company-a",
          server.url(),
          Secret.fromFile(Files.writeString(dir.resolve("api-key"), key.key())),
          Secret.fromFile(Files.writeString(dir.resolve("api-secret"), key.secret())),
          TlsContexts.system(),
          Duration.ofSeconds(10),
          Duration.ofSeconds(120),
          pageLength);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static ErpNextClient client() {
    return client(ErpNextClient.DEFAULT_PAGE_LENGTH);
  }

  private static BankTransactionDocument document(String bankAccount, String transactionId) {
    return new BankTransactionDocument(
        LocalDate.of(2026, 11, 25),
        bankAccount,
        "EUR",
        "Rechnung 2026-0042",
        "E2E-42",
        transactionId,
        new BigDecimal("0.00"),
        new BigDecimal("1234.56"),
        "Überweisung",
        "Müller & Söhne GmbH",
        "DE06123456780000000005",
        "");
  }

  private static int status(String method, String path) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(server.url().resolve(path))
              .header("Authorization", "token " + key.key() + ":" + key.secret())
              .header("Accept", "application/json");
      if (method.equals("GET")) {
        request.GET();
      } else {
        request
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString("{\"iban\":\"" + B1 + "\"}"));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }

  @Test
  void listsTheCompanyBankAccountsOfEveryCompanyWithTheNarrowKeyPageByPage() throws Exception {
    List<CompanyBankAccount> accounts = client(2).companyBankAccounts();

    assertThat(accounts)
        .containsExactlyInAnyOrder(
            new CompanyBankAccount(accountA1, A1),
            new CompanyBankAccount(accountA2, "DE87 1234 5678 0000 0000 02"),
            new CompanyBankAccount(accountB1, B1));
  }

  @Test
  void insertsSubmittedInOneRequestWithTheTransactionIdAndTheCompanyOfTheBankAccount()
      throws Exception {
    InsertedTransaction inA = client().insertSubmitted(document(accountA1, "hibiscus:1001"));
    InsertedTransaction inB = client().insertSubmitted(document(accountB1, "hibiscus:1002"));

    assertThat(inA.docstatus()).isEqualTo(1);
    JsonNode stored = admin.get("Bank Transaction", inA.name());
    assertThat(stored.path("docstatus").asInt()).isEqualTo(1);
    assertThat(stored.path("transaction_id").asString()).isEqualTo("hibiscus:1001");
    assertThat(stored.path("company").asString()).isEqualTo(COMPANY_A);
    assertThat(stored.path("bank_account").asString()).isEqualTo(accountA1);
    assertThat(stored.path("date").asString()).isEqualTo("2026-11-25");
    assertThat(stored.path("withdrawal").decimalValue()).isEqualByComparingTo("1234.56");
    assertThat(stored.path("deposit").decimalValue()).isEqualByComparingTo("0");
    assertThat(stored.path("currency").asString()).isEqualTo("EUR");
    assertThat(stored.path("description").asString()).isEqualTo("Rechnung 2026-0042");
    assertThat(stored.path("reference_number").asString()).isEqualTo("E2E-42");
    assertThat(stored.path("transaction_type").asString()).isEqualTo("Überweisung");
    assertThat(stored.path("bank_party_name").asString()).isEqualTo("Müller & Söhne GmbH");
    assertThat(stored.path("bank_party_iban").asString()).isEqualTo("DE06123456780000000005");
    assertThat(admin.get("Bank Transaction", inB.name()).path("company").asString())
        .isEqualTo(COMPANY_B);
  }

  @Test
  void submitsADraftWithPut() throws Exception {
    String draft =
        admin
            .insert(
                "Bank Transaction",
                Map.of(
                    "date", "2026-11-24",
                    "bank_account", accountA1,
                    "currency", "EUR",
                    "deposit", 10,
                    "transaction_id", "hibiscus:2001"))
            .path("name")
            .asString();
    assertThat(admin.get("Bank Transaction", draft).path("docstatus").asInt()).isZero();

    client().submit(draft);

    assertThat(admin.get("Bank Transaction", draft).path("docstatus").asInt()).isEqualTo(1);
  }

  @Test
  void findsExistingTransactionIdsOfOneBankAccountInChunksCancelledIncluded() throws Exception {
    ErpNextClient client = client();
    InsertedTransaction kept = client.insertSubmitted(document(accountA2, "hibiscus:3001"));
    InsertedTransaction cancelled = client.insertSubmitted(document(accountA2, "hibiscus:3002"));
    client.insertSubmitted(document(accountA1, "hibiscus:3003"));
    admin.call(
        "frappe.client.cancel", Map.of("doctype", "Bank Transaction", "name", cancelled.name()));
    List<String> ids =
        new ArrayList<>(IntStream.range(0, 118).mapToObj(i -> "hibiscus:39" + i).toList());
    ids.addAll(List.of("hibiscus:3001", "hibiscus:3002", "hibiscus:3003"));

    List<ExistingTransaction> existing = client.existing(accountA2, ids);

    assertThat(existing)
        .containsExactlyInAnyOrder(
            new ExistingTransaction(kept.name(), "hibiscus:3001", 1),
            new ExistingTransaction(cancelled.name(), "hibiscus:3002", 2));
  }

  /** A source with one account and the given transactions. */
  private static TransactionSource source(String iban, List<SourceTransaction> transactions) {
    return new TransactionSource() {
      @Override
      public List<SourceAccount> accounts() {
        return List.of(new SourceAccount("7", iban, "EUR"));
      }

      @Override
      public SourceEntries transactions(SourceAccount account, LocalDate from) {
        return new SourceEntries(transactions, List.of());
      }
    };
  }

  private static SourceTransaction booked(String id, String amount, String purpose, String name) {
    return new SourceTransaction(
        id,
        LocalDate.now(CLOCK).minusDays(1),
        new BigDecimal(amount),
        false,
        purpose,
        name,
        "DE06123456780000000005",
        "x".repeat(140),
        "r".repeat(140));
  }

  @Test
  void deliversOnceKeepsZeroAmountsLongFieldsAndUmlautsAndCountsCancelledAsExisting()
      throws Exception {
    String longPurpose =
        String.join(
            "\n",
            IntStream.range(0, 60)
                .mapToObj(i -> "Zeile " + i + ": Grüße an Müller, Größe ÄÖÜ äöü ß € „Zitat“")
                .toList());
    String longName = "Ä".repeat(140);
    List<SourceTransaction> transactions =
        List.of(
            booked("5001", "-12.34", longPurpose, longName),
            booked("5002", "0.00", "Nullbuchung", "Bank"),
            booked("5003", "99999999.99", "Großbetrag", "Großkunde"));
    Delivery delivery =
        new Delivery(
            source(A1, transactions),
            "hibiscus",
            List.of(new Route(Iban.parse(A1), "company-a")),
            List.of(client()),
            CLOCK);

    RunReport first = delivery.run(LocalDate.now(CLOCK).minusDays(14));
    List<ExistingTransaction> delivered =
        client().existing(accountA1, List.of("hibiscus:5001", "hibiscus:5002", "hibiscus:5003"));
    String cancel =
        delivered.stream()
            .filter(t -> t.transactionId().equals("hibiscus:5002"))
            .findFirst()
            .orElseThrow()
            .name();
    admin.call("frappe.client.cancel", Map.of("doctype", "Bank Transaction", "name", cancel));
    RunReport second = delivery.run(LocalDate.now(CLOCK).minusDays(14));

    assertThat(first.targets().getFirst().accounts().getFirst())
        .isEqualTo(new AccountReport("DE17…0001", 3, 3, 0, 0, 0, null));
    assertThat(second.targets().getFirst().accounts().getFirst())
        .isEqualTo(new AccountReport("DE17…0001", 3, 0, 3, 0, 0, null));
    assertThat(delivered).hasSize(3).allSatisfy(t -> assertThat(t.docstatus()).isEqualTo(1));
    JsonNode stored =
        admin.get(
            "Bank Transaction",
            delivered.stream()
                .filter(t -> t.transactionId().equals("hibiscus:5001"))
                .findFirst()
                .orElseThrow()
                .name());
    assertThat(stored.path("description").asString()).isEqualTo(longPurpose);
    assertThat(stored.path("bank_party_name").asString()).isEqualTo(longName);
    assertThat(stored.path("transaction_type").asString()).hasSize(140);
    assertThat(stored.path("reference_number").asString()).hasSize(140);
    assertThat(
            client()
                .existing(accountA1, List.of("hibiscus:5001", "hibiscus:5002", "hibiscus:5003")))
        .hasSize(3);
  }

  @Test
  void theNarrowKeyReadsNothingElseAndChangesNothingElse() throws Exception {
    assertThat(status("GET", FrappeAdmin.resource("Sales Invoice"))).isEqualTo(403);
    assertThat(status("GET", FrappeAdmin.resource("User"))).isEqualTo(403);
    assertThat(status("GET", FrappeAdmin.resource("GL Entry"))).isEqualTo(403);
    assertThat(status("GET", FrappeAdmin.resource("Customer"))).isEqualTo(403);
    assertThat(status("GET", FrappeAdmin.resource("Account"))).isEqualTo(403);
    assertThat(status("PUT", FrappeAdmin.resource("Bank Account", accountA1))).isEqualTo(403);
    assertThat(status("DELETE", FrappeAdmin.resource("Bank Account", accountA1))).isEqualTo(403);
    assertThat(status("POST", FrappeAdmin.resource("Bank Account"))).isEqualTo(403);
    InsertedTransaction mine = client().insertSubmitted(document(accountA1, "hibiscus:6001"));
    assertThat(status("DELETE", FrappeAdmin.resource("Bank Transaction", mine.name())))
        .isEqualTo(403);
    assertThat(
            status(
                "POST",
                "/api/method/frappe.client.cancel?doctype=Bank%20Transaction&name=" + mine.name()))
        .isEqualTo(403);
  }
}
