package com.encircle360.bankbote.hibiscus;

import static com.encircle360.bankbote.integration.HibiscusFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.bankbote.integration.HibiscusFixtures;
import com.encircle360.bankbote.integration.HibiscusServer;
import com.encircle360.bankbote.secret.Secret;
import com.encircle360.bankbote.source.SourceAccount;
import com.encircle360.bankbote.source.SourceEntries;
import com.encircle360.bankbote.source.SourceException;
import com.encircle360.bankbote.source.SourceTransaction;
import com.encircle360.bankbote.tls.TlsContexts;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Bankbote's client and source against a real Hibiscus Server with MariaDB, with fixtures written
 * into the vendor schema. Each test uses accounts of its own.
 */
@Tag("hibiscus")
class HibiscusServerIT {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Clock CLOCK = Clock.system(BERLIN);
  private static final String A1 = "DE17123456780000000001";

  @TempDir static Path dir;
  static HibiscusServer server;
  static HibiscusFixtures fixtures;
  static Path certificate;
  static Path password;

  @BeforeAll
  static void start() throws IOException {
    server = HibiscusServer.start(Files.createDirectory(dir.resolve("secrets")));
    fixtures = new HibiscusFixtures(server);
    certificate =
        HibiscusServer.writePem(server.certificateFromKeyStore(), dir.resolve("hibiscus.pem"));
    password = server.writeMasterPassword(dir);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  private static HibiscusClient client(String username, Path passwordFile) {
    return new HibiscusClient(
        server.url(),
        username,
        Secret.fromFile(passwordFile),
        TlsContexts.pinned(certificate),
        Duration.ofSeconds(10),
        Duration.ofSeconds(120));
  }

  private static HibiscusSource source() {
    return new HibiscusSource(client("admin", password), CLOCK);
  }

  private static LocalDate today() {
    return LocalDate.now(BERLIN);
  }

  private static SourceAccount account(String id) {
    return new SourceAccount(id, A1, "EUR");
  }

  private static HttpResponse<String> raw(String path, String authorization)
      throws IOException, InterruptedException {
    try (HttpClient http =
        HttpClient.newBuilder().sslContext(TlsContexts.pinned(certificate)).build()) {
      HttpRequest.Builder request = HttpRequest.newBuilder(server.url().resolve(path)).GET();
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
  }

  private static String basic(String username, String secret) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, JsonNode> rawById(String accountId, int days) throws SourceException {
    Map<String, JsonNode> rows = new java.util.LinkedHashMap<>();
    for (JsonNode node :
        client("admin", password).list("hibiscus/konto/" + accountId + "/umsaetze/days/" + days)) {
      rows.put(node.get("id").asString(), node);
    }
    return rows;
  }

  @Test
  void thePresentedCertificateIsTheOneInTheKeyStoreWithTheConfiguredNameAndNoAlternativeName()
      throws Exception {
    X509Certificate stored = server.certificateFromKeyStore();
    X509Certificate presented = server.presentedCertificate();

    assertThat(presented).isEqualTo(stored);
    assertThat(presented.getSubjectX500Principal().getName())
        .contains("CN=" + HibiscusServer.CERTIFICATE_HOSTNAME);
    assertThat(presented.getSubjectAlternativeNames()).isNull();
  }

  @Test
  void acceptsAnyUserNameWithTheMasterPassword() throws SourceException {
    assertThat(new HibiscusSource(client("admin", password), CLOCK).accounts()).isNotNull();
    assertThat(new HibiscusSource(client("bankbote", password), CLOCK).accounts()).isNotNull();
  }

  @Test
  void refusesAWrongPasswordNoPasswordAndAnEmptyUserName() throws Exception {
    Path wrong = Files.writeString(dir.resolve("wrong"), "not-the-master-password");

    assertThatThrownBy(() -> new HibiscusSource(client("admin", wrong), CLOCK).accounts())
        .isInstanceOf(SourceException.class)
        .hasMessage("Hibiscus Server rejected the master password (HTTP 401)");
    assertThat(raw("/webadmin/rest/hibiscus/konto/list", null).statusCode()).isEqualTo(401);
    assertThat(
            raw("/webadmin/rest/hibiscus/konto/list", basic("", server.masterPassword()))
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void listsAccountsWithIbanAndCurrencyAndAnAccountWithoutIban() throws Exception {
    String withIban = fixtures.account(A1, "EUR");
    String withoutIban = fixtures.account(null, "USD");

    Map<String, SourceAccount> accounts =
        source().accounts().stream()
            .collect(Collectors.toMap(SourceAccount::id, Function.identity()));

    assertThat(accounts.get(withIban)).isEqualTo(new SourceAccount(withIban, A1, "EUR"));
    assertThat(accounts.get(withoutIban)).isEqualTo(new SourceAccount(withoutIban, "", "USD"));
  }

  @Test
  void writesDatesAsIsoDatesAndAmountsAsJavaPrintsADouble() throws Exception {
    String account = fixtures.account(A1, "EUR");
    Map<String, Double> amounts = new java.util.LinkedHashMap<>();
    for (double amount :
        new double[] {-1234.56, 12_345_678.9, 10_000_000, 7.1, -0.5, 0, 99_999_999.99}) {
      amounts.put(fixtures.insert(row(account, today().minusDays(2), amount)), amount);
    }

    Map<String, JsonNode> raw = rawById(account, 5);
    Map<String, SourceTransaction> read =
        source().transactions(account(account), today().minusDays(5)).transactions().stream()
            .collect(Collectors.toMap(SourceTransaction::id, Function.identity()));

    amounts.forEach((id, amount) -> {
      assertThat(raw.get(id).get("betrag").asString()).isEqualTo(Double.toString(amount));
      assertThat(raw.get(id).get("datum").asString()).isEqualTo(today().minusDays(2).toString());
      assertThat(read.get(id).amount())
          .isEqualTo(new BigDecimal(Double.toString(amount)).setScale(2));
      assertThat(read.get(id).bookingDate()).isEqualTo(today().minusDays(2));
    });
    assertThat(raw.values())
        .extracting(node -> node.get("betrag").asString())
        .contains("1.23456789E7", "1.0E7", "7.1", "-0.5", "0.0", "9.999999999E7");
  }

  @Test
  void marksPendingTransactionsByTheFlagNotBookedAndLeavesAnEmptyFlagOut() throws Exception {
    String account = fixtures.account(A1, "EUR");
    Map<String, Boolean> expected = new java.util.LinkedHashMap<>();
    expected.put(fixtures.insert(row(account, today(), 1).flags(null)), false);
    expected.put(fixtures.insert(row(account, today(), 2).flags(0)), false);
    expected.put(fixtures.insert(row(account, today(), 3).flags(1)), false);
    expected.put(fixtures.insert(row(account, today(), 4).flags(2)), true);
    expected.put(fixtures.insert(row(account, today(), 5).flags(3)), true);
    String nullFlags = expected.keySet().iterator().next();

    Map<String, JsonNode> raw = rawById(account, 1);
    SourceEntries entries = source().transactions(account(account), today());

    assertThat(raw.get(nullFlags).has("flags")).isFalse();
    assertThat(entries.unreadable()).isEmpty();
    assertThat(entries.transactions())
        .allSatisfy(t -> assertThat(t.pending()).as(t.id()).isEqualTo(expected.get(t.id())));
    assertThat(entries.transactions()).hasSize(expected.size());
  }

  @Test
  void keepsUmlautsTheEuroSignAndLettersBeyondLatin1InPurposeAndName() throws Exception {
    String account = fixtures.account(A1, "EUR");
    String id =
        fixtures.insert(
            row(account, today(), -42.5)
                .purpose(
                    "Rechnung 2026-0042 für Müller",
                    "Grüße – „Ä Ö Ü ß“",
                    "Zeile drei\nZeile vier €")
                .name("Łódź Handel Ω", "Gärtnerei Möller")
                .counterparty("DE87123456780000000002")
                .type("Überweisung")
                .references("E2E-ÄÖÜ", "NONREF"));

    SourceTransaction transaction =
        source().transactions(account(account), today()).transactions().stream()
            .filter(t -> t.id().equals(id))
            .findFirst()
            .orElseThrow();

    assertThat(transaction.purpose())
        .isEqualTo("Rechnung 2026-0042 für Müller\nGrüße – „Ä Ö Ü ß“\nZeile drei\nZeile vier €");
    assertThat(transaction.counterpartyName()).isEqualTo("Łódź Handel Ω Gärtnerei Möller");
    assertThat(transaction.counterpartyAccount()).isEqualTo("DE87123456780000000002");
    assertThat(transaction.type()).isEqualTo("Überweisung");
    assertThat(transaction.reference()).isEqualTo("E2E-ÄÖÜ");
  }

  @Test
  void answersWithApplicationJsonWithoutCharsetInUtf8() throws Exception {
    String account = fixtures.account(A1, "EUR");
    fixtures.insert(row(account, today(), 1).name("Müller €", null));

    HttpResponse<String> response =
        raw(
            "/webadmin/rest/hibiscus/konto/" + account + "/umsaetze/days/1",
            basic("admin", server.masterPassword()));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(response.body()).contains("Müller");
  }

  @Test
  void theWindowStartsOnItsFirstDayAndEndsWithTheNewestBooking() throws Exception {
    String account = fixtures.account(A1, "EUR");
    LocalDate from = today().minusDays(7);
    String before = fixtures.insert(row(account, from.minusDays(1), 1));
    String first = fixtures.insert(row(account, from, 2));
    String last = fixtures.insert(row(account, today(), 3));
    String future = fixtures.insert(row(account, today().plusDays(1), 4));

    Map<String, JsonNode> raw = rawById(account, 8);
    List<String> read =
        source().transactions(account(account), from).transactions().stream()
            .map(SourceTransaction::id)
            .toList();

    assertThat(raw).containsKeys(before, first, last, future);
    assertThat(read).containsExactlyInAnyOrder(first, last, future);
    assertThat(rawById(account, 7)).doesNotContainKey(before).containsKey(first);
  }

  @Test
  void ordersByBookingDateAndIdNewestFirstWithoutALimit() throws Exception {
    String account = fixtures.account(A1, "EUR");
    int count = 10_050;
    fixtures.insertMany(
        row(account, today().minusDays(1), 1.25).purpose("bulk", null, null), count);
    String newest = fixtures.insert(row(account, today(), 2));

    List<String> ids = new ArrayList<>(rawById(account, 3).keySet());
    SourceEntries entries = source().transactions(account(account), today().minusDays(3));

    assertThat(ids).hasSize(count + 1).first().isEqualTo(newest);
    List<Long> older = ids.subList(1, ids.size()).stream().map(Long::valueOf).toList();
    assertThat(older).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    assertThat(entries.transactions()).hasSize(count + 1);
  }

  @Test
  void showsRowsInsertedBehindItsBackAtOnce() throws Exception {
    String account = fixtures.account(A1, "EUR");
    assertThat(source().accounts()).extracting(SourceAccount::id).contains(account);
    assertThat(source().transactions(account(account), today()).transactions()).isEmpty();

    String id = fixtures.insert(row(account, today(), 9.99));

    assertThat(source().transactions(account(account), today()).transactions())
        .extracting(SourceTransaction::id)
        .containsExactly(id);
  }

  @Test
  void anUnknownAccountIsAnErrorOfTheServer() {
    assertThatThrownBy(() -> source().transactions(account("9999"), today()))
        .isInstanceOf(SourceException.class)
        .hasMessage("Hibiscus Server answered HTTP 500");
  }

  @Test
  void theLogOfTheServerHoldsNeitherPassword() {
    assertThat(server.logs())
        .doesNotContain(server.masterPassword())
        .doesNotContain(server.databasePassword());
  }
}
