package com.encircle360.oss.bankbote;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.delivery.RunReport;
import com.encircle360.oss.bankbote.testing.FakeErpNext;
import com.encircle360.oss.bankbote.testing.FakeHibiscus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service against a fake Hibiscus Server and three fake ERPNext installations, one per
 * company, each with its own key.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BankboteServiceTest {

  static final String A1 = "DE17123456780000000001";
  static final String A2 = "DE87123456780000000002";
  static final String B1 = "DE60123456780000000003";
  static final String C1 = "DE33123456780000000004";
  static final String UNMAPPED = "DE06123456780000000005";
  static final String MASTER_PASSWORD = "master-password-of-the-test";

  @TempDir static Path dir;
  static FakeHibiscus hibiscus;
  static FakeErpNext erpA;
  static FakeErpNext erpB;
  static FakeErpNext erpC;

  @Autowired RunCoordinator coordinator;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    String today = LocalDate.now(java.time.ZoneId.of("Europe/Berlin")).toString();
    String yesterday = LocalDate.now(java.time.ZoneId.of("Europe/Berlin")).minusDays(1).toString();
    String old = LocalDate.now(java.time.ZoneId.of("Europe/Berlin")).minusDays(40).toString();
    hibiscus =
        new FakeHibiscus(dir, MASTER_PASSWORD)
            .account("1", A1)
            .account("2", A2)
            .account("3", B1)
            .account("4", C1)
            .account("5", UNMAPPED)
            .booked("1", "101", yesterday, "-1234.56")
            .booked("1", "102", today, "1.0E7")
            .pending("1", "103", today, "-5.0")
            .booked("1", "104", old, "-9.99")
            .booked("2", "201", today, "20.0")
            .booked("3", "301", yesterday, "-30.5")
            .booked("4", "401", today, "40.0")
            .booked("5", "501", today, "-50.0");
    erpA =
        new FakeErpNext("key-a", "secret-a")
            .bankAccount("Main - A", A1, true)
            .bankAccount("Savings - A", A2, true);
    erpB = new FakeErpNext("key-b", "secret-b").bankAccount("Main - B", B1, true);
    erpC = new FakeErpNext("key-c", "secret-c").bankAccount("Main - C", C1, true);
    registry.add("bankbote.hibiscus.url", () -> hibiscus.server().uri().toString());
    registry.add("bankbote.hibiscus.certificate", () -> hibiscus.certificate().toString());
    registry.add(
        "bankbote.hibiscus.password-file", () -> write("master-password", MASTER_PASSWORD));
    registry.add("bankbote.lookback-days", () -> "14");
    registry.add("bankbote.source-key", () -> "hibiscus");
    registry.add("bankbote.schedule", () -> "-");
    registry.add("bankbote.notify-delay", () -> "100ms");
    List<FakeErpNext> erps = List.of(erpA, erpB, erpC);
    List<String> names = List.of("a", "b", "c");
    for (int i = 0; i < 3; i++) {
      String name = names.get(i);
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
    String[][] accounts = {
      {A1, "company-a"}, {A2, "company-a"}, {B1, "company-b"}, {C1, "company-c"}
    };
    for (int i = 0; i < accounts.length; i++) {
      String[] account = accounts[i];
      registry.add("bankbote.accounts[" + i + "].iban", () -> account[0]);
      registry.add("bankbote.accounts[" + i + "].target", () -> account[1]);
    }
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
    hibiscus.close();
    erpA.close();
    erpB.close();
    erpC.close();
  }

  private RunReport awaitRun(Optional<RunReport> before) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<RunReport> report = coordinator.lastReport();
      if (report.isPresent() && !report.equals(before) && !coordinator.running()) {
        return report.get();
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no run ended");
  }

  private static List<String> ids(FakeErpNext erp) {
    return erp.transactions().stream().map(doc -> doc.get("transaction_id").asString()).toList();
  }

  private static List<String> purposes(FakeErpNext erp) {
    return erp.transactions().stream().map(doc -> doc.get("description").asString()).toList();
  }

  private HttpResponse<String> call(String method, String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .method(
                  method, HttpRequest.BodyPublishers.ofString("context={\"iban\":\"" + A1 + "\"}"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  @Order(1)
  void theRunAtStartDeliversEachAccountOnlyToTheErpNextOfItsCompany() throws Exception {
    RunReport report = awaitRun(Optional.empty());

    assertThat(purposes(erpA))
        .containsExactlyInAnyOrder("SVWZ+Invoice 101", "SVWZ+Invoice 102", "SVWZ+Invoice 201");
    assertThat(purposes(erpB)).containsExactly("SVWZ+Invoice 301");
    assertThat(purposes(erpC)).containsExactly("SVWZ+Invoice 401");
    for (FakeErpNext erp : List.of(erpA, erpB, erpC)) {
      assertThat(ids(erp)).allMatch(id -> id.matches("b1-c-[0-9a-f]{32}-1"));
    }
    assertThat(report.unmapped()).extracting(u -> u.iban()).containsExactly("DE06…0005");
    for (FakeErpNext erp : List.of(erpA, erpB, erpC)) {
      String expected =
          erp == erpA
              ? "token key-a:secret-a"
              : erp == erpB ? "token key-b:secret-b" : "token key-c:secret-c";
      assertThat(erp.requests())
          .allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(expected));
      assertThat(erp.transactions())
          .allSatisfy(doc -> assertThat(doc.get("docstatus").asInt()).isEqualTo(1));
    }
    JsonNode large =
        erpA.transactions().stream()
            .filter(doc -> doc.get("description").asString().equals("SVWZ+Invoice 102"))
            .findFirst()
            .orElseThrow();
    assertThat(large.get("deposit").decimalValue()).isEqualByComparingTo("10000000.00");
    assertThat(report.successful()).isTrue();
  }

  @Test
  @Order(2)
  void theHealthEndpointShowsTheLastRunPerTargetWithoutSecretsOrPersonalData() throws Exception {
    HttpResponse<String> response = call("GET", "/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode health = JsonMapper.builder().build().readTree(response.body());
    JsonNode run = health.at("/components/delivery/details/lastRun");
    assertThat(run.get("successful").asBoolean()).isTrue();
    assertThat(run.get("targets")).hasSize(3);
    assertThat(run.at("/targets/0/name").asString()).isEqualTo("company-a");
    assertThat(run.at("/targets/0/accounts/0/iban").asString()).isEqualTo("DE17…0001");
    assertThat(run.at("/targets/0/accounts/0/delivered").asInt()).isEqualTo(2);
    assertThat(run.at("/targets/0/accounts/0/pending").asInt()).isEqualTo(1);
    assertThat(response.body())
        .doesNotContain(
            MASTER_PASSWORD,
            "secret-a",
            "secret-b",
            "key-a",
            A1,
            A2,
            B1,
            C1,
            UNMAPPED,
            "Invoice",
            "Example Customer");
  }

  @Test
  @Order(3)
  void aNotificationOfHibiscusStartsARunThatFindsEverythingDelivered(CapturedOutput output)
      throws Exception {
    Optional<RunReport> before = coordinator.lastReport();

    HttpResponse<String> response = call("POST", "/notify");

    assertThat(response.statusCode()).isEqualTo(202);
    RunReport report = awaitRun(before);
    assertThat(report.targets().getFirst().accounts().getFirst().existing()).isEqualTo(2);
    assertThat(ids(erpA)).hasSize(3);
    assertThat(output.getAll())
        .contains("company-a", "DE17…0001")
        .doesNotContain(
            MASTER_PASSWORD,
            "secret-a",
            "secret-b",
            "secret-c",
            A1,
            B1,
            UNMAPPED,
            "Invoice",
            "Example Customer");
  }

  @Test
  @Order(4)
  void aRunCanBeAskedForAndOnlyByPost() throws Exception {
    Optional<RunReport> before = coordinator.lastReport();

    assertThat(call("POST", "/run").statusCode()).isEqualTo(202);
    awaitRun(before);
    assertThat(call("GET", "/notify").statusCode()).isEqualTo(405);
    assertThat(call("GET", "/actuator/env").statusCode()).isEqualTo(404);
    assertThat(call("GET", "/actuator/configprops").statusCode()).isEqualTo(404);
  }
}
