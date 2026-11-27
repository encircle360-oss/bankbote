package com.encircle360.bankbote.erpnext;

/**
 * A request to an ERPNext failed. The message names the target and the HTTP status, never the
 * body of the answer, which can hold personal data, and never a credential.
 */
public class ErpNextException extends Exception {

  /** What went wrong, which decides whether Bankbote goes on with the target. */
  public enum Kind {
    /** The target cannot be reached, times out, fails or redirects: the rest of the account waits. */
    UNAVAILABLE,
    /** The target refuses the API key: nothing more goes to it in this run. */
    AUTHENTICATION,
    /** The target refuses this one document: the next one is tried. */
    REJECTED,
    /** The target answers against what Bankbote relies on: nothing more goes to it in this run. */
    INCONSISTENT
  }

  private final Kind kind;

  public ErpNextException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** Whether nothing more should go to the target in this run. */
  public boolean stopsTheTarget() {
    return kind == Kind.AUTHENTICATION || kind == Kind.INCONSISTENT;
  }
}
