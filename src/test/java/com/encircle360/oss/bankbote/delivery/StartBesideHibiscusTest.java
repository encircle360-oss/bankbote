package com.encircle360.oss.bankbote.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.oss.bankbote.account.Iban;
import com.encircle360.oss.bankbote.erpnext.ErpNextClient;
import com.encircle360.oss.bankbote.hibiscus.HibiscusClient;
import com.encircle360.oss.bankbote.hibiscus.HibiscusSource;
import com.encircle360.oss.bankbote.secret.Secret;
import com.encircle360.oss.bankbote.testing.FakeErpNext;
import com.encircle360.oss.bankbote.testing.FakeHibiscus;
import com.encircle360.oss.bankbote.testing.FakeHttpServer.Response;
import com.encircle360.oss.bankbote.testing.FlakyFront;
import com.encircle360.oss.bankbote.tls.TlsContexts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bankbote starting beside a Hibiscus Server that is still starting: the first run meets a port
 * that closes the TLS handshake or answers 500, and the repeated run delivers.
 */
class StartBesideHibiscusTest {

  private static final String A1 = "DE17123456780000000001";
  private static final Clock CLOCK = Clock.system(ZoneId.of("Europe/Berlin"));

  @TempDir Path dir;

  private RunCoordinator coordinator(
      FakeHibiscus hibiscus, java.net.URI url, FakeErpNext erp, BlockingQueue<RunReport> reports)
      throws Exception {
    Path password = Files.writeString(dir.resolve("master-password"), "master-of-test");
    HibiscusSource source =
        new HibiscusSource(
            new HibiscusClient(
                url,
                "admin",
                Secret.fromFile(password),
                TlsContexts.pinned(hibiscus.certificate()),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)),
            CLOCK);
    ErpNextClient client =
        new ErpNextClient(
            "a",
            erp.server().uri(),
            Secret.fromFile(Files.writeString(dir.resolve("key"), "key-a")),
            Secret.fromFile(Files.writeString(dir.resolve("secret"), "secret-a")),
            TlsContexts.system(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));
    Delivery delivery =
        new Delivery(source, List.of(new Route(Iban.parse(A1), "a")), List.of(client), CLOCK);
    return new RunCoordinator(delivery::run, CLOCK, 14, null, Duration.ofMillis(200), reports::add);
  }

  @Test
  void aTerminatedHandshakeAtTheFirstRunIsASourceNotReadableYetAndTheRepeatedRunDelivers()
      throws Exception {
    String today = LocalDate.now(CLOCK).toString();
    try (FakeHibiscus hibiscus =
            new FakeHibiscus(dir, "master-of-test")
                .account("1", A1)
                .booked("1", "11", today, "1.0");
        FlakyFront front = new FlakyFront(hibiscus.server().uri().getPort());
        FakeErpNext erp = new FakeErpNext("key-a", "secret-a").bankAccount("Main - A", A1, true)) {
      BlockingQueue<RunReport> reports = new LinkedBlockingQueue<>();
      try (RunCoordinator coordinator = coordinator(hibiscus, front.uri(), erp, reports)) {
        coordinator.requestRun();
        RunReport first = reports.poll(10, TimeUnit.SECONDS);
        front.forward();
        RunReport second = reports.poll(10, TimeUnit.SECONDS);

        assertThat(first.sourceError())
            .startsWith("Hibiscus Server cannot be reached: SSLHandshakeException");
        assertThat(second.successful()).isTrue();
        assertThat(second.targets().getFirst().accounts().getFirst().delivered()).isEqualTo(1);
      }
    }
  }

  @Test
  void aServerErrorAtTheFirstRunIsRepeatedTheSameWay() throws Exception {
    String today = LocalDate.now(CLOCK).toString();
    try (FakeHibiscus hibiscus =
            new FakeHibiscus(dir, "master-of-test")
                .account("1", A1)
                .booked("1", "11", today, "1.0");
        FakeErpNext erp = new FakeErpNext("key-a", "secret-a").bankAccount("Main - A", A1, true)) {
      AtomicInteger calls = new AtomicInteger();
      FakeHibiscusHandler.wrap(hibiscus, calls);
      BlockingQueue<RunReport> reports = new LinkedBlockingQueue<>();
      try (RunCoordinator coordinator =
          coordinator(hibiscus, hibiscus.server().uri(), erp, reports)) {
        coordinator.requestRun();
        RunReport first = reports.poll(10, TimeUnit.SECONDS);
        RunReport second = reports.poll(10, TimeUnit.SECONDS);

        assertThat(first.sourceError()).isEqualTo("Hibiscus Server answered HTTP 500");
        assertThat(second.successful()).isTrue();
        assertThat(erp.transactions()).hasSize(1);
      }
    }
  }

  /** Lets the first request of a fake Hibiscus Server answer 500, as the real one does at start. */
  private static final class FakeHibiscusHandler {
    static void wrap(FakeHibiscus hibiscus, AtomicInteger calls) {
      var server = hibiscus.server();
      var original = hibiscus.handlerForTests();
      server.handler(request ->
          calls.incrementAndGet() == 1
              ? Response.json(500, "no command found for REST url")
              : original.handle(request));
    }
  }
}
