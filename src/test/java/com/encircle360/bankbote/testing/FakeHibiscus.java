package com.encircle360.bankbote.testing;

import com.encircle360.bankbote.testing.FakeHttpServer.Request;
import com.encircle360.bankbote.testing.FakeHttpServer.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Hibiscus Server as far as Bankbote reads it: the JSON beans {@code hibiscus/konto/list} and
 * {@code hibiscus/konto/<id>/umsaetze/days/<n>} over HTTPS with its own certificate and HTTP Basic
 * authentication of the master password. The beans write every value as a string, as {@code
 * JsonUtil} of hibiscus.server does, and leave out empty columns.
 */
public final class FakeHibiscus implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern TRANSACTIONS =
      Pattern.compile("/webadmin/rest/hibiscus/konto/([0-9]{1,4})/umsaetze/days/([0-9]{1,4})");

  private final TestCertificate certificate;
  private final FakeHttpServer server;
  private final String masterPassword;
  private final List<Map<String, String>> accounts = new CopyOnWriteArrayList<>();
  private final Map<String, List<Map<String, String>>> transactions = new ConcurrentHashMap<>();

  public FakeHibiscus(Path dir, String masterPassword) {
    this.certificate = TestCertificate.create(dir, "hibiscus-server");
    this.masterPassword = masterPassword;
    this.server = FakeHttpServer.https(certificate.serverContext()).handler(this::handle);
  }

  public Path certificate() {
    return certificate.pem();
  }

  public FakeHttpServer server() {
    return server;
  }

  public FakeHibiscus account(String id, String iban) {
    accounts.add(
        Map.of("id", id, "iban", iban, "waehrung", "EUR", "kontonummer", id, "blz", "12345678"));
    return this;
  }

  /** A booked transaction with an invented counterparty, as the bean writes it. */
  public FakeHibiscus booked(String accountId, String id, String date, String amount) {
    return add(
        accountId,
        Map.of(
            "id",
            id,
            "konto_id",
            accountId,
            "datum",
            date,
            "valuta",
            date,
            "betrag",
            amount,
            "zweck",
            "SVWZ+Invoice " + id,
            "empfaenger_name",
            "Example Customer " + id,
            "empfaenger_konto",
            "DE87123456780000000002",
            "art",
            "SEPA transfer",
            "endtoendid",
            "E2E-" + id));
  }

  /** A pending transaction: {@code flags} holds {@code Umsatz.FLAG_NOTBOOKED}. */
  public FakeHibiscus pending(String accountId, String id, String date, String amount) {
    return add(
        accountId,
        Map.of(
            "id",
            id,
            "konto_id",
            accountId,
            "datum",
            date,
            "valuta",
            date,
            "betrag",
            amount,
            "zweck",
            "Pending " + id,
            "flags",
            "2",
            "saldo",
            "0.0"));
  }

  private FakeHibiscus add(String accountId, Map<String, String> transaction) {
    transactions.computeIfAbsent(accountId, key -> new CopyOnWriteArrayList<>()).add(transaction);
    return this;
  }

  private Response handle(Request request) {
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("admin:" + masterPassword).getBytes(StandardCharsets.UTF_8));
    if (!expected.equals(request.header("Authorization"))) {
      return Response.json(401, "");
    }
    String path = request.uri().getPath();
    if (path.equals("/webadmin/rest/hibiscus/konto/list")) {
      return Response.json(200, JSON.writeValueAsString(accounts));
    }
    Matcher matcher = TRANSACTIONS.matcher(path);
    if (matcher.matches()) {
      List<Map<String, String>> list =
          new ArrayList<>(transactions.getOrDefault(matcher.group(1), List.of()));
      return Response.json(200, JSON.writeValueAsString(list.reversed()));
    }
    return Response.json(500, "no command found for REST url " + path);
  }

  @Override
  public void close() {
    server.close();
  }
}
