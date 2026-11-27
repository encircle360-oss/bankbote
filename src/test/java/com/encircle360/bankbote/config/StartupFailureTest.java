package com.encircle360.bankbote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.bankbote.BankboteApplication;
import com.encircle360.bankbote.testing.TestCertificate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class StartupFailureTest {

  @TempDir Path dir;

  @Test
  void aBrokenMappingStopsTheStartWithEveryProblem(CapturedOutput output) throws Exception {
    Path pem = TestCertificate.create(dir, "hibiscus").pem();
    Path secret = Files.writeString(dir.resolve("secret"), "very-secret-value");

    assertThatThrownBy(() ->
            new SpringApplicationBuilder(BankboteApplication.class)
                .properties(
                    "server.port=0",
                    "bankbote.schedule=-",
                    "bankbote.hibiscus.url=https://127.0.0.1:1",
                    "bankbote.hibiscus.certificate=" + pem,
                    "bankbote.hibiscus.password-file=" + secret,
                    "bankbote.targets[0].name=company-a",
                    "bankbote.targets[0].url=https://erp-a.example.com",
                    "bankbote.targets[0].api-key-file=" + secret,
                    "bankbote.targets[0].api-secret-file=" + secret,
                    "bankbote.targets[1].name=company-a",
                    "bankbote.targets[1].url=https://erp-b.example.com",
                    "bankbote.targets[1].api-key-file=" + secret,
                    "bankbote.targets[1].api-secret-file=" + secret,
                    "bankbote.accounts[0].iban=DE17123456780000000001",
                    "bankbote.accounts[0].target=company-x",
                    "bankbote.accounts[1].iban=DE17123456780000000001",
                    "bankbote.accounts[1].target=company-a")
                .run())
        .hasRootCauseInstanceOf(InvalidConfigurationException.class);

    assertThat(output.getAll())
        .contains("APPLICATION FAILED TO START")
        .contains("bankbote.targets: the name company-a is used 2 times")
        .contains("bankbote.accounts[0] (DE17…0001) is mapped to the unknown target company-x")
        .contains("bankbote.accounts: the account DE17…0001 is mapped 2 times")
        .contains("docs/konfiguration.md")
        .doesNotContain("very-secret-value")
        .doesNotContain("DE17123456780000000001");
  }

  @Test
  void theAnalysisNamesTheProblemsAndWhereTheyAreDescribed() {
    FailureAnalysis analysis =
        new InvalidConfigurationFailureAnalyzer()
            .analyze(
                new IllegalStateException(
                    new InvalidConfigurationException(java.util.List.of("one"))));

    assertThat(analysis.getDescription())
        .isEqualTo("The configuration of Bankbote has 1 problem(s):\n  - one");
    assertThat(analysis.getAction()).contains("docs/konfiguration.md");
  }
}
