package com.encircle360.oss.bankbote.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.oss.bankbote.BankboteApplication;
import com.encircle360.oss.bankbote.config.InvalidConfigurationException;
import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.delivery.RunReport;
import com.encircle360.oss.bankbote.testing.TestCertificate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The probe port, the only listener meant to be reachable from outside the network namespace: it
 * answers the two probes with their status and nothing else, while the main port keeps everything.
 * The Hibiscus Server does not answer, so every run fails and the health component delivery is
 * down.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ProbePortTest {

  @TempDir static Path dir;
  static int probePort;
  private static List<String> configuration;

  @Autowired RunCoordinator coordinator;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    probePort = freePort();
    configuration()
        .forEach(line -> {
          int equals = line.indexOf('=');
          registry.add(line.substring(0, equals), () -> line.substring(equals + 1));
        });
    registry.add("bankbote.probes.port", () -> probePort);
    registry.add("bankbote.probes.address", () -> "127.0.0.1");
  }

  /** A configuration whose Hibiscus Server is a port nobody listens on. */
  static List<String> configuration() {
    if (configuration != null) {
      return configuration;
    }
    try {
      Path pem = TestCertificate.create(dir, "hibiscus").pem();
      Path secret = Files.writeString(dir.resolve("secret"), "probe-secret\n");
      configuration =
          List.of(
              "bankbote.schedule=-",
              "bankbote.hibiscus.url=https://127.0.0.1:1",
              "bankbote.hibiscus.certificate=" + pem,
              "bankbote.hibiscus.password-file=" + secret,
              "bankbote.targets[0].name=company-a",
              "bankbote.targets[0].url=https://erp-a.example.com",
              "bankbote.targets[0].api-key-file=" + secret,
              "bankbote.targets[0].api-secret-file=" + secret,
              "bankbote.accounts[0].iban=DE17123456780000000001",
              "bankbote.accounts[0].target=company-a");
      return configuration;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  static HttpResponse<String> call(int port, String method, String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .method(method, HttpRequest.BodyPublishers.noBody())
              .timeout(Duration.ofSeconds(10))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private RunReport awaitFailedRun() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<RunReport> report = coordinator.lastReport();
      if (report.isPresent() && !coordinator.running()) {
        return report.get();
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no run ended");
  }

  @Test
  void theProbesAnswerWithTheirStatusAloneAndStayUpWhenTheLastRunFailed() throws Exception {
    assertThat(awaitFailedRun().successful()).isFalse();

    for (String probe : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
      HttpResponse<String> response = call(probePort, "GET", probe);
      assertThat(response.statusCode()).as(probe).isEqualTo(200);
      assertThat(response.body()).as(probe).isEqualTo("{\"status\":\"UP\"}");
      assertThat(call(probePort, "HEAD", probe).statusCode()).as(probe).isEqualTo(200);
    }
  }

  @Test
  void nothingElseAnswersOnTheProbePort() throws Exception {
    List<String> refused = new ArrayList<>();
    String[][] calls = {
      {"POST", "/run"},
      {"POST", "/notify"},
      {"GET", "/notify"},
      {"GET", "/actuator/health"},
      {"GET", "/actuator/health/delivery"},
      {"GET", "/actuator"},
      {"GET", "/actuator/env"},
      {"POST", "/actuator/health/liveness"},
      {"GET", "/actuator/health/liveness/"},
      {"GET", "/actuator/health/liveness;x=1"},
      {"GET", "/actuator/health/%6civeness"},
      {"GET", "/actuator/health/liveness/../../health"},
      {"GET", "/"}
    };
    for (String[] call : calls) {
      HttpResponse<String> response = call(probePort, call[0], call[1]);
      if (response.statusCode() == 404 && response.body().isEmpty()) {
        refused.add(call[0] + " " + call[1]);
      }
    }

    assertThat(refused).hasSize(calls.length);
  }

  @Test
  void theMainPortKeepsTheRunEndpointsAndTheDetailsOfTheLastRun() throws Exception {
    awaitFailedRun();

    HttpResponse<String> health = call(port, "GET", "/actuator/health");
    assertThat(health.statusCode()).isEqualTo(503);
    assertThat(health.body()).contains("\"lastRun\"", "Hibiscus Server cannot be reached");
    assertThat(call(port, "GET", "/actuator/health/liveness").body())
        .isEqualTo("{\"status\":\"UP\"}");
    assertThat(call(port, "POST", "/run").statusCode()).isEqualTo(202);
  }

  @Test
  void withoutAnAddressTheProbePortListensOnEveryAddress() throws Exception {
    int other = freePort();
    List<String> properties = new ArrayList<>(configuration());
    properties.add("server.port=0");
    properties.add("bankbote.probes.port=" + other);

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(BankboteApplication.class)
            .properties(properties.toArray(String[]::new))
            .run()) {
      assertThat(context.isRunning()).isTrue();
      assertThat(call(other, "GET", "/actuator/health/liveness").statusCode()).isEqualTo(200);
      assertThat(call(other, "POST", "/run").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void aBrokenProbePortOrAManagementServerStopsTheStart(CapturedOutput output) {
    List<String> properties = new ArrayList<>(configuration());
    properties.add("server.port=0");
    properties.add("bankbote.probes.port=70000");
    properties.add("management.server.port=8090");

    assertThatThrownBy(() ->
            new SpringApplicationBuilder(BankboteApplication.class)
                .properties(properties.toArray(String[]::new))
                .run())
        .hasRootCauseInstanceOf(InvalidConfigurationException.class);

    assertThat(output.getAll())
        .contains("APPLICATION FAILED TO START")
        .contains("bankbote.probes.port must be between 1 and 65535")
        .contains("management.server.port is not supported");
  }
}
