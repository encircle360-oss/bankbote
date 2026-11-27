package com.encircle360.oss.bankbote.config;

import java.util.List;

/** The configuration has problems; Bankbote does not start. */
public class InvalidConfigurationException extends RuntimeException {

  private final List<String> problems;

  public InvalidConfigurationException(List<String> problems) {
    super(
        "the configuration of Bankbote has "
            + problems.size()
            + " problem(s):\n  - "
            + String.join("\n  - ", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
