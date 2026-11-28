package com.encircle360.oss.bankbote.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.oss.bankbote.account.Iban;
import com.encircle360.oss.bankbote.erpnext.ErpNextClient;
import com.encircle360.oss.bankbote.secret.Secret;
import com.encircle360.oss.bankbote.source.SourceTransaction;
import com.encircle360.oss.bankbote.testing.FakeErpNext;
import com.encircle360.oss.bankbote.testing.FakeHttpServer;
import com.encircle360.oss.bankbote.testing.FakeHttpServer.Response;
import com.encircle360.oss.bankbote.testing.FakeSource;
import com.encircle360.oss.bankbote.tls.TlsContexts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(OutputCaptureExtension.class)
class DeliveryTest {

  static final String A1 = "DE17123456780000000001";
  static final String A2 = "DE87123456780000000002";
  static final String B1 = "DE60123456780000000003";
  static final String C1 = "DE33123456780000000004";
  static final String UNMAPPED = "DE06123456780000000005";
  static final LocalDate FROM = LocalDate.of(2026, 11, 13);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-27T09:00:00Z"), ZoneId.of("Europe/Berlin"));

  @TempDir Path dir;
  private final List<AutoCloseable> closeables = new ArrayList<>();
  private final FakeSource source = new FakeSource();

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable closeable : closeables) {
      closeable.close();
    }
  }

  private FakeErpNext erp(String name) {
    FakeErpNext erp = new FakeErpNext("key-" + name, "secret-" + name);
    closeables.add(erp);
    return erp;
  }

  private ErpNextClient client(String name, FakeErpNext erp) {
    return client(name, erp, Duration.ofSeconds(5));
  }

  private ErpNextClient client(String name, FakeErpNext erp, Duration timeout) {
    try {
      return new ErpNextClient(
          name,
          erp.server().uri(),
          Secret.fromFile(Files.writeString(dir.resolve(name + ".key"), "key-" + name)),
          Secret.fromFile(Files.writeString(dir.resolve(name + ".secret"), "secret-" + name)),
          TlsContexts.system(),
          Duration.ofSeconds(5),
          timeout);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Delivery delivery(List<Route> routes, ErpNextClient... clients) {
    return new Delivery(source, routes, List.of(clients), CLOCK);
  }

  private static Route route(String iban, String target) {
    return new Route(Iban.parse(iban), target);
  }

  private static List<String> ids(FakeErpNext erp) {
    return erp.transactions().stream().map(doc -> doc.get("transaction_id").asString()).toList();
  }

  /** The source ids of the documents a target holds, read from the purpose {@code Invoice <id>}. */
  private static List<String> sources(FakeErpNext erp) {
    return erp.transactions().stream()
        .map(doc -> doc.get("description").asString().replace("Invoice ", ""))
        .toList();
  }

  /** The identifier of a transaction of {@link FakeSource#booked}, the first with its content. */
  private static String id(String iban, String sourceId, String date, String amount) {
    return TransactionIds.transactionId(
        Iban.parse(iban),
        FakeSource.transaction(sourceId, date, amount, "Invoice " + sourceId, ""),
        1);
  }

  private static AccountReport account(RunReport report, String target, String iban) {
    return report.targets().stream()
        .filter(t -> t.name().equals(target))
        .flatMap(t -> t.accounts().stream())
        .filter(a -> a.iban().equals(Iban.parse(iban).masked()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void deliversEachAccountOnlyToItsOwnTargetWithTwoTargets() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true).bankAccount("Savings - A", A2, true);
    FakeErpNext b = erp("b").bankAccount("Main - B", B1, true);
    source
        .account("1", A1)
        .account("2", A2)
        .account("3", B1)
        .booked("1", "11", "2026-11-20", "-10.00")
        .booked("2", "21", "2026-11-21", "20.00")
        .booked("3", "31", "2026-11-22", "30.00");

    RunReport report =
        delivery(
                List.of(route(A1, "a"), route(A2, "a"), route(B1, "b")),
                client("a", a),
                client("b", b))
            .run(FROM);

    assertThat(sources(a)).containsExactlyInAnyOrder("11", "21");
    assertThat(sources(b)).containsExactly("31");
    assertThat(a.transactions())
        .extracting(doc -> doc.get("bank_account").asString())
        .containsExactlyInAnyOrder("Main - A", "Savings - A");
    assertThat(b.transactions().getFirst().get("bank_account").asString()).isEqualTo("Main - B");
    assertThat(report.successful()).isTrue();
    assertThat(report.targets()).extracting(TargetReport::name).containsExactly("a", "b");
    assertThat(account(report, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 1, 1, 0, 0, 0, 0, null));
    assertThat(account(report, "b", B1).delivered()).isEqualTo(1);
  }

  @Test
  void deliversEachAccountOnlyToItsOwnTargetWithThreeTargetsAndKeepsEachTokenAtItsHost() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    FakeErpNext b = erp("b").bankAccount("Main - B", B1, true);
    FakeErpNext c = erp("c").bankAccount("Main - C", C1, true);
    source
        .account("1", A1)
        .account("3", B1)
        .account("4", C1)
        .booked("1", "11", "2026-11-20", "-10.00")
        .booked("3", "31", "2026-11-22", "30.00")
        .booked("4", "41", "2026-11-23", "40.00")
        .booked("4", "42", "2026-11-24", "-41.00");

    delivery(
            List.of(route(A1, "a"), route(B1, "b"), route(C1, "c")),
            client("a", a),
            client("b", b),
            client("c", c))
        .run(FROM);

    assertThat(sources(a)).containsExactly("11");
    assertThat(sources(b)).containsExactly("31");
    assertThat(sources(c)).containsExactlyInAnyOrder("41", "42");
    for (String name : List.of("a", "b", "c")) {
      FakeErpNext erp = name.equals("a") ? a : name.equals("b") ? b : c;
      assertThat(erp.requests())
          .isNotEmpty()
          .allSatisfy(request ->
              assertThat(request.header("Authorization"))
                  .isEqualTo("token key-" + name + ":secret-" + name));
    }
  }

  @Test
  void neverSendsAnUnmappedAccountAnywhereAndReportsIt() {
    FakeErpNext a =
        erp("a").bankAccount("Main - A", A1, true).bankAccount("Other - A", UNMAPPED, true);
    source
        .account("1", A1)
        .account("5", UNMAPPED)
        .account("6", "")
        .booked("5", "51", "2026-11-20", "-10.00")
        .booked("5", "52", "2026-11-20", "-11.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(a.transactions()).isEmpty();
    assertThat(report.unmapped())
        .containsExactly(
            new UnmappedAccount("DE06…0005", "5", 2), new UnmappedAccount("none", "6", 0));
    assertThat(report.successful()).isTrue();
  }

  @Test
  void reportsAnUnmappedAccountWhoseTransactionsCannotBeRead() {
    source.account("5", UNMAPPED).failing("5", "Hibiscus Server answered HTTP 500");

    RunReport report = delivery(List.of(), client("a", erp("a"))).run(FROM);

    assertThat(report.unmapped()).containsExactly(new UnmappedAccount("DE06…0005", "5", -1));
  }

  @Test
  void neverDeliversAPendingTransaction() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source.account("1", A1).pending("1", "90").booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(sources(a)).containsExactly("11");
    assertThat(account(report, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 2, 1, 0, 1, 0, 0, null));
  }

  @Test
  void neverDeliversAnUnreadableTransactionAndCountsItAsFailed() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source.account("1", A1).unreadable("1", "12").booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(sources(a)).containsExactly("11");
    assertThat(account(report, "a", A1))
        .isEqualTo(
            new AccountReport(
                "DE17…0001", 2, 1, 0, 0, 1, 0, "1 transaction(s) cannot be read at the source"));
    assertThat(report.successful()).isFalse();
  }

  @Test
  void runningTwiceOrWithOverlappingWindowsCreatesNoDuplicates() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source
        .account("1", A1)
        .booked("1", "11", "2026-11-14", "1.00")
        .booked("1", "12", "2026-11-20", "2.00");
    Delivery delivery = delivery(List.of(route(A1, "a")), client("a", a));

    delivery.run(LocalDate.of(2026, 11, 18));
    RunReport second = delivery.run(FROM);
    RunReport third = delivery.run(FROM);

    assertThat(sources(a)).containsExactlyInAnyOrder("11", "12");
    assertThat(account(report(second), "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 2, 1, 1, 0, 0, 0, null));
    assertThat(account(third, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 2, 0, 2, 0, 0, 0, null));
  }

  private static RunReport report(RunReport report) {
    return report;
  }

  @Test
  void reportsADraftThatHoldsTheTransactionIdAndNeitherSubmitsNorDuplicatesIt() {
    FakeErpNext a =
        erp("a")
            .bankAccount("Main - A", A1, true)
            .existing(id(A1, "11", "2026-11-20", "1.00"), "Main - A", 0);
    source
        .account("1", A1)
        .booked("1", "11", "2026-11-20", "1.00")
        .booked("1", "12", "2026-11-21", "2.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(ids(a))
        .containsExactlyInAnyOrder(
            id(A1, "11", "2026-11-20", "1.00"), id(A1, "12", "2026-11-21", "2.00"));
    assertThat(a.transactions().getFirst().get("docstatus").asInt()).isZero();
    assertThat(a.requests()).noneMatch(request -> request.method().equals("PUT"));
    assertThat(account(report, "a", A1))
        .isEqualTo(
            new AccountReport(
                "DE17…0001",
                2,
                1,
                0,
                0,
                1,
                0,
                "ERPNext a holds "
                    + id(A1, "11", "2026-11-20", "1.00")
                    + " as a draft; submit or delete it there"));
    assertThat(report.targets().getFirst().error()).isNull();
  }

  @Test
  void reportsANewTransactionThatERPNextKeptAsDraftWithoutSubmittingIt() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true).insertAsDraft();
    source.account("1", A1).booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(a.transactions().getFirst().get("docstatus").asInt()).isZero();
    assertThat(a.requests()).noneMatch(request -> request.method().equals("PUT"));
    assertThat(account(report, "a", A1).failed()).isEqualTo(1);
    assertThat(account(report, "a", A1).error())
        .isEqualTo(
            "ERPNext a kept "
                + id(A1, "11", "2026-11-20", "1.00")
                + " as a draft; submit or delete it there");
  }

  @Test
  void leavesACancelledTransactionAloneAndCountsItAsExisting() {
    FakeErpNext a =
        erp("a")
            .bankAccount("Main - A", A1, true)
            .existing(id(A1, "11", "2026-11-20", "1.00"), "Main - A", 2);
    source.account("1", A1).booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(a.transactions()).hasSize(1);
    assertThat(account(report, "a", A1).existing()).isEqualTo(1);
  }

  @Test
  void refusesToDeliverWithoutExactlyOneCompanyBankAccountForTheIban() {
    FakeErpNext a =
        erp("a")
            .bankAccount("Supplier - A", A1, false)
            .bankAccount("Main - A", "de87 1234 5678 0000 0000 02", true)
            .bankAccount("Copy - A", A2, true);
    source
        .account("1", A1)
        .account("2", A2)
        .booked("1", "11", "2026-11-20", "1.00")
        .booked("2", "21", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a"), route(A2, "a")), client("a", a)).run(FROM);

    assertThat(a.transactions()).isEmpty();
    assertThat(account(report, "a", A1))
        .isEqualTo(
            new AccountReport(
                "DE17…0001",
                1,
                0,
                0,
                0,
                1,
                0,
                "ERPNext a has no company bank account with this IBAN"));
    assertThat(account(report, "a", A2))
        .isEqualTo(
            new AccountReport(
                "DE87…0002",
                1,
                0,
                0,
                0,
                1,
                0,
                "ERPNext a has 2 company bank accounts with this IBAN"));
  }

  @Test
  void aTargetThatIsDownOrRejectsTheKeyFailsOnlyItsOwnAccounts() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    FakeErpNext b = erp("b").bankAccount("Main - B", B1, true);
    FakeErpNext c = erp("c").bankAccount("Main - C", C1, true);
    b.override(request -> Response.json(503, "{}"));
    source
        .account("1", A1)
        .account("3", B1)
        .account("4", C1)
        .booked("1", "11", "2026-11-20", "1.00")
        .booked("3", "31", "2026-11-20", "1.00")
        .booked("4", "41", "2026-11-20", "1.00");
    ErpNextClient wrongKeyForC = client("c-wrong", c);

    RunReport report =
        new Delivery(
                source,
                List.of(route(A1, "a"), route(B1, "b"), route(C1, "c-wrong")),
                List.of(client("a", a), client("b", b), wrongKeyForC),
                CLOCK)
            .run(FROM);

    assertThat(sources(a)).containsExactly("11");
    assertThat(b.transactions()).isEmpty();
    assertThat(c.transactions()).isEmpty();
    assertThat(report.successful()).isFalse();
    assertThat(account(report, "a", A1).delivered()).isEqualTo(1);
    assertThat(account(report, "b", B1))
        .isEqualTo(
            new AccountReport(
                "DE60…0003", 1, 0, 0, 0, 1, 0, "ERPNext b is unavailable (HTTP 503)"));
    assertThat(report.targets())
        .filteredOn(t -> t.name().equals("c-wrong"))
        .singleElement()
        .satisfies(t ->
            assertThat(t.error()).isEqualTo("ERPNext c-wrong rejected the API key (HTTP 401)"));
    assertThat(report.targets())
        .filteredOn(t -> t.name().equals("a"))
        .singleElement()
        .satisfies(t -> assertThat(t.error()).isNull());
  }

  @Test
  void aSlowTargetDoesNotDelayTheOthers() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    FakeErpNext slow = erp("slow").bankAccount("Main - S", B1, true);
    slow.override(request -> {
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    });
    source
        .account("1", A1)
        .account("3", B1)
        .booked("1", "11", "2026-11-20", "1.00")
        .booked("3", "31", "2026-11-20", "1.00");
    List<Long> deliveredAt = new java.util.concurrent.CopyOnWriteArrayList<>();
    long start = System.nanoTime();
    a.override(request -> {
      if (request.method().equals("POST")) {
        deliveredAt.add(System.nanoTime() - start);
      }
      return null;
    });

    RunReport report =
        delivery(
                List.of(route(A1, "a"), route(B1, "slow")),
                client("a", a),
                client("slow", slow, Duration.ofMillis(500)))
            .run(FROM);

    assertThat(sources(a)).containsExactly("11");
    assertThat(deliveredAt)
        .singleElement()
        .satisfies(nanos -> assertThat(nanos).isLessThan(2_000_000_000L));
    assertThat(account(report, "slow", B1).error())
        .startsWith("ERPNext slow cannot be reached: HttpTimeoutException");
  }

  @Test
  void aRejectedDocumentFailsOnlyItselfAndARedirectNeverCarriesTheToken() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    try (FakeHttpServer elsewhere = FakeHttpServer.http()) {
      a.override(request -> {
        if (request.method().equals("POST") && request.body().contains("Invoice 11")) {
          return Response.json(417, "{\"exc_type\":\"ValidationError\"}");
        }
        if (request.method().equals("POST") && request.body().contains("Invoice 13")) {
          return new Response(
              302, "text/plain", "", Map.of("Location", elsewhere.uri() + "/steal"));
        }
        return null;
      });
      source
          .account("1", A1)
          .booked("1", "11", "2026-11-20", "1.00")
          .booked("1", "12", "2026-11-21", "2.00")
          .booked("1", "13", "2026-11-22", "3.00")
          .booked("1", "14", "2026-11-23", "4.00");

      RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

      assertThat(sources(a)).containsExactly("12");
      assertThat(elsewhere.requests()).isEmpty();
      AccountReport account = account(report, "a", A1);
      assertThat(account.delivered()).isEqualTo(1);
      assertThat(account.failed()).isEqualTo(3);
      assertThat(account.error())
          .isEqualTo(
              "ERPNext a answered with a redirect (HTTP 302), which Bankbote does not follow");
    }
  }

  @Test
  void stopsATargetThatDropsTheTransactionId() {
    FakeErpNext a =
        erp("a")
            .bankAccount("Main - A", A1, true)
            .bankAccount("Savings - A", A2, true)
            .dropTransactionId();
    source
        .account("1", A1)
        .account("2", A2)
        .booked("1", "11", "2026-11-20", "1.00")
        .booked("1", "12", "2026-11-20", "1.00")
        .booked("2", "21", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a"), route(A2, "a")), client("a", a)).run(FROM);

    assertThat(a.transactions()).hasSize(1);
    assertThat(report.targets().getFirst().error()).contains("did not keep the transaction_id");
    assertThat(account(report, "a", A1).failed()).isEqualTo(2);
    assertThat(account(report, "a", A2))
        .isEqualTo(
            new AccountReport("DE87…0002", 1, 0, 0, 0, 1, 0, report.targets().getFirst().error()));
  }

  @Test
  void reportsAnAccountTheSourceDoesNotHaveOrHoldsTwiceOrCannotRead() {
    FakeErpNext a =
        erp("a")
            .bankAccount("Main - A", A1, true)
            .bankAccount("Savings - A", A2, true)
            .bankAccount("Third - A", B1, true);
    source
        .account("2", A2)
        .account("3", A2)
        .account("4", B1)
        .failing("4", "Hibiscus Server answered HTTP 500");

    RunReport report =
        delivery(List.of(route(A1, "a"), route(A2, "a"), route(B1, "a")), client("a", a)).run(FROM);

    assertThat(account(report, "a", A1).error())
        .isEqualTo("the source has no account with this IBAN");
    assertThat(account(report, "a", A2).error())
        .isEqualTo("the source has 2 accounts with this IBAN");
    assertThat(account(report, "a", B1))
        .isEqualTo(
            new AccountReport("DE60…0003", 0, 0, 0, 0, 0, 0, "Hibiscus Server answered HTTP 500"));
    assertThat(a.requests()).isEmpty();
  }

  @Test
  void aFailingSourceEndsTheRunWithItsError() {
    source.failingAccounts("Hibiscus Server rejected the master password (HTTP 401)");
    FakeErpNext a = erp("a");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(report.sourceError())
        .isEqualTo("Hibiscus Server rejected the master password (HTTP 401)");
    assertThat(report.successful()).isFalse();
    assertThat(report.targets()).isEmpty();
    assertThat(a.requests()).isEmpty();
  }

  @Test
  void anAccountWithNothingToDeliverDoesNotAskTheTarget() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source.account("1", A1).pending("1", "90");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(a.requests()).isEmpty();
    assertThat(account(report, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 1, 0, 0, 1, 0, 0, null));
    assertThat(report.from()).isEqualTo(FROM);
    assertThat(report.startedAt()).isEqualTo(CLOCK.instant());
    assertThat(report.finishedAt()).isEqualTo(CLOCK.instant());
  }

  @Test
  void countsAnIdHeldTwiceAsExisting() {
    FakeErpNext a =
        erp("a")
            .bankAccount("Main - A", A1, true)
            .existing(id(A1, "11", "2026-11-20", "1.00"), "Main - A", 1)
            .existing(id(A1, "11", "2026-11-20", "1.00"), "Main - A", 0);
    source.account("1", A1).booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(account(report, "a", A1).existing()).isEqualTo(1);
    assertThat(a.transactions())
        .extracting(doc -> doc.get("docstatus").asInt())
        .containsExactly(1, 0);
  }

  @Test
  void anUnexpectedDocstatusCountsAsFailed() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    a.override(request ->
        request.method().equals("POST")
            ? Response.json(
                200,
                "{\"data\":{\"name\":\"X\",\"transaction_id\":\""
                    + id(A1, "11", "2026-11-20", "1.00")
                    + "\",\"docstatus\":2}}")
            : null);
    source.account("1", A1).booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(account(report, "a", A1).failed()).isEqualTo(1);
    assertThat(account(report, "a", A1).error())
        .isEqualTo("ERPNext a answered docstatus 2 for a new bank transaction");
  }

  @Test
  void documentsGoOutAsMapped() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source.account("1", A1).booked("1", "11", "2026-11-20", "-10.00");

    delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    ObjectNode doc = a.transactions().getFirst();
    assertThat(doc.get("withdrawal").decimalValue()).isEqualByComparingTo("10.00");
    assertThat(doc.get("currency").asString()).isEqualTo("EUR");
    assertThat(doc.get("date").asString()).isEqualTo("2026-11-20");
  }

  @Test
  void anUnexpectedErrorOfATargetRunEndsTheRun() {
    com.encircle360.oss.bankbote.source.TransactionSource broken =
        new com.encircle360.oss.bankbote.source.TransactionSource() {
          @Override
          public List<com.encircle360.oss.bankbote.source.SourceAccount> accounts() {
            return List.of(new com.encircle360.oss.bankbote.source.SourceAccount("1", A1, "EUR"));
          }

          @Override
          public com.encircle360.oss.bankbote.source.SourceEntries transactions(
              com.encircle360.oss.bankbote.source.SourceAccount account, LocalDate from) {
            throw new IllegalStateException("broken source");
          }
        };
    Delivery delivery =
        new Delivery(broken, List.of(route(A1, "a")), List.of(client("a", erp("a"))), CLOCK);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> delivery.run(FROM))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("a target run failed unexpectedly")
        .hasRootCauseMessage("broken source");
  }

  @Test
  void anInterruptedRunEndsWithAnError() throws InterruptedException {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    java.util.concurrent.CountDownLatch reached = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    a.override(request -> {
      reached.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    });
    source.account("1", A1).booked("1", "11", "2026-11-20", "1.00");
    Delivery delivery = delivery(List.of(route(A1, "a")), client("a", a));
    Thread runner = Thread.currentThread();
    // The run is interrupted while it waits for its target; an interrupt set before the run starts
    // goes unnoticed whenever the target has already finished.
    Thread interrupter =
        Thread.ofPlatform()
            .start(() -> {
              try {
                reached.await();
                runner.interrupt();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> delivery.run(FROM))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("interrupted during a run");
    } finally {
      release.countDown();
      Thread.interrupted();
      interrupter.join();
    }
  }

  @Test
  void deliversUnderTheIdentifierDerivedFromTheTransactionAndTheAccount() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source
        .account("1", A1)
        .booked("1", "11", "2026-11-20", "-10.00")
        .bookedWithBankId("1", "12", "2026-11-20", "-10.00", "TX-0012");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(ids(a))
        .containsExactly(
            id(A1, "11", "2026-11-20", "-10.00"),
            TransactionIds.transactionId(
                Iban.parse(A1),
                FakeSource.transaction("12", "2026-11-20", "-10.00", "Invoice 12", "TX-0012"),
                1));
    assertThat(ids(a).getFirst()).startsWith("b1-c-");
    assertThat(ids(a).getLast()).startsWith("b1-r-");
    assertThat(account(report, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 2, 2, 0, 0, 0, 1, null));
  }

  @Test
  void identicalTransactionsOfOneDayAreEachDeliveredOnceAndASecondRunDeliversNothing() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    SourceTransaction card = FakeSource.transaction("21", "2026-11-20", "-4.50", "Card", "");
    source
        .account("1", A1)
        .booked("1", card)
        .pending("1", "22")
        .booked("1", FakeSource.transaction("23", "2026-11-20", "-4.50", "Card", ""));
    Delivery delivery = delivery(List.of(route(A1, "a")), client("a", a));

    RunReport first = delivery.run(FROM);
    RunReport second = delivery.run(FROM);

    String key = TransactionIds.key(Iban.parse(A1), card);
    assertThat(ids(a)).containsExactly(key + "-1", key + "-2");
    assertThat(account(first, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 3, 2, 0, 1, 0, 0, null));
    assertThat(account(second, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 3, 0, 2, 1, 0, 0, null));
  }

  @Test
  void aLaterArrivalOfTheSameContentGetsTheNextNumberAndIsDelivered() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    SourceTransaction card = FakeSource.transaction("21", "2026-11-20", "-4.50", "Card", "");
    source.account("1", A1).booked("1", card);
    Delivery delivery = delivery(List.of(route(A1, "a")), client("a", a));
    delivery.run(FROM);

    source.booked("1", FakeSource.transaction("140", "2026-11-20", "-4.50", "Card", ""));
    RunReport later = delivery.run(FROM);

    String key = TransactionIds.key(Iban.parse(A1), card);
    assertThat(ids(a)).containsExactly(key + "-1", key + "-2");
    assertThat(account(later, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 2, 1, 1, 0, 0, 0, null));
  }

  @Test
  void aRebuiltSourceWithOtherIdsInAnotherOrderDeliversNothingAgain() {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source
        .account("1", A1)
        .booked("1", FakeSource.transaction("101", "2026-11-20", "-4.50", "Card", ""))
        .booked("1", FakeSource.transaction("102", "2026-11-20", "-4.50", "Card", ""))
        .booked("1", "103", "2026-11-21", "20.00")
        .bookedWithBankId("1", "104", "2026-11-22", "30.00", "TX-104");
    Delivery delivery = delivery(List.of(route(A1, "a")), client("a", a));
    delivery.run(FROM);
    List<String> delivered = ids(a);

    source
        .clear("1")
        .booked("1", FakeSource.transaction("9", "2026-11-22", "30.00", "Invoice 104", "TX-104"))
        .booked("1", FakeSource.transaction("2", "2026-11-20", "-4.50", "Card", ""))
        .booked("1", FakeSource.transaction("7", "2026-11-21", "20.00", "Invoice 103", ""))
        .booked("1", FakeSource.transaction("10", "2026-11-20", "-4.50", "Card", ""));
    RunReport again = delivery.run(FROM);

    assertThat(ids(a)).isEqualTo(delivered).hasSize(4);
    assertThat(account(again, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 4, 0, 4, 0, 0, 1, null));
  }

  @Test
  void deliversTransactionsThatShareABankIdWithOtherContentAndWarnsOnceWithoutContent(
      CapturedOutput output) {
    FakeErpNext a = erp("a").bankAccount("Main - A", A1, true);
    source
        .account("1", A1)
        .bookedWithBankId("1", "11", "2026-11-20", "-10.00", "TX-1")
        .bookedWithBankId("1", "12", "2026-11-20", "-10.00", "TX-1")
        .bookedWithBankId("1", "13", "2026-11-21", "-10.00", "TX-2");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(sources(a)).containsExactly("11", "12", "13");
    assertThat(ids(a)).doesNotHaveDuplicates();
    assertThat(account(report, "a", A1))
        .isEqualTo(new AccountReport("DE17…0001", 3, 3, 0, 0, 0, 3, null));
    assertThat(output.getAll().lines().filter(line -> line.contains("share a bank transaction")))
        .singleElement()
        .satisfies(line -> {
          assertThat(line)
              .contains(
                  "2 booked transaction(s) of account DE17…0001 share a bank transaction id with a"
                      + " transaction of other content");
          assertThat(line).doesNotContain(A1, "TX-1", "Invoice");
        });
  }

  @Test
  void identifiersOfTheEarlierSchemeAreNotRecognised() {
    FakeErpNext a =
        erp("a").bankAccount("Main - A", A1, true).existing("hibiscus:11", "Main - A", 1);
    source.account("1", A1).booked("1", "11", "2026-11-20", "1.00");

    RunReport report = delivery(List.of(route(A1, "a")), client("a", a)).run(FROM);

    assertThat(ids(a)).containsExactly("hibiscus:11", id(A1, "11", "2026-11-20", "1.00"));
    assertThat(account(report, "a", A1).delivered()).isEqualTo(1);
  }
}
