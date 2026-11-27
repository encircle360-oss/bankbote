package com.encircle360.bankbote.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.bankbote.delivery.RunCoordinator;
import com.encircle360.bankbote.delivery.RunReport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TriggerControllerTest {

  private final AtomicInteger runs = new AtomicInteger();
  private final RunCoordinator coordinator =
      new RunCoordinator(
          from -> {
            runs.incrementAndGet();
            return new RunReport(null, null, from, null, List.of(), List.of());
          },
          Clock.systemUTC(),
          14,
          null,
          Duration.ofHours(1),
          report -> {});
  private final TriggerController controller = new TriggerController(coordinator);

  @AfterEach
  void stop() {
    coordinator.close();
  }

  private static MockHttpServletRequest from(String address) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/notify");
    request.setRemoteAddr(address);
    return request;
  }

  @Test
  void acceptsCallsFromTheOwnNetworkNamespace() {
    assertThat(controller.notified(from("127.0.0.1")).getStatusCode().value()).isEqualTo(202);
    assertThat(controller.notified(from("::1")).getStatusCode().value()).isEqualTo(202);
    assertThat(controller.run(from("127.0.0.1")).getStatusCode().value()).isEqualTo(202);
  }

  @Test
  void refusesCallsFromAnyOtherAddress() {
    assertThat(controller.notified(from("10.0.0.7")).getStatusCode().value()).isEqualTo(403);
    assertThat(controller.run(from("192.0.2.1")).getStatusCode().value()).isEqualTo(403);
    assertThat(controller.run(from("not-an-address")).getStatusCode().value()).isEqualTo(403);
    assertThat(runs).hasValue(0);
  }
}
