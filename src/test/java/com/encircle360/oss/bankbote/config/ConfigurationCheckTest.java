package com.encircle360.oss.bankbote.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.encircle360.oss.bankbote.config.BankboteProperties.Account;
import com.encircle360.oss.bankbote.config.BankboteProperties.Hibiscus;
import com.encircle360.oss.bankbote.config.BankboteProperties.Target;
import com.encircle360.oss.bankbote.testing.TestCertificate;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationCheckTest {

  static final String A1 = "DE17123456780000000001";
  static final String B1 = "DE60123456780000000003";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-27T09:00:00Z"), ZoneId.of("Europe/Berlin"));
  private static final Duration TEN = Duration.ofSeconds(10);

  @TempDir Path dir;
  private Path pem;
  private Path password;
  private Path key;
  private Path secret;

  @BeforeEach
  void files() throws IOException {
    pem = TestCertificate.create(dir, "hibiscus").pem();
    password = Files.writeString(dir.resolve("master-password"), "m");
    key = Files.writeString(dir.resolve("key"), "k");
    secret = Files.writeString(dir.resolve("secret"), "s");
  }

  private Hibiscus hibiscus() {
    return new Hibiscus(URI.create("https://127.0.0.1:8080"), pem, "admin", password, TEN, TEN);
  }

  private Target target(String name, String url) {
    return new Target(name, URI.create(url), key, secret, null, false, TEN, TEN);
  }

  private BankboteProperties properties(List<Target> targets, List<Account> accounts) {
    return new BankboteProperties(
        "hibiscus",
        14,
        null,
        "Europe/Berlin",
        "0 0 * * * *",
        Duration.ofSeconds(60),
        hibiscus(),
        targets,
        accounts);
  }

  private List<String> problems(BankboteProperties properties) {
    return ConfigurationCheck.problems(properties, CLOCK);
  }

  @Test
  void acceptsAValidConfigurationWithTwoTargets() {
    assertThat(
            problems(
                properties(
                    List.of(
                        target("company-a", "https://erp-a.example.com"),
                        target("company-b", "https://erp-b.example.com")),
                    List.of(
                        new Account(A1, "company-a"),
                        new Account("de60 1234 5678 0000 0000 03", "company-b")))))
        .isEmpty();
  }

  @Test
  void refusesTwoTargetsWithTheSameName() {
    assertThat(
            problems(
                properties(
                    List.of(
                        target("company-a", "https://erp-a.example.com"),
                        target("company-a", "https://erp-b.example.com")),
                    List.of(new Account(A1, "company-a")))))
        .containsExactly("bankbote.targets: the name company-a is used 2 times");
  }

  @Test
  void refusesTwoTargetsAtTheSameErpNext() {
    assertThat(
            problems(
                properties(
                    List.of(
                        target("company-a", "https://ERP.example.com/"),
                        target("company-b", "https://erp.example.com")),
                    List.of(new Account(A1, "company-a")))))
        .containsExactly("bankbote.targets: company-a and company-b point at the same ERPNext");
  }

  @Test
  void refusesAnAccountMappedToAnUnknownTarget() {
    assertThat(
            problems(
                properties(
                    List.of(target("company-a", "https://erp-a.example.com")),
                    List.of(new Account(A1, "company-x")))))
        .containsExactly(
            "bankbote.accounts[0] (DE17…0001) is mapped to the unknown target company-x");
  }

  @Test
  void refusesTheSameAccountMappedTwiceEvenToTwoTargets() {
    assertThat(
            problems(
                properties(
                    List.of(
                        target("company-a", "https://erp-a.example.com"),
                        target("company-b", "https://erp-b.example.com")),
                    List.of(
                        new Account(A1, "company-a"),
                        new Account("DE17 1234 5678 0000 0000 01", "company-b")))))
        .containsExactly("bankbote.accounts: the account DE17…0001 is mapped 2 times");
  }

  @Test
  void refusesMalformedAccounts() {
    assertThat(
            problems(
                properties(
                    List.of(target("company-a", "https://erp-a.example.com")),
                    List.of(
                        new Account("DE18123456780000000001", "company-a"),
                        new Account(B1, null),
                        new Account(null, "company-a")))))
        .containsExactly(
            "bankbote.accounts[0].iban: not a valid IBAN: check digits do not match",
            "bankbote.accounts[1].target is missing",
            "bankbote.accounts[2].iban: not a valid IBAN: no value");
  }

  @Test
  void refusesAnEmptyConfiguration() {
    assertThat(
            problems(
                new BankboteProperties(
                    "hibiscus",
                    14,
                    null,
                    "Europe/Berlin",
                    "0 0 * * * *",
                    Duration.ZERO,
                    null,
                    null,
                    null)))
        .containsExactly(
            "bankbote.hibiscus is missing",
            "bankbote.targets is empty",
            "bankbote.accounts is empty");
  }

  @Test
  void refusesBrokenGeneralSettings() {
    BankboteProperties properties =
        new BankboteProperties(
            "Hibiscus!",
            0,
            LocalDate.of(2026, 11, 28),
            "Mars/Olympus",
            "every hour",
            Duration.ofSeconds(-1),
            hibiscus(),
            List.of(target("company-a", "https://erp-a.example.com")),
            List.of(new Account(A1, "company-a")));

    assertThat(problems(properties))
        .containsExactly(
            "bankbote.source-key must be 1 to 32 lowercase letters, digits or dashes",
            "bankbote.lookback-days must be between 1 and 9999",
            "bankbote.backfill-from must not lie in the future",
            "bankbote.time-zone is not a time zone: Mars/Olympus",
            "bankbote.schedule is neither a cron expression nor -",
            "bankbote.notify-delay must not be negative");
  }

  @Test
  void acceptsAScheduleOfNone() {
    BankboteProperties properties =
        new BankboteProperties(
            "hibiscus",
            9999,
            LocalDate.of(2026, 11, 27),
            "UTC",
            "-",
            Duration.ZERO,
            hibiscus(),
            List.of(target("company-a", "https://erp-a.example.com")),
            List.of(new Account(A1, "company-a")));

    assertThat(problems(properties)).isEmpty();
  }

  @Test
  void refusesABrokenHibiscusSection() throws IOException {
    Path empty = Files.writeString(dir.resolve("empty"), "");
    Hibiscus plain =
        new Hibiscus(
            URI.create("http://user@127.0.0.1:8080?x=1"),
            dir.resolve("none.pem"),
            "admin",
            empty,
            Duration.ZERO,
            TEN);
    Hibiscus missing = new Hibiscus(null, null, "admin", null, TEN, TEN);

    assertThat(
            problems(
                new BankboteProperties(
                    "hibiscus",
                    14,
                    null,
                    "Europe/Berlin",
                    "-",
                    Duration.ZERO,
                    plain,
                    List.of(target("company-a", "https://erp-a.example.com")),
                    List.of(new Account(A1, "company-a")))))
        .containsExactly(
            "bankbote.hibiscus.url must use https",
            "bankbote.hibiscus.url must not carry user info, a query or a fragment",
            "bankbote.hibiscus.certificate: certificate file "
                + dir.resolve("none.pem")
                + " cannot be read",
            "bankbote.hibiscus.password-file: secret file " + empty + " is empty",
            "bankbote.hibiscus.connect-timeout must be positive");
    assertThat(
            problems(
                new BankboteProperties(
                    "hibiscus",
                    14,
                    null,
                    "Europe/Berlin",
                    "-",
                    Duration.ZERO,
                    missing,
                    List.of(target("company-a", "https://erp-a.example.com")),
                    List.of(new Account(A1, "company-a")))))
        .containsExactly(
            "bankbote.hibiscus.url is missing",
            "bankbote.hibiscus.certificate is missing",
            "bankbote.hibiscus.password-file: no secret file configured");
  }

  @Test
  void refusesBrokenTargets() throws IOException {
    Path garbage = Files.writeString(dir.resolve("garbage.pem"), "");
    List<Target> targets =
        List.of(
            new Target(
                "Company A",
                URI.create("http://erp-a.example.com"),
                null,
                dir.resolve("missing"),
                garbage,
                false,
                TEN,
                Duration.ZERO),
            new Target(
                "company-b",
                URI.create("ftp://erp-b.example.com#x"),
                key,
                secret,
                null,
                false,
                TEN,
                TEN),
            new Target(null, null, key, secret, null, false, TEN, TEN),
            new Target(
                "company-d",
                URI.create("http://erp-d.example.com"),
                key,
                secret,
                null,
                true,
                TEN,
                TEN));

    assertThat(problems(properties(targets, List.of(new Account(A1, "company-d")))))
        .containsExactly(
            "bankbote.targets[0].name must be 1 to 63 lowercase letters, digits or dashes",
            "bankbote.targets[0].url must use https, or http with allow-plain-http",
            "bankbote.targets[0].api-key-file: no secret file configured",
            "bankbote.targets[0].api-secret-file: secret file "
                + dir.resolve("missing")
                + " cannot be read",
            "bankbote.targets[0].certificate-authorities: certificate file "
                + garbage
                + " holds no certificate",
            "bankbote.targets[0].request-timeout must be positive",
            "bankbote.targets[1].url must use https, or http with allow-plain-http",
            "bankbote.targets[1].url must not carry user info, a query or a fragment",
            "bankbote.targets[2].name must be 1 to 63 lowercase letters, digits or dashes",
            "bankbote.targets[2].url is missing");
  }

  @Test
  void theExceptionListsEveryProblem() {
    InvalidConfigurationException exception =
        new InvalidConfigurationException(List.of("first", "second"));

    assertThat(exception.problems()).containsExactly("first", "second");
    assertThat(exception)
        .hasMessage("the configuration of Bankbote has 2 problem(s):\n  - first\n  - second");
  }

  @Test
  void refusesMissingGeneralSettings() {
    BankboteProperties properties =
        new BankboteProperties(
            null,
            10000,
            null,
            null,
            null,
            null,
            hibiscus(),
            List.of(target("company-a", "https://erp-a.example.com")),
            List.of(new Account(A1, "company-a")));

    assertThat(problems(properties))
        .containsExactly(
            "bankbote.source-key must be 1 to 32 lowercase letters, digits or dashes",
            "bankbote.lookback-days must be between 1 and 9999",
            "bankbote.time-zone is not a time zone: null",
            "bankbote.schedule is neither a cron expression nor -",
            "bankbote.notify-delay must not be negative");
  }

  @Test
  void checksUrlsTimeoutsTargetsAndAuthoritiesInEveryCombination() {
    Hibiscus queried =
        new Hibiscus(
            URI.create("https://127.0.0.1:8080/?q"),
            pem,
            "admin",
            password,
            null,
            Duration.ofSeconds(-1));
    List<Target> targets =
        List.of(
            new Target(
                null,
                URI.create("https://erp-x.example.com/#f"),
                key,
                secret,
                pem,
                false,
                TEN,
                TEN),
            new Target(
                "company-a",
                URI.create("https://erp-a.example.com"),
                key,
                secret,
                pem,
                false,
                TEN,
                TEN),
            new Target(
                "company-a",
                URI.create("https://erp-a.example.com"),
                key,
                secret,
                null,
                false,
                TEN,
                TEN));

    assertThat(
            problems(
                new BankboteProperties(
                    "hibiscus",
                    14,
                    null,
                    "Europe/Berlin",
                    "-",
                    Duration.ZERO,
                    queried,
                    targets,
                    List.of(new Account(A1, " ")))))
        .containsExactly(
            "bankbote.hibiscus.url must not carry user info, a query or a fragment",
            "bankbote.hibiscus.connect-timeout must be positive",
            "bankbote.hibiscus.request-timeout must be positive",
            "bankbote.targets[0].name must be 1 to 63 lowercase letters, digits or dashes",
            "bankbote.targets[0].url must not carry user info, a query or a fragment",
            "bankbote.targets: the name company-a is used 2 times",
            "bankbote.accounts[0].target is missing");
  }

  @Test
  void refusesAnEmptyUserNameWhichHibiscusServerRejects() {
    for (String username : new String[] {"", " ", null}) {
      Hibiscus hibiscus =
          new Hibiscus(URI.create("https://127.0.0.1:8080"), pem, username, password, TEN, TEN);

      assertThat(
              problems(
                  new BankboteProperties(
                      "hibiscus",
                      14,
                      null,
                      "Europe/Berlin",
                      "-",
                      Duration.ZERO,
                      hibiscus,
                      List.of(target("company-a", "https://erp-a.example.com")),
                      List.of(new Account(A1, "company-a")))))
          .as(String.valueOf(username))
          .containsExactly(
              "bankbote.hibiscus.username must not be empty: Hibiscus Server refuses an empty user"
                  + " name");
    }
  }
}
