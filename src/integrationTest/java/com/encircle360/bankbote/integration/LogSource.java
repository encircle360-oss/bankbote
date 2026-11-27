package com.encircle360.bankbote.integration;

/** A server whose log a failed test reports, with every secret masked. */
public interface LogSource {

  /** The last lines of the log, headed by where they come from. */
  String excerpt(int lines);
}
