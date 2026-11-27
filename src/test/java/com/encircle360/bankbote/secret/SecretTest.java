package com.encircle360.bankbote.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretTest {

  @TempDir Path dir;

  @Test
  void readsTheFileWithoutTheTrailingLineBreak() throws IOException {
    Path file = Files.writeString(dir.resolve("password"), "s3cr3t-value\r\n");

    assertThat(Secret.fromFile(file).reveal()).isEqualTo("s3cr3t-value");
  }

  @Test
  void keepsInnerWhitespace() throws IOException {
    Path file = Files.writeString(dir.resolve("password"), " two words \n");

    assertThat(Secret.fromFile(file).reveal()).isEqualTo(" two words ");
  }

  @Test
  void neverShowsTheValue() throws IOException {
    Path file = Files.writeString(dir.resolve("password"), "s3cr3t-value");

    Secret secret = Secret.fromFile(file);

    assertThat(secret.toString()).isEqualTo("Secret[****]").doesNotContain("s3cr3t");
  }

  @Test
  void refusesAMissingFileNamingOnlyThePath() {
    Path missing = dir.resolve("missing");

    assertThatThrownBy(() -> Secret.fromFile(missing))
        .isInstanceOf(SecretFileException.class)
        .hasMessage("secret file " + missing + " cannot be read");
  }

  @Test
  void refusesAnEmptyFile() throws IOException {
    Path file = Files.writeString(dir.resolve("empty"), "\n");

    assertThatThrownBy(() -> Secret.fromFile(file))
        .isInstanceOf(SecretFileException.class)
        .hasMessage("secret file " + file + " is empty");
  }

  @Test
  void refusesAFileWithSeveralLines() throws IOException {
    Path file = Files.writeString(dir.resolve("lines"), "first\nsecond\n");

    assertThatThrownBy(() -> Secret.fromFile(file))
        .isInstanceOf(SecretFileException.class)
        .hasMessage("secret file " + file + " holds more than one line")
        .hasMessageNotContaining("first");
  }

  @Test
  void refusesNoPath() {
    assertThatThrownBy(() -> Secret.fromFile(null))
        .isInstanceOf(SecretFileException.class)
        .hasMessage("no secret file configured");
  }

  @Test
  void refusesACarriageReturnInsideTheValue() throws IOException {
    Path file = Files.writeString(dir.resolve("cr"), "first\rsecond");

    assertThatThrownBy(() -> Secret.fromFile(file))
        .hasMessage("secret file " + file + " holds more than one line");
  }
}
