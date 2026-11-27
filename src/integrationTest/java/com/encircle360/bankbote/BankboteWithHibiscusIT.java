package com.encircle360.bankbote;

import static com.encircle360.bankbote.integration.HibiscusFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.bankbote.delivery.AccountReport;
import com.encircle360.bankbote.delivery.RunCoordinator;
import com.encircle360.bankbote.delivery.RunReport;
import com.encircle360.bankbote.integration.HibiscusFixtures;
import com.encircle360.bankbote.integration.HibiscusServer;
import com.encircle360.bankbote.integration.ServerLogOnFailure;
import com.encircle360.bankbote.testing.FakeErpNext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** The whole service against a real Hibiscus Server with fixtures and two fake ERPNext. */
@Tag("hibiscus")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class BankboteWithHibiscusIT {

  static final String A1 = "DE17123456780000000001";
  static final String B1 = "DE60123456780000000003";
  static final String UNMAPPED = "DE06123456780000000005";

  @TempDir static Path dir;
  static HibiscusServer hibiscus;

  @RegisterExtension
  static final ServerLogOnFailure LOG_ON_FAILURE = new ServerLogOnFailure(() -> hibiscus);

  static FakeErpNext erpA;
  static FakeErpNext erpB;
  static String booked;
  static String pending;
  static String bookedB;
  static String unmapped;

  @Autowired RunCoordinator coordinator;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException, SQLException {
    hibiscus = HibiscusServer.start(Files.createDirectory(dir.resolve("secrets")));
    HibiscusFixtures fixtures = new HibiscusFixtures(hibiscus);
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Berlin"));
    String accountA = fixtures.account(A1, "EUR");
    String accountB = fixtures.account(B1, "EUR");
    String accountU = fixtures.account(UNMAPPED, "EUR");
    booked =
        fixtures.insert(
            row(accountA, today.minusDays(1), -1234.56)
                .purpose("Rechnung 2026-0042", "für Müller & Söhne", "Grüße €")
                .name("Müller & Söhne GmbH", null)
                .counterparty("DE87123456780000000002")
                .type("Überweisung")
                .references("E2E-42", "NONREF"));
    pending = fixtures.insert(row(accountA, today, -5).purpose("vorgemerkt", null, null).flags(2));
    bookedB = fixtures.insert(row(accountB, today, 12_345_678.9).purpose("Großbetrag", null, null));
    unmapped = fixtures.insert(row(accountU, today, 1).purpose("nirgendwohin", null, null));

    Path certificate =
        HibiscusServer.writePem(hibiscus.certificateFromKeyStore(), dir.resolve("hibiscus.pem"));
    erpA = new FakeErpNext("key-a", "secret-a").bankAccount("Main - A", A1, true);
    erpB = new FakeErpNext("key-b", "secret-b").bankAccount("Main - B", B1, true);
    registry.add("bankbote.hibiscus.url", () -> hibiscus.url().toString());
    registry.add("bankbote.hibiscus.certificate", certificate::toString);
    registry.add(
        "bankbote.hibiscus.password-file", () -> hibiscus.writeMasterPassword(dir).toString());
    registry.add("bankbote.schedule", () -> "-");
    registry.add("bankbote.notify-delay", () -> "100ms");
    List<FakeErpNext> erps = List.of(erpA, erpB);
    for (int i = 0; i < erps.size(); i++) {
      String name = i == 0 ? "a" : "b";
      FakeErpNext erp = erps.get(i);
      registry.add("bankbote.targets[" + i + "].name", () -> "company-" + name);
      registry.add("bankbote.targets[" + i + "].url", () -> erp.server().uri().toString());
      registry.add("bankbote.targets[" + i + "].allow-plain-http", () -> "true");
      registry.add(
          "bankbote.targets[" + i + "].api-key-file", () -> write(name + ".key", "key-" + name));
      registry.add(
          "bankbote.targets[" + i + "].api-secret-file",
          () -> write(name + ".secret", "secret-" + name));
    }
    registry.add("bankbote.accounts[0].iban", () -> A1);
    registry.add("bankbote.accounts[0].target", () -> "company-a");
    registry.add("bankbote.accounts[1].iban", () -> B1);
    registry.add("bankbote.accounts[1].target", () -> "company-b");
  }

  private static String write(String name, String content) {
    try {
      return Files.writeString(dir.resolve(name), content + "\n").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterAll
  static void stop() {
    erpA.close();
    erpB.close();
    hibiscus.close();
  }

  private RunReport awaitRun(Optional<RunReport> before) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<RunReport> report = coordinator.lastReport();
      if (report.isPresent() && !report.equals(before) && !coordinator.running()) {
        return report.get();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no run ended");
  }

  private static List<String> ids(FakeErpNext erp) {
    return erp.transactions().stream().map(doc -> doc.get("transaction_id").asString()).toList();
  }

  @Test
  void deliversTheBookedTransactionsOfTheRealServerEachToItsCompanyAndNothingTwice(
      CapturedOutput output) throws Exception {
    RunReport first = awaitRun(Optional.empty());

    assertThat(first.successful()).isTrue();
    assertThat(ids(erpA)).containsExactly("hibiscus:" + booked);
    assertThat(ids(erpB)).containsExactly("hibiscus:" + bookedB);
    assertThat(first.unmapped()).extracting(u -> u.iban()).containsExactly("DE06…0005");
    AccountReport a = first.targets().getFirst().accounts().getFirst();
    assertThat(a).isEqualTo(new AccountReport("DE17…0001", 2, 1, 0, 1, 0, null));
    JsonNode doc = erpA.transactions().getFirst();
    assertThat(doc.get("withdrawal").decimalValue()).isEqualByComparingTo("1234.56");
    assertThat(doc.get("description").asString())
        .isEqualTo("Rechnung 2026-0042\nfür Müller & Söhne\nGrüße €");
    assertThat(doc.get("bank_party_name").asString()).isEqualTo("Müller & Söhne GmbH");
    assertThat(doc.get("bank_party_iban").asString()).isEqualTo("DE87123456780000000002");
    assertThat(doc.get("reference_number").asString()).isEqualTo("E2E-42");
    assertThat(doc.get("transaction_type").asString()).isEqualTo("Überweisung");
    assertThat(erpB.transactions().getFirst().get("deposit").decimalValue())
        .isEqualByComparingTo("12345678.90");

    Optional<RunReport> before = coordinator.lastReport();
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/notify"))
                  .POST(HttpRequest.BodyPublishers.ofString("context={}"))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertThat(response.statusCode()).isEqualTo(202);
    }
    RunReport second = awaitRun(before);

    assertThat(second.targets().getFirst().accounts().getFirst())
        .isEqualTo(new AccountReport("DE17…0001", 2, 0, 1, 1, 0, null));
    assertThat(ids(erpA)).hasSize(1);
    assertThat(ids(erpB)).hasSize(1);
    assertThat(output.getAll())
        .doesNotContain(hibiscus.masterPassword(), A1, B1, UNMAPPED, "Müller", "Rechnung");
  }
}
