package com.encircle360.oss.bankbote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.oss.bankbote.config.BankboteProperties.Account;
import com.encircle360.oss.bankbote.config.BankboteProperties.Hibiscus;
import com.encircle360.oss.bankbote.config.BankboteProperties.Target;
import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.testing.TestCertificate;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class BankboteConfigurationTest {

  @TempDir Path dir;

  @Test
  void buildsTheCoordinatorWithTargetsOfSystemAndOwnAuthorities(CapturedOutput output)
      throws Exception {
    Path pem = TestCertificate.create(dir, "hibiscus").pem();
    Path authority = TestCertificate.create(dir, "authority").pem();
    Path file = Files.writeString(dir.resolve("secret"), "x");
    Duration ten = Duration.ofSeconds(10);
    BankboteProperties properties =
        new BankboteProperties(
            14,
            null,
            "Europe/Berlin",
            "-",
            Duration.ZERO,
            new Hibiscus(URI.create("https://127.0.0.1:1"), pem, "admin", file, ten, ten),
            List.of(
                new Target(
                    "company-a",
                    URI.create("https://erp-a.example.com"),
                    file,
                    file,
                    null,
                    false,
                    ten,
                    ten),
                new Target(
                    "company-b",
                    URI.create("https://erp-b.example.com"),
                    file,
                    file,
                    authority,
                    false,
                    ten,
                    ten)),
            List.of(new Account("DE17123456780000000001", "company-a")));

    try (RunCoordinator coordinator =
        new BankboteConfiguration().runCoordinator(properties, new MockEnvironment())) {
      assertThat(coordinator.running()).isFalse();
    }
    assertThat(output.getAll()).doesNotContain("source-key");

    MockEnvironment earlier = new MockEnvironment().withProperty("bankbote.source-key", "hibiscus");
    try (RunCoordinator coordinator =
        new BankboteConfiguration().runCoordinator(properties, earlier)) {
      assertThat(coordinator.running()).isFalse();
    }
    assertThat(output.getAll()).contains("WARN", "bankbote.source-key has no effect since 0.2.0");
  }

  @Test
  void refusesAnInvalidConfiguration() {
    BankboteProperties properties =
        new BankboteProperties(14, null, "Europe/Berlin", "-", Duration.ZERO, null, null, null);

    assertThatThrownBy(
            () -> new BankboteConfiguration().runCoordinator(properties, new MockEnvironment()))
        .isInstanceOf(InvalidConfigurationException.class);
  }
}
