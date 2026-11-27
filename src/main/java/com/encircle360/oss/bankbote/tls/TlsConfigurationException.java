package com.encircle360.oss.bankbote.tls;

/** A configured certificate file is missing or does not hold what it must. */
public class TlsConfigurationException extends RuntimeException {

  public TlsConfigurationException(String message) {
    super(message);
  }
}
