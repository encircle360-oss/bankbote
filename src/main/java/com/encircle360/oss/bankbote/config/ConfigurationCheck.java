package com.encircle360.oss.bankbote.config;

import com.encircle360.oss.bankbote.account.Iban;
import com.encircle360.oss.bankbote.config.BankboteProperties.Account;
import com.encircle360.oss.bankbote.config.BankboteProperties.Hibiscus;
import com.encircle360.oss.bankbote.config.BankboteProperties.Target;
import com.encircle360.oss.bankbote.secret.Secret;
import com.encircle360.oss.bankbote.secret.SecretFileException;
import com.encircle360.oss.bankbote.tls.TlsConfigurationException;
import com.encircle360.oss.bankbote.tls.TlsContexts;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.env.PropertyResolver;
import org.springframework.scheduling.support.CronExpression;

/**
 * Checks the whole configuration before Bankbote starts and names every problem at once. Secret
 * files and certificates are read here too, so a start never ends with a missing file later.
 */
public final class ConfigurationCheck {

  private static final Pattern SOURCE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
  private static final Pattern TARGET_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  private ConfigurationCheck() {}

  /** Every problem of the configuration, empty when there is none. */
  public static List<String> problems(BankboteProperties properties, Clock clock) {
    List<String> problems = new ArrayList<>();
    general(properties, clock, problems);
    if (properties.hibiscus() == null) {
      problems.add("bankbote.hibiscus is missing");
    } else {
      hibiscus(properties.hibiscus(), problems);
    }
    Set<String> targetNames = targets(properties.targets(), problems);
    accounts(properties.accounts(), targetNames, problems);
    return problems;
  }

  /** Throws when the configuration has a problem. */
  public static void requireValid(BankboteProperties properties, Clock clock) {
    List<String> problems = problems(properties, clock);
    if (!problems.isEmpty()) {
      throw new InvalidConfigurationException(problems);
    }
  }

  /**
   * Every problem of the probe port, empty when there is none. A management server of its own is
   * refused: the native image fixes the management context when it is built, so such a server would
   * either never listen or stop the start with a message that names no cause.
   */
  public static List<String> probeProblems(ProbeProperties probes, PropertyResolver environment) {
    List<String> problems = new ArrayList<>();
    for (String key : List.of("management.server.port", "management.server.address")) {
      if (environment.containsProperty(key)) {
        problems.add(
            key
                + " is not supported: the native image has no separate management server; probes"
                + " from outside use bankbote.probes.port");
      }
    }
    Integer port = probes.port();
    if (port == null) {
      if (probes.address() != null) {
        problems.add("bankbote.probes.address needs bankbote.probes.port");
      }
      return problems;
    }
    if (port < 1 || port > 65535) {
      problems.add("bankbote.probes.port must be between 1 and 65535");
    } else if (port.equals(environment.getProperty("server.port", Integer.class))) {
      problems.add("bankbote.probes.port must differ from server.port");
    }
    if (probes.address() != null) {
      try {
        InetAddress.ofLiteral(probes.address());
      } catch (IllegalArgumentException e) {
        problems.add("bankbote.probes.address is not an IP address: " + probes.address());
      }
    }
    return problems;
  }

  private static void general(BankboteProperties properties, Clock clock, List<String> problems) {
    if (properties.sourceKey() == null || !SOURCE_KEY.matcher(properties.sourceKey()).matches()) {
      problems.add("bankbote.source-key must be 1 to 32 lowercase letters, digits or dashes");
    }
    if (properties.lookbackDays() < 1 || properties.lookbackDays() > 9999) {
      problems.add("bankbote.lookback-days must be between 1 and 9999");
    }
    ZoneId zone = null;
    try {
      zone = ZoneId.of(properties.timeZone());
    } catch (DateTimeException | NullPointerException e) {
      // reported below, after backfill-from, which is checked against UTC then
    }
    ZoneId today = zone == null ? ZoneOffset.UTC : zone;
    if (properties.backfillFrom() != null
        && properties.backfillFrom().isAfter(LocalDate.now(clock.withZone(today)))) {
      problems.add("bankbote.backfill-from must not lie in the future");
    }
    if (zone == null) {
      problems.add("bankbote.time-zone is not a time zone: " + properties.timeZone());
    }
    String schedule = properties.schedule();
    if (!"-".equals(schedule)
        && (schedule == null || !CronExpression.isValidExpression(schedule))) {
      problems.add("bankbote.schedule is neither a cron expression nor -");
    }
    if (properties.notifyDelay() == null || properties.notifyDelay().isNegative()) {
      problems.add("bankbote.notify-delay must not be negative");
    }
  }

  private static void hibiscus(Hibiscus hibiscus, List<String> problems) {
    String prefix = "bankbote.hibiscus";
    url(prefix + ".url", hibiscus.url(), false, "must use https", problems);
    if (hibiscus.certificate() == null) {
      problems.add(prefix + ".certificate is missing");
    } else {
      check(prefix + ".certificate", () -> TlsContexts.pinned(hibiscus.certificate()), problems);
    }
    if (hibiscus.username() == null || hibiscus.username().isBlank()) {
      problems.add(
          prefix + ".username must not be empty: Hibiscus Server refuses an empty user name");
    }
    secret(prefix + ".password-file", hibiscus.passwordFile(), problems);
    positive(prefix + ".connect-timeout", hibiscus.connectTimeout(), problems);
    positive(prefix + ".request-timeout", hibiscus.requestTimeout(), problems);
  }

