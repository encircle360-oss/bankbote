package com.encircle360.bankbote.delivery;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A run right after the start and then by schedule, as the fallback to the notifications. */
@Component
public class ScheduledRuns {

  private final RunCoordinator coordinator;

  public ScheduledRuns(RunCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void afterStart() {
    coordinator.requestRun();
  }

  @Scheduled(
      cron = "${bankbote.schedule:0 0 * * * *}",
      zone = "${bankbote.time-zone:Europe/Berlin}")
  public void bySchedule() {
    coordinator.requestRun();
  }
}
