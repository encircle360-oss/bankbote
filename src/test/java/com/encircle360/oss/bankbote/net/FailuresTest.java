package com.encircle360.oss.bankbote.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

class FailuresTest {

  @Test
  void namesTheKindOfFailureAndItsMessageWhenThereIsOne() {
    assertThat(Failures.describe(new ConnectException())).isEqualTo("ConnectException");
    assertThat(Failures.describe(new IOException("connection reset")))
        .isEqualTo("IOException: connection reset");
  }
}
