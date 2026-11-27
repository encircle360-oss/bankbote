package com.encircle360.bankbote.erpnext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.bankbote.erpnext.ErpNextException.Kind;
import com.encircle360.bankbote.secret.Secret;
import com.encircle360.bankbote.testing.FakeErpNext;
import com.encircle360.bankbote.testing.FakeHttpServer;
import com.encircle360.bankbote.testing.FakeHttpServer.Response;
import com.encircle360.bankbote.tls.TlsContexts;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ErpNextClientTest {

  @TempDir Path dir;
  private FakeErpNext erp;
  private ErpNextClient client;

  @BeforeEach
  void start() throws IOException {
    erp = new FakeErpNext("key-a", "secret-a");
    client = client(erp.server().uri(), Duration.ofSeconds(5));
  }

  private ErpNextClient client(URI uri, Duration timeout) throws IOException {
    return client(uri, timeout, ErpNextClient.DEFAULT_PAGE_LENGTH);
  }

  private ErpNextClient client(URI uri, Duration timeout, int pageLength) throws IOException {
    return new ErpNextClient(
        "company-a",
        uri,
        Secret.fromFile(Files.writeString(dir.resolve("key"), "key-a")),
        Secret.fromFile(Files.writeString(dir.resolve("secret"), "secret-a")),
        TlsContexts.system(),
        Duration.ofSeconds(5),
        timeout,
        pageLength);
  }

  @AfterEach
  void stop() {
    erp.close();
  }

  private static BankTransactionDocument document(String transactionId) {
    return new BankTransactionDocument(
        LocalDate.of(2026, 11, 25),
        "Main account - EX",
        "EUR",
        "Invoice 2026-0042",
        "E2E-1",
        transactionId,
        BigDecimal.ZERO.setScale(2),
        new BigDecimal("1234.56"),
        "SEPA transfer",
        "Example Trading GmbH",
        "DE87123456780000000002",
        "");
  }

  @Test
  void listsTheCompanyBankAccountsPageByPageWithTheToken() throws Exception {
    client = client(erp.server().uri(), Duration.ofSeconds(5), 2);
    IntStream.range(0, 5).forEach(i -> erp.bankAccount("Account " + i, "DE17 1234", true));
    erp.bankAccount("Supplier account", "DE87123456780000000002", false);

    List<CompanyBankAccount> accounts = client.companyBankAccounts();

    assertThat(accounts)
        .hasSize(5)
        .first()
        .isEqualTo(new CompanyBankAccount("Account 0", "DE17 1234"));
    assertThat(erp.requests())
        .allSatisfy(request ->
            assertThat(request.header("Authorization")).isEqualTo("token key-a:secret-a"));
    assertThat(erp.requests()).hasSize(3);
    assertThat(erp.requests().getFirst().uri().getQuery())
        .contains("filters=[[\"is_company_account\",\"=\",1]]")
        .contains("fields=[\"name\",\"iban\"]")
        .contains("limit_start=0");
  }

  @Test
  void findsExistingTransactionsOfTheBankAccountInChunks() throws ErpNextException {
    erp.existing("hibiscus:1", "Main account - EX", 1)
        .existing("hibiscus:2", "Main account - EX", 0)
        .existing("hibiscus:1", "Other account - EX", 1)
        .existing("hibiscus:99", "Main account - EX", 2);
    List<String> ids = IntStream.rangeClosed(1, 120).mapToObj(i -> "hibiscus:" + i).toList();

    List<ExistingTransaction> existing = client.existing("Main account - EX", ids);

    assertThat(existing)
        .extracting(ExistingTransaction::transactionId, ExistingTransaction::docstatus)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("hibiscus:1", 1),
            org.assertj.core.groups.Tuple.tuple("hibiscus:2", 0),
            org.assertj.core.groups.Tuple.tuple("hibiscus:99", 2));
    assertThat(erp.requests()).hasSize(3);
    assertThat(client.existing("Main account - EX", List.of())).isEmpty();
  }

  @Test
  void insertsTheTransactionSubmittedAndChecksTheAnswer() throws ErpNextException {
    InsertedTransaction inserted = client.insertSubmitted(document("hibiscus:41"));

    assertThat(inserted.docstatus()).isEqualTo(1);
    assertThat(inserted.name()).startsWith("ACC-BTN-2026-");
    JsonNode body = JsonMapper.builder().build().readTree(erp.requests().getFirst().body());
    assertThat(body.get("docstatus").asInt()).isEqualTo(1);
    assertThat(body.get("date").asString()).isEqualTo("2026-11-25");
    assertThat(body.get("bank_account").asString()).isEqualTo("Main account - EX");
    assertThat(body.get("currency").asString()).isEqualTo("EUR");
    assertThat(body.get("description").asString()).isEqualTo("Invoice 2026-0042");
    assertThat(body.get("reference_number").asString()).isEqualTo("E2E-1");
    assertThat(body.get("transaction_id").asString()).isEqualTo("hibiscus:41");
    assertThat(body.get("deposit").decimalValue()).isEqualByComparingTo("0");
    assertThat(body.get("withdrawal").toString()).isEqualTo("1234.56");
    assertThat(body.get("transaction_type").asString()).isEqualTo("SEPA transfer");
    assertThat(body.get("bank_party_name").asString()).isEqualTo("Example Trading GmbH");
    assertThat(body.get("bank_party_iban").asString()).isEqualTo("DE87123456780000000002");
    assertThat(body.get("bank_party_account_number").asString()).isEmpty();
    assertThat(erp.requests().getFirst().header("Content-Type")).isEqualTo("application/json");
  }

  @Test
  void refusesAnAnswerWithoutTheTransactionId() {
    erp.dropTransactionId();

    assertThatThrownBy(() -> client.insertSubmitted(document("hibiscus:41")))
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.INCONSISTENT))
        .hasMessage(
            "ERPNext company-a did not keep the transaction_id of the new bank transaction;"
                + " delivering more could create duplicates");
  }

  @Test
  void givesTheDocstatusOfADraftBack() throws ErpNextException {
    erp.insertAsDraft();

    assertThat(client.insertSubmitted(document("hibiscus:41")).docstatus()).isZero();
  }

  @Test
  void classifiesErrorsWithoutRepeatingTheBodyOrTheSecret() {
    erp.override(request ->
        Response.json(
            417, "{\"exc_type\":\"ValidationError\",\"exception\":\"secret-a Example Trading\"}"));
    assertThatThrownBy(() -> client.insertSubmitted(document("hibiscus:41")))
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED))
        .hasMessage("ERPNext company-a rejected the request (HTTP 417, ValidationError)");

    erp.override(request -> Response.json(403, "not json"));
    assertThatThrownBy(() -> client.companyBankAccounts())
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTHENTICATION))
        .hasMessage("ERPNext company-a rejected the API key (HTTP 403)");

    erp.override(request -> Response.json(502, "{\"exc_type\":\"<script>\"}"));
    assertThatThrownBy(() -> client.companyBankAccounts())
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE))
        .hasMessage("ERPNext company-a is unavailable (HTTP 502)");

    erp.override(request -> Response.json(200, "{\"data\":\"odd\"}"));
    assertThatThrownBy(() -> client.companyBankAccounts())
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE))
        .hasMessage("ERPNext company-a answered with something Bankbote cannot read");

    erp.override(request -> Response.json(200, "{ broken"));
    assertThatThrownBy(() -> client.insertSubmitted(document("x")))
        .hasMessage("ERPNext company-a answered with something Bankbote cannot read");
  }

  @Test
  void rejectedKeyIsReportedAsAuthentication() throws IOException {
    ErpNextClient wrong =
        new ErpNextClient(
            "company-a",
            erp.server().uri(),
            Secret.fromFile(Files.writeString(dir.resolve("k2"), "key-b")),
            Secret.fromFile(Files.writeString(dir.resolve("s2"), "secret-b")),
            TlsContexts.system(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));

    assertThatThrownBy(wrong::companyBankAccounts)
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTHENTICATION))
        .hasMessage("ERPNext company-a rejected the API key (HTTP 401)");
  }

  @Test
  void neverFollowsARedirectAndNeverSendsTheTokenElsewhere() {
    try (FakeHttpServer elsewhere = FakeHttpServer.http()) {
      erp.override(request ->
          new Response(
              307,
              "text/plain",
              "",
              Map.of("Location", elsewhere.uri() + request.uri().toString())));

      assertThatThrownBy(() -> client.companyBankAccounts())
          .isInstanceOfSatisfying(
              ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE))
          .hasMessage(
              "ERPNext company-a answered with a redirect (HTTP 307), which Bankbote does not follow");
      assertThat(elsewhere.requests()).isEmpty();
    }
  }

  @Test
  void reportsAnUnreachableOrSlowTarget() throws IOException {
    erp.override(request -> {
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return Response.json(200, "{\"data\":[]}");
    });
    ErpNextClient impatient = client(erp.server().uri(), Duration.ofMillis(200));

    assertThatThrownBy(impatient::companyBankAccounts)
        .isInstanceOfSatisfying(
            ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE))
        .hasMessageStartingWith("ERPNext company-a cannot be reached: HttpTimeoutException");

    URI closed = erp.server().uri();
    erp.close();
    ErpNextClient gone = client(closed, Duration.ofSeconds(1));
    assertThatThrownBy(gone::companyBankAccounts)
        .hasMessageStartingWith("ERPNext company-a cannot be reached: ");
  }

  @Test
  void keepsAPathPrefixOfTheBaseUrl() throws Exception {
    ErpNextClient prefixed =
        client(URI.create(erp.server().uri() + "/erp/"), Duration.ofSeconds(5));
    erp.override(request -> Response.json(200, "{\"data\":[]}"));

    prefixed.companyBankAccounts();

    assertThat(erp.requests().getFirst().uri().getRawPath())
        .isEqualTo("/erp/api/resource/Bank%20Account");
  }

  @Test
  void documentHidesPersonalDataInItsText() {
    assertThat(document("hibiscus:41").toString())
        .isEqualTo("BankTransactionDocument[transactionId=hibiscus:41]")
        .doesNotContain("Example");
  }

  @Test
  void refusesAnswersOfTheWrongShape() {
    erp.override(request -> Response.json(200, "[]"));
    assertThatThrownBy(() -> client.companyBankAccounts())
        .hasMessage("ERPNext company-a answered with something Bankbote cannot read");

    erp.override(request -> Response.json(200, "{\"data\":[]}"));
    assertThatThrownBy(() -> client.insertSubmitted(document("x")))
        .hasMessage("ERPNext company-a answered with something Bankbote cannot read");

    erp.override(request -> Response.json(409, "<html>"));
    assertThatThrownBy(() -> client.insertSubmitted(document("x")))
        .hasMessage("ERPNext company-a rejected the request (HTTP 409)");
  }

  @Test
  void readsMissingAndNullFieldsAsEmpty() throws ErpNextException {
    erp.override(request ->
        Response.json(200, "{\"data\":[{\"name\":\"A\",\"iban\":null},{\"name\":\"B\"}]}"));

    assertThat(client.companyBankAccounts())
        .containsExactly(new CompanyBankAccount("A", ""), new CompanyBankAccount("B", ""));
    assertThat(new CompanyBankAccount("A", "DE17123456780000000001").toString())
        .isEqualTo("CompanyBankAccount[name=A]");
  }

  @Test
  void anInterruptedRequestEndsAsUnavailable() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> client.companyBankAccounts())
          .isInstanceOfSatisfying(
              ErpNextException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void onlyAnAuthenticationOrInconsistencyStopsTheTarget() {
    assertThat(new ErpNextException(Kind.AUTHENTICATION, "x").stopsTheTarget()).isTrue();
    assertThat(new ErpNextException(Kind.INCONSISTENT, "x").stopsTheTarget()).isTrue();
    assertThat(new ErpNextException(Kind.REJECTED, "x").stopsTheTarget()).isFalse();
    assertThat(new ErpNextException(Kind.UNAVAILABLE, "x").stopsTheTarget()).isFalse();
  }
}