  private static Set<String> targets(List<Target> targets, List<String> problems) {
    if (targets.isEmpty()) {
      problems.add("bankbote.targets is empty");
    }
    Map<String, URI> urls = new LinkedHashMap<>();
    for (int i = 0; i < targets.size(); i++) {
      Target target = targets.get(i);
      String prefix = "bankbote.targets[" + i + "]";
      if (target.name() == null || !TARGET_NAME.matcher(target.name()).matches()) {
        problems.add(prefix + ".name must be 1 to 63 lowercase letters, digits or dashes");
      }
      url(
          prefix + ".url",
          target.url(),
          target.allowPlainHttp(),
          "must use https, or http with allow-plain-http",
          problems);
      secret(prefix + ".api-key-file", target.apiKeyFile(), problems);
      secret(prefix + ".api-secret-file", target.apiSecretFile(), problems);
      if (target.certificateAuthorities() != null) {
        check(
            prefix + ".certificate-authorities",
            () -> TlsContexts.trustingAuthorities(target.certificateAuthorities()),
            problems);
      }
      positive(prefix + ".connect-timeout", target.connectTimeout(), problems);
      positive(prefix + ".request-timeout", target.requestTimeout(), problems);
    }
    Map<String, Long> counts =
        targets.stream()
            .map(Target::name)
            .filter(Objects::nonNull)
            .collect(
                Collectors.groupingBy(name -> name, LinkedHashMap::new, Collectors.counting()));
    counts.forEach((name, count) -> {
      if (count > 1) {
        problems.add("bankbote.targets: the name " + name + " is used " + count + " times");
      }
    });
    for (Target target : targets) {
      if (target.url() == null || target.name() == null) {
        continue;
      }
      String site = site(target.url());
      urls.entrySet().stream()
          .filter(
              entry -> site(entry.getValue()).equals(site) && !entry.getKey().equals(target.name()))
          .findFirst()
          .ifPresent(entry ->
              problems.add(
                  "bankbote.targets: "
                      + entry.getKey()
                      + " and "
                      + target.name()
                      + " point at the same ERPNext"));
      urls.putIfAbsent(target.name(), target.url());
    }
    return counts.keySet();
  }

  /** The address of a site without the case of its host and a trailing slash. */
  private static String site(URI url) {
    String text =
        url.getScheme()
            + "://"
            + Objects.toString(url.getHost(), "").toLowerCase(Locale.ROOT)
            + ":"
            + url.getPort()
            + Objects.toString(url.getRawPath(), "");
    return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
  }

  private static void accounts(
      List<Account> accounts, Set<String> targetNames, List<String> problems) {
    if (accounts.isEmpty()) {
      problems.add("bankbote.accounts is empty");
    }
    Map<Iban, Integer> counts = new LinkedHashMap<>();
    for (int i = 0; i < accounts.size(); i++) {
      Account account = accounts.get(i);
      String prefix = "bankbote.accounts[" + i + "]";
      Iban iban;
      try {
        iban = Iban.parse(account.iban());
      } catch (IllegalArgumentException e) {
        problems.add(prefix + ".iban: " + e.getMessage());
        continue;
      }
      counts.merge(iban, 1, Integer::sum);
      if (account.target() == null || account.target().isBlank()) {
        problems.add(prefix + ".target is missing");
      } else if (!targetNames.contains(account.target())) {
        problems.add(
            prefix
                + " ("
                + iban.masked()
                + ") is mapped to the unknown target "
                + account.target());
      }
    }
    counts.forEach((iban, count) -> {
      if (count > 1) {
        problems.add(
            "bankbote.accounts: the account " + iban.masked() + " is mapped " + count + " times");
      }
    });
  }

  private static void url(
      String key, URI url, boolean allowPlainHttp, String schemeProblem, List<String> problems) {
    if (url == null) {
      problems.add(key + " is missing");
      return;
    }
    boolean https = "https".equalsIgnoreCase(url.getScheme());
    boolean http = "http".equalsIgnoreCase(url.getScheme());
    if (!https && !(http && allowPlainHttp)) {
      problems.add(key + " " + schemeProblem);
    }
    if (url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
      problems.add(key + " must not carry user info, a query or a fragment");
    }
  }

  private static void secret(String key, Path file, List<String> problems) {
    check(key, () -> Secret.fromFile(file), problems);
  }

  private static void check(String key, Runnable load, List<String> problems) {
    try {
      load.run();
    } catch (SecretFileException | TlsConfigurationException e) {
      problems.add(key + ": " + e.getMessage());
    }
  }

  private static void positive(String key, Duration duration, List<String> problems) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      problems.add(key + " must be positive");
    }
  }
}
