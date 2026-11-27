package com.encircle360.oss.bankbote.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Shows the problems of the configuration at start instead of a stack trace. */
public class InvalidConfigurationFailureAnalyzer
    extends AbstractFailureAnalyzer<InvalidConfigurationException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
    String message = cause.getMessage();
    return new FailureAnalysis(
        Character.toUpperCase(message.charAt(0)) + message.substring(1),
        "Correct the configuration under bankbote.*; every key is described in"
            + " docs/konfiguration.md.",
        cause);
  }
}
