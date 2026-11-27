package com.encircle360.bankbote;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ResourceBanner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class BannerTest {

  private static final String PROJECT = "https://github.com/encircle360-oss/bankbote";

  @Test
  void namesTheProjectItsAddressAndTheCompanyBehindIt() throws IOException {
    String banner = text();

    assertThat(banner).contains("Bank transactions from Hibiscus Server into ERPNext");
    assertThat(banner).contains("encircle360 GmbH");
    assertThat(banner).contains(PROJECT);
  }

  @Test
  void staysWithinAsciiAndANarrowTerminal() throws IOException {
    String banner = text();

    assertThat(banner.chars()).allMatch(c -> c == '\n' || (c >= 0x20 && c < 0x7f));
    assertThat(printed().lines()).allSatisfy(line -> assertThat(line.length()).isLessThan(80));
  }

  @Test
  void printsWithoutColoursAndWithoutAVersionWhereNeitherIsKnown() {
    String printed = printed();

    assertThat(printed).doesNotContain("${");
    assertThat(printed).doesNotContain("\u001b");
    assertThat(printed).contains(PROJECT);
  }

  @Test
  void isSwitchedOnInTheConfigurationOfTheService() throws IOException {
    try (InputStream in = new ClassPathResource("application.yaml").getInputStream()) {
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);

      assertThat(yaml).doesNotContain("banner-mode: off");
    }
  }

  private static String text() throws IOException {
    try (InputStream in = new ClassPathResource("banner.txt").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String printed() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new ResourceBanner(new ClassPathResource("banner.txt"))
        .printBanner(
            new MockEnvironment().withProperty("spring.output.ansi.enabled", "never"),
            BankboteApplication.class,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }
}
