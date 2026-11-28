package com.encircle360.oss.bankbote.hibiscus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.oss.bankbote.secret.Secret;
import com.encircle360.oss.bankbote.source.SourceAccount;
import com.encircle360.oss.bankbote.source.SourceEntries;
import com.encircle360.oss.bankbote.source.SourceException;
import com.encircle360.oss.bankbote.source.SourceTransaction;
import com.encircle360.oss.bankbote.testing.FakeHibiscus;
import com.encircle360.oss.bankbote.testing.FakeHttpServer;
import com.encircle360.oss.bankbote.testing.FakeHttpServer.Response;
import com.encircle360.oss.bankbote.testing.TestCertificate;
import com.encircle360.oss.bankbote.tls.TlsContexts;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HibiscusSourceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-27T09:00:00Z"), ZoneId.of("Europe/Berlin"));
  private static final SourceAccount ACCOUNT =
      new SourceAccount("3", "DE17123456780000000001", "EUR");

  @TempDir Path dir;
  private TestCertificate certificate;
  private FakeHttpServer server;
  private HibiscusSource source;

  @BeforeEach
  void start() throws Exception {
    certificate = TestCertificate.create(dir, "hibiscus");
    server = FakeHttpServer.https(certificate.serverContext());
    Path password = Files.writeString(dir.resolve("master-password"), "master-secret\n");
    source = source(server.uri(), password);
  }

  private HibiscusSource source(URI uri, Path password) {
    HibiscusClient client =
        new HibiscusClient(
            uri,
            "admin",
            Secret.fromFile(password),
            TlsContexts.pinned(certificate.pem()),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
    return new HibiscusSource(client, CLOCK);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private static String transaction(String fields) {
    return "{\"id\":\"41\",\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"valuta\":\"2026-11-25\","
        + "\"betrag\":\"-1234.56\""
        + fields
        + "}";
  }

  @Test
  void readsTheAccountsWithBasicAuthenticationOfTheMasterPassword() throws SourceException {
    server.handler(request ->
        Response.json(
            200,
            "[{\"id\":\"3\",\"iban\":\"DE17 1234 5678 0000 0000 01\",\"waehrung\":\"EUR\","
                + "\"kontonummer\":\"1\",\"saldo\":\"1.0E7\"},"
                + "{\"id\":\"4\",\"kontonummer\":\"2\"}]"));

    List<SourceAccount> accounts = source.accounts();

    assertThat(accounts)
        .containsExactly(
            new SourceAccount("3", "DE17 1234 5678 0000 0000 01", "EUR"),
            new SourceAccount("4", "", ""));
    FakeHttpServer.Request request = server.requests().getFirst();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.uri().getPath()).isEqualTo("/webadmin/rest/hibiscus/konto/list");
    assertThat(request.header("Authorization"))
        .isEqualTo(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("admin:master-secret".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void keepsAPathPrefixOfTheConfiguredUrl() throws Exception {
    server.handler(request -> Response.json(200, "[]"));
    Path password = Files.writeString(dir.resolve("pw"), "x");

    source(URI.create(server.uri() + "/hibiscus"), password).accounts();

    assertThat(server.requests().getFirst().uri().getPath())
        .isEqualTo("/hibiscus/webadmin/rest/hibiscus/konto/list");
  }

  @Test
  void readsTheTransactionsOfTheWindowWithThreeDaysToSpare() throws SourceException {
    server.handler(request ->
        Response.json(
            200,
            "["
                + transaction(
                    ",\"zweck\":\"EREF+E2E-1 SVWZ+Invoice\",\"zweck2\":\"2026-0042\","
                        + "\"zweck3\":\"line three\\nline four\","
                        + "\"empfaenger_name\":\"Example\",\"empfaenger_name2\":\"Trading GmbH\","
                        + "\"empfaenger_konto\":\"DE87123456780000000002\",\"art\":\"SEPA transfer\","
                        + "\"endtoendid\":\"E2E-1\",\"customerref\":\"NONREF\",\"flags\":\"0\","
                        + "\"txid\":\" TX-2026-0041 \"")
                + ","
                + "{\"id\":\"40\",\"konto_id\":\"3\",\"datum\":\"2026-11-19\",\"betrag\":\"5.0\"}"
                + "]"));

    SourceEntries entries = source.transactions(ACCOUNT, LocalDate.of(2026, 11, 20));

    assertThat(server.requests().getFirst().uri().getPath())
        .isEqualTo("/webadmin/rest/hibiscus/konto/3/umsaetze/days/10");
    assertThat(entries.unreadable()).isEmpty();
    assertThat(entries.transactions())
        .containsExactly(
            new SourceTransaction(
                "41",
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 25),
                new java.math.BigDecimal("-1234.56"),
                false,
                "EREF+E2E-1 SVWZ+Invoice\n2026-0042\nline three\nline four",
                "Example Trading GmbH",
                "DE87123456780000000002",
                "SEPA transfer",
                "E2E-1",
                "TX-2026-0041"));
  }

  @Test
  void readsTheValueDateAndTakesAMissingOneAsNone() throws SourceException {
    server.handler(request ->
        Response.json(
            200,
            "["
                + transaction(",\"valuta\":\"2026-11-26\"")
                + ","
                + "{\"id\":\"42\",\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"betrag\":\"1.0\"},"
                + "{\"id\":\"43\",\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"valuta\":\" \","
                + "\"betrag\":\"1.0\"},"
                + "{\"id\":\"44\",\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"valuta\":\"x\","
                + "\"betrag\":\"1.0\"}"
                + "]"));

    SourceEntries entries = source.transactions(ACCOUNT, LocalDate.of(2026, 11, 20));

    assertThat(entries.transactions())
        .extracting(SourceTransaction::id, SourceTransaction::valueDate, SourceTransaction::bankId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("41", LocalDate.of(2026, 11, 26), ""),
            org.assertj.core.groups.Tuple.tuple("42", null, ""),
            org.assertj.core.groups.Tuple.tuple("43", null, ""));
    assertThat(entries.unreadable())
        .containsExactly(
            new SourceEntries.Unreadable("44", "value date is neither yyyy-MM-dd nor dd.MM.yyyy"));
  }

  /**
   * The bean cuts at the start of the day {@code n} times 24 hours before now on the clock of the
   * server ({@code KontoImpl.getUmsaetze(int)}); whatever its time zone and whatever the hour, the
   * first day of the window must be in the answer.
   */
  @Test
  void theFirstDayOfTheWindowIsAlwaysCompleteWhateverTheTimeZoneOfTheServer() {
    List<String> zones =
        List.of(
            "Europe/Berlin", "UTC", "America/Sao_Paulo", "Pacific/Kiritimati", "Pacific/Pago_Pago");
    Instant start = Instant.parse("2026-10-20T00:00:00Z");
    for (int hour = 0; hour < 24 * 14; hour++) {
      Instant now = start.plus(Duration.ofHours(hour)).plus(Duration.ofMinutes(30));
      for (String bankbote : zones) {
        for (String server : zones) {
          LocalDate today = now.atZone(ZoneId.of(bankbote)).toLocalDate();
          for (int window = 0; window <= 3; window++) {
            LocalDate from = today.minusDays(window);
            long days = HibiscusSource.days(from, today);
            LocalDate cut =
                now.minus(Duration.ofHours(24 * days)).atZone(ZoneId.of(server)).toLocalDate();
            assertThat(cut)
                .as("%s %s %s %s", now, bankbote, server, window)
                .isBeforeOrEqualTo(from);
          }
        }
      }
    }
  }

  @Test
  void readsEveryTransactionOfTheFirstDayFromAServerThatIsTwoDaysAhead() throws Exception {
    Instant now = Instant.parse("2026-11-27T10:30:00Z");
    try (FakeHibiscus hibiscus = new FakeHibiscus(dir, "master-secret")) {
      hibiscus
          .serverClock(Clock.fixed(now, ZoneId.of("Pacific/Kiritimati")))
          .booked("3", "60", "2026-11-19", "1.0")
          .booked("3", "61", "2026-11-20", "1.0")
          .booked("3", "62", "2026-11-20", "1.0")
          .booked("3", "63", "2026-11-26", "1.0");
      HibiscusSource behind =
          new HibiscusSource(
              new HibiscusClient(
                  hibiscus.server().uri(),
                  "admin",
                  Secret.fromFile(dir.resolve("master-password")),
                  TlsContexts.pinned(hibiscus.certificate()),
                  Duration.ofSeconds(5),
                  Duration.ofSeconds(5)),
              Clock.fixed(now, ZoneId.of("Pacific/Pago_Pago")));

      SourceEntries entries = behind.transactions(ACCOUNT, LocalDate.of(2026, 11, 20));

      assertThat(entries.transactions())
          .extracting(SourceTransaction::id)
          .containsExactly("63", "62", "61");
    }
  }

  @Test
  void decodesUmlautsByTheCharsetOfTheAnswer() throws SourceException {
    String body = "[{\"id\":\"3\",\"iban\":\"\",\"waehrung\":\"EUR\",\"name\":\"Müller\"}]";
    server.handler(request -> Response.json(200, body));
    assertThat(source.accounts()).hasSize(1);

    for (String contentType :
        List.of(
            "application/json;charset=UTF-8",
            "application/json; charset=\"utf-8\"",
            "application/json;charset=unknown-charset",
            "text/plain")) {
      server.handler(request -> new Response(200, contentType, body, Map.of()));
      assertThat(source.accounts()).as(contentType).hasSize(1);
    }
  }

  @Test
  void charsetFollowsTheContentType() throws Exception {
    server.handler(request -> new Response(200, "text/plain;charset=ISO-8859-1", "[]", Map.of()));
    try (java.net.http.HttpClient client =
        java.net.http.HttpClient.newBuilder()
            .sslContext(TlsContexts.pinned(certificate.pem()))
            .build()) {
      java.net.http.HttpResponse<Void> response =
          client.send(
              java.net.http.HttpRequest.newBuilder(server.uri()).build(),
              java.net.http.HttpResponse.BodyHandlers.discarding());

      assertThat(HibiscusClient.charset(response)).isEqualTo(StandardCharsets.ISO_8859_1);
    }
    server.handler(request -> new Response(200, "application/json", "[]", Map.of()));
    try (java.net.http.HttpClient client =
        java.net.http.HttpClient.newBuilder()
            .sslContext(TlsContexts.pinned(certificate.pem()))
            .build()) {
      java.net.http.HttpResponse<Void> response =
          client.send(
              java.net.http.HttpRequest.newBuilder(server.uri()).build(),
              java.net.http.HttpResponse.BodyHandlers.discarding());

      assertThat(HibiscusClient.charset(response)).isEqualTo(StandardCharsets.UTF_8);
    }
  }

  @Test
  void marksPendingTransactions() throws SourceException {
    server.handler(request ->
        Response.json(200, "[" + transaction(",\"flags\":\"2\",\"betrag\":\"oops\"") + "]"));

    SourceEntries entries = source.transactions(ACCOUNT, LocalDate.of(2026, 11, 20));

    assertThat(entries.transactions())
        .singleElement()
        .satisfies(t -> assertThat(t.pending()).isTrue());
    assertThat(entries.unreadable()).isEmpty();
  }

  @Test
  void reportsTransactionsItCannotReadWithoutTheirContent() throws SourceException {
    server.handler(request ->
        Response.json(
            200,
            "["
                + "{\"id\":\"50\",\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"betrag\":\"1.234,56\",\"zweck\":\"secret\"},"
                + "{\"id\":\"51\",\"konto_id\":\"3\",\"datum\":\"25/11/2026\",\"betrag\":\"1.0\"},"
                + "{\"id\":\"52\",\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"betrag\":\"1.0\",\"flags\":\"x\"},"
                + "{\"id\":\"53\",\"konto_id\":\"9\",\"datum\":\"2026-11-25\",\"betrag\":\"1.0\"},"
                + "{\"konto_id\":\"3\",\"datum\":\"2026-11-25\",\"betrag\":\"1.0\"},"
                + "\"not an object\""
                + "]"));

    SourceEntries entries = source.transactions(ACCOUNT, LocalDate.of(2026, 11, 20));

    assertThat(entries.transactions()).isEmpty();
    assertThat(entries.unreadable())
        .containsExactly(
            new SourceEntries.Unreadable("50", "amount is not a decimal number"),
            new SourceEntries.Unreadable("51", "date is neither yyyy-MM-dd nor dd.MM.yyyy"),
            new SourceEntries.Unreadable("52", "flags are not a number"),
            new SourceEntries.Unreadable("53", "belongs to another account"),
            new SourceEntries.Unreadable("", "has no id"),
            new SourceEntries.Unreadable("", "is not an object"));
  }

  @Test
  void asksForOneDayWhenTheWindowStartsInTheFuture() throws SourceException {
    server.handler(request -> Response.json(200, "[]"));

    source.transactions(ACCOUNT, LocalDate.of(2026, 12, 1));

    assertThat(server.requests().getFirst().uri().getPath()).endsWith("/umsaetze/days/1");
  }

  @Test
  void refusesWindowsAndAccountsTheInterfaceCannotExpress() {
    assertThatThrownBy(() -> source.transactions(ACCOUNT, LocalDate.of(1990, 1, 1)))
        .isInstanceOf(SourceException.class)
        .hasMessage(
            "Hibiscus Server gives at most 9999 days of transactions, the window needs 13482");
    assertThatThrownBy(() ->
            source.transactions(new SourceAccount("12345", "", "EUR"), LocalDate.of(2026, 11, 1)))
        .isInstanceOf(SourceException.class)
        .hasMessage("Hibiscus Server gives transactions only for account ids of up to four digits");
    assertThatThrownBy(
            () -> source.transactions(new SourceAccount("3", "", "XYZ"), LocalDate.of(2026, 11, 1)))
        .isInstanceOf(SourceException.class)
        .hasMessage(
            "account 3 has no valid currency: not an ISO 4217 currency code with fixed decimals");
    assertThat(server.requests()).isEmpty();
  }

  @Test
  void reportsRejectedCredentialsAndOtherStatuses() {
    server.handler(request -> Response.json(401, ""));
    assertThatThrownBy(() -> source.accounts())
        .isInstanceOf(SourceException.class)
        .hasMessage("Hibiscus Server rejected the master password (HTTP 401)")
        .hasMessageNotContaining("master-secret");

    server.handler(request -> Response.json(500, "boom"));
    assertThatThrownBy(() -> source.accounts()).hasMessage("Hibiscus Server answered HTTP 500");
  }

  @Test
  void neverFollowsRedirects() {
    try (FakeHttpServer elsewhere = FakeHttpServer.http()) {
      server.handler(request ->
          new Response(302, "text/plain", "", Map.of("Location", elsewhere.uri() + "/x")));

      assertThatThrownBy(() -> source.accounts()).hasMessage("Hibiscus Server answered HTTP 302");
      assertThat(elsewhere.requests()).isEmpty();
    }
  }

  @Test
  void refusesAnswersThatAreNoJsonArray() {
    server.handler(request -> Response.json(200, "{\"error\":true}"));
    assertThatThrownBy(() -> source.accounts())
        .isInstanceOf(SourceException.class)
        .hasMessage("Hibiscus Server answered with something other than a JSON list");

    server.handler(request -> Response.json(200, "[ not json"));
    assertThatThrownBy(() -> source.accounts())
        .hasMessage("Hibiscus Server answered with something other than a JSON list");
  }

  @Test
  void refusesAServerWithAnotherCertificate() throws Exception {
    TestCertificate other = TestCertificate.create(dir, "other");
    try (FakeHttpServer impostor = FakeHttpServer.https(other.serverContext())) {
      Path password = Files.writeString(dir.resolve("pw"), "master-secret");

      assertThatThrownBy(() -> source(impostor.uri(), password).accounts())
          .isInstanceOf(SourceException.class)
          .hasMessageStartingWith("Hibiscus Server cannot be reached: SSLHandshakeException")
          .hasMessageNotContaining("master-secret");
      assertThat(impostor.requests()).isEmpty();
    }
  }

  @Test
  void reportsAnUnreachableServer() throws Exception {
    URI closed = server.uri();
    server.close();

    assertThatThrownBy(() -> source.accounts())
        .isInstanceOf(SourceException.class)
        .hasMessageStartingWith("Hibiscus Server cannot be reached: ");
    assertThat(closed).isNotNull();
  }

  @Test
  void refusesAnswersAboveTheSizeLimit() {
    HibiscusClient small =
        new HibiscusClient(
            server.uri(),
            "admin",
            Secret.fromFile(writePassword()),
            TlsContexts.pinned(certificate.pem()),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            10);
    server.handler(request -> Response.json(200, "[\"0123456789\"]"));

    assertThatThrownBy(() -> new HibiscusSource(small, CLOCK).accounts())
        .isInstanceOf(SourceException.class)
        .hasMessage("Hibiscus Server answered with more than 10 bytes");
  }

  private Path writePassword() {
    try {
      return Files.writeString(dir.resolve("pw-small"), "master-secret");
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void timesOutOnASlowServer() throws Exception {
    HibiscusClient impatient =
        new HibiscusClient(
            server.uri(),
            "admin",
            Secret.fromFile(writePassword()),
            TlsContexts.pinned(certificate.pem()),
            Duration.ofSeconds(5),
            Duration.ofMillis(200));
    server.handler(request -> {
      Thread.sleep(2000);
      return Response.json(200, "[]");
    });

    assertThatThrownBy(() -> new HibiscusSource(impatient, CLOCK).accounts())
        .isInstanceOf(SourceException.class)
        .hasMessageStartingWith("Hibiscus Server cannot be reached: HttpTimeoutException");
  }

  @Test
  void acceptsABaseUrlWithATrailingSlashAndNullValues() throws Exception {
    server.handler(
        request -> Response.json(200, "[{\"id\":\"3\",\"iban\":null,\"waehrung\":\"EUR\"}]"));

    List<SourceAccount> accounts =
        source(
                URI.create(server.uri() + "/"),
                Files.writeString(dir.resolve("pw2"), "master-secret"))
            .accounts();

    assertThat(accounts).containsExactly(new SourceAccount("3", "", "EUR"));
    assertThat(server.requests().getFirst().uri().getPath())
        .isEqualTo("/webadmin/rest/hibiscus/konto/list");
  }

  @Test
  void aForbiddenAnswerIsARejectedPassword() {
    server.handler(request -> Response.json(403, ""));

    assertThatThrownBy(() -> source.accounts())
        .hasMessage("Hibiscus Server rejected the master password (HTTP 403)");
  }

  @Test
  void ignoresContentTypeParametersWithoutValue() throws SourceException {
    server.handler(request -> new Response(200, "application/json; charset; q=1", "[]", Map.of()));

    assertThat(source.accounts()).isEmpty();
  }

  @Test
  void anInterruptedRequestEndsWithASourceException() {
    server.handler(request -> Response.json(200, "[]"));
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> source.accounts())
          .isInstanceOf(SourceException.class)
          .hasMessage("interrupted while waiting for Hibiscus Server");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void sourceTypesKeepPersonalDataOutOfTheirText() {
    assertThat(new SourceAccount("3", "DE17123456780000000001", "EUR").toString())
        .isEqualTo("SourceAccount[id=3, iban=DE17…0001]");
    assertThat(new SourceAccount("4", "", "EUR").toString())
        .isEqualTo("SourceAccount[id=4, iban=none]");
    assertThat(
            new SourceTransaction(
                    "41",
                    LocalDate.of(2026, 11, 25),
                    LocalDate.of(2026, 11, 25),
                    java.math.BigDecimal.ONE,
                    false,
                    "Invoice",
                    "Example",
                    "",
                    "",
                    "",
                    "TX-1")
                .toString())
        .isEqualTo("SourceTransaction[id=41]");
  }

  @Test
  void anAnswerCutOffInTheMiddleIsAnUnreachableServer() {
    server.handler(request ->
        new Response(
            200, "application/json; q=1", "[1,2,3]", Map.of(FakeHttpServer.TRUNCATE, "yes")));

    assertThatThrownBy(() -> source.accounts())
        .isInstanceOf(SourceException.class)
        .hasMessageStartingWith("Hibiscus Server cannot be reached: ");
  }
}
