package com.encircle360.oss.bankbote;

import static com.encircle360.oss.bankbote.integration.HibiscusFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.oss.bankbote.delivery.AccountReport;
import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.delivery.RunReport;
import com.encircle360.oss.bankbote.integration.ErpNextFixtures;
import com.encircle360.oss.bankbote.integration.ErpNextFixtures.ApiKey;
import com.encircle360.oss.bankbote.integration.ErpNextServer;
import com.encircle360.oss.bankbote.integration.FrappeAdmin;
import com.encircle360.oss.bankbote.integration.HibiscusFixtures;
import com.encircle360.oss.bankbote.integration.HibiscusServer;
import com.encircle360.oss.bankbote.integration.ServerLogOnFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The whole chain: a real Hibiscus Server with fixtures in its database, Bankbote, and a real
 * ERPNext 16 with two companies on one site, reached with the narrow key of the service user.
 */
@Tag("erpnext")
@SpringBootTest
class BankboteChainIT {

  static final String A1 = "DE17123456780000000001";
  static final String B1 = "DE60123456780000000003";

  @TempDir static Path dir;
  static HibiscusServer hibiscus;
  static ErpNextServer erpnext;
  static FrappeAdmin admin;
  static String accountA;
  static String accountB;
  static String booked;
  static String bookedB;

  @RegisterExtension
  static final ServerLogOnFailure LOG_ON_FAILURE =
      new ServerLogOnFailure(() -> hibiscus, () -> erpnext);

  @Autowired RunCoordinator coordinator;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException, SQLException {
    erpnext = ErpNextServer.start(Files.createDirectory(dir.resolve("erpnext-secrets")));
    admin = erpnext.admin();
    ErpNextFixtures erp = new ErpNextFixtures(admin);
    erp.setUp("Beispiel GmbH", "BG");
    erp.company("Zweite GmbH", "ZG");
    erp.bank("Beispielbank");
    accountA = erp.bankAccount("Beispiel GmbH", "BG", "Girokonto", "Beispielbank", A1, true);
    accountB = erp.bankAccount("Zweite GmbH", "ZG", "Geschäftskonto", "Beispielbank", B1, true);
    ApiKey key = erp.serviceUser("bankbote@example.com");

    hibiscus = HibiscusServer.start(Files.createDirectory(dir.resolve("hibiscus-secrets")));
    HibiscusFixtures fixtures = new HibiscusFixtures(hibiscus);
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Berlin"));
    String hibiscusA = fixtures.account(A1, "EUR");
    String hibiscusB = fixtures.account(B1, "EUR");
    booked =
        fixtures.insert(
            row(hibiscusA, today.minusDays(1), -1234.56)
                .purpose("Rechnung 2026-0042", "für Müller & Söhne", "Grüße €")
                .name("Müller & Söhne GmbH", null)
                .counterparty("DE87123456780000000002")
                .type("Überweisung")
                .references("E2E-42", "NONREF"));
    fixtures.insert(row(hibiscusA, today, -5).purpose("vorgemerkt", null, null).flags(2));
    bookedB =
        fixtures.insert(row(hibiscusB, today, 12_345_678.9).purpose("Großbetrag", null, null));
    Path certificate =
        HibiscusServer.writePem(hibiscus.certificateFromKeyStore(), dir.resolve("hibiscus.pem"));

    registry.add("bankbote.hibiscus.url", () -> hibiscus.url().toString());
    registry.add("bankbote.hibiscus.certificate", certificate::toString);
    registry.add(
        "bankbote.hibiscus.password-file", () -> hibiscus.writeMasterPassword(dir).toString());
    registry.add("bankbote.schedule", () -> "-");
    registry.add("server.port", () -> "0");
    registry.add("bankbote.targets[0].name", () -> "beispiel-gruppe");
    registry.add("bankbote.targets[0].url", () -> erpnext.url().toString());
    registry.add("bankbote.targets[0].allow-plain-http", () -> "true");
    registry.add("bankbote.targets[0].api-key-file", () -> write("api-key", key.key()));
    registry.add("bankbote.targets[0].api-secret-file", () -> write("api-secret", key.secret()));
    registry.add("bankbote.accounts[0].iban", () -> A1);
    registry.add("bankbote.accounts[0].target", () -> "beispiel-gruppe");
    registry.add("bankbote.accounts[1].iban", () -> B1);
    registry.add("bankbote.accounts[1].target", () -> "beispiel-gruppe");
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
    if (hibiscus != null) {
      hibiscus.close();
    }
    if (erpnext != null) {
      erpnext.close();
    }
  }

  private RunReport awaitRun(Optional<RunReport> before) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<RunReport> report = coordinator.lastReport();
      if (report.isPresent() && !report.equals(before) && !coordinator.running()) {
        return report.get();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("no run ended");
  }

  private static JsonNode byTransactionId(String transactionId) {
    JsonNode rows =
        admin.callForm(
            "frappe.client.get_list",
            java.util.Map.of(
                "doctype", "Bank Transaction",
                "filters", "[[\"transaction_id\",\"=\",\"" + transactionId + "\"]]",
                "fields", "[\"name\"]"));
    assertThat(rows).as(transactionId).hasSize(1);
    return admin.get("Bank Transaction", rows.get(0).path("name").asString());
  }

  @Test
  void deliversFromTheRealHibiscusServerIntoTheRealErpNextEachToItsCompanyOnce() throws Exception {
    RunReport first = awaitRun(Optional.empty());

    assertThat(first.successful()).as(first.toString()).isTrue();
    assertThat(first.targets().getFirst().accounts())
        .containsExactly(
            new AccountReport("DE17…0001", 2, 1, 0, 1, 0, null),
            new AccountReport("DE60…0003", 1, 1, 0, 0, 0, null));
    JsonNode a = byTransactionId("hibiscus:" + booked);
    assertThat(a.path("docstatus").asInt()).isEqualTo(1);
    assertThat(a.path("company").asString()).isEqualTo("Beispiel GmbH");
    assertThat(a.path("bank_account").asString()).isEqualTo(accountA);
    assertThat(a.path("withdrawal").decimalValue()).isEqualByComparingTo("1234.56");
    assertThat(a.path("description").asString())
        .isEqualTo("Rechnung 2026-0042\nfür Müller & Söhne\nGrüße €");
    assertThat(a.path("bank_party_name").asString()).isEqualTo("Müller & Söhne GmbH");
    assertThat(a.path("bank_party_iban").asString()).isEqualTo("DE87123456780000000002");
    assertThat(a.path("reference_number").asString()).isEqualTo("E2E-42");
    JsonNode b = byTransactionId("hibiscus:" + bookedB);
    assertThat(b.path("company").asString()).isEqualTo("Zweite GmbH");
    assertThat(b.path("deposit").decimalValue()).isEqualByComparingTo("12345678.90");

    Optional<RunReport> before = coordinator.lastReport();
    coordinator.requestRun();
    RunReport second = awaitRun(before);

    assertThat(second.targets().getFirst().accounts())
        .containsExactly(
            new AccountReport("DE17…0001", 2, 0, 1, 1, 0, null),
            new AccountReport("DE60…0003", 1, 0, 1, 0, 0, null));
    assertThat(byTransactionId("hibiscus:" + bookedB).path("bank_account").asString())
        .isEqualTo(accountB);
  }
}
