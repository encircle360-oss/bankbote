package com.encircle360.oss.bankbote.web;

import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two ways to ask for a run over HTTP. Both take no input: the body Hibiscus Server sends is
 * never read, and the worst a caller can do is cause a run, which delivers nothing twice and
 * collapses with every other request.
 *
 * <p>Bankbote listens on the loopback address by default, so only processes in its own network
 * namespace, such as the Hibiscus Server next to it, can call it; Hibiscus Server cannot send a
 * credential with its notification. Should the server listen wider, for health checks from outside,
 * both endpoints still answer only to the loopback address.
 */
@RestController
public class TriggerController {

  private final RunCoordinator coordinator;

  public TriggerController(RunCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  /** The {@code notify.url} of Hibiscus Server: a run after the notification delay. */
  @PostMapping("/notify")
  public ResponseEntity<Void> notified(HttpServletRequest request) {
    if (!fromLoopback(request)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    coordinator.notified();
    return ResponseEntity.accepted().build();
  }

  /** A run as soon as possible. */
  @PostMapping("/run")
  public ResponseEntity<Void> run(HttpServletRequest request) {
    if (!fromLoopback(request)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    coordinator.requestRun();
    return ResponseEntity.accepted().build();
  }

  private static boolean fromLoopback(HttpServletRequest request) {
    try {
      return InetAddress.ofLiteral(request.getRemoteAddr()).isLoopbackAddress();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
