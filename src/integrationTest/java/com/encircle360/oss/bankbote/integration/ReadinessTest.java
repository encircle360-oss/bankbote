package com.encircle360.oss.bankbote.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.oss.bankbote.testing.FakeHttpServer;
import com.encircle360.oss.bankbote.testing.FakeHttpServer.Response;
import com.encircle360.oss.bankbote.testing.TestCertificate;
import com.encircle360.oss.bankbote.tls.TlsContexts;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wait for a server that goes through the phases of a start, each a real network failure. */
@Tag("hibiscus")
class ReadinessTest {

  @TempDir Path dir;

  private static int get(SSLContext context, URI uri) throws IOException, InterruptedException {
    try (HttpClient http =
        HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(2)).build()) {
      return http.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }
  }

  @Test
  void waitsThroughRefusedConnectionsTerminatedHandshakesTimeoutsAndServerErrors()
      throws Exception {
    TestCertificate certificate = TestCertificate.create(dir, "server");
    SSLContext pinned = TlsContexts.pinned(certificate.pem());
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = probe.getLocalPort();
    }
    try (ServerSocket hangUp = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        FakeHttpServer server = FakeHttpServer.https(certificate.serverContext())) {
      Thread.ofVirtual()
          .start(() -> {
            while (!hangUp.isClosed()) {
              try (Socket socket = hangUp.accept()) {
                socket.getInputStream().read();
              } catch (IOException e) {
                return;
              }
            }
          });
      AtomicInteger served = new AtomicInteger();
      server.handler(request ->
          served.incrementAndGet() < 3 ? Response.json(500, "") : Response.json(200, "[]"));
      List<URI> phases =
          List.of(
              URI.create("https://127.0.0.1:" + closedPort + "/"),
              URI.create("https://127.0.0.1:" + hangUp.getLocalPort() + "/"),
              URI.create("https://127.0.0.1:" + silent.getLocalPort() + "/"),
              server.uri(),
              server.uri(),
              server.uri());
      AtomicInteger attempt = new AtomicInteger();

      int attempts =
          Readiness.await(
              () -> get(pinned, phases.get(Math.min(attempt.getAndIncrement(), phases.size() - 1))),
              Duration.ofSeconds(30),
              Duration.ofMillis(10));

      assertThat(attempts).isEqualTo(6);
    }
  }

  @Test
  void givesUpAtTheDeadlineWithTheLastFailure() {
    assertThatThrownBy(() ->
            Readiness.await(
                () -> {
                  throw new java.net.ConnectException("Connection refused");
                },
                Duration.ofMillis(200),
                Duration.ofMillis(20)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("the server is not ready after PT0.2S and ")
        .hasMessageEndingWith("last: ConnectException: Connection refused");
  }

  @Test
  void stopsAtOnceAtAnAnswerThatWaitingCannotChange() {
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() ->
            Readiness.await(
                () -> {
                  attempts.incrementAndGet();
                  return 401;
                },
                Duration.ofSeconds(10),
                Duration.ofMillis(10)))
        .hasMessage("the server answered HTTP 401");
    assertThat(attempts).hasValue(1);
  }
}
