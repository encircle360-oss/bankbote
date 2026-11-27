package com.encircle360.bankbote.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The configuration of Bankbote under {@code bankbote.*}. Secrets are never values here, only
 * paths of files; {@link ConfigurationCheck} checks everything at start.
 *
 * @param sourceKey the first part of every transaction id, the same for the life of a Hibiscus
 *     Server
 * @param lookbackDays how many days back a regular run looks
 * @param backfillFrom the first booking date of the first run after the start, or null
 * @param timeZone the time zone that decides which day today is
 * @param schedule the cron expression of the runs by schedule, {@code -} for none
 * @param notifyDelay how long after a notification of Hibiscus Server the run starts
 * @param hibiscus the Hibiscus Server
 * @param targets the ERPNext installations, one per company
 * @param accounts which account goes to which target
 */
@ConfigurationProperties("bankbote")
public record BankboteProperties(
    @DefaultValue("hibiscus") String sourceKey,
    @DefaultValue("14") int lookbackDays,
    LocalDate backfillFrom,
    @DefaultValue("Europe/Berlin") String timeZone,
    @DefaultValue("0 0 * * * *") String schedule,
    @DefaultValue("60s") Duration notifyDelay,
    Hibiscus hibiscus,
    List<Target> targets,
    List<Account> accounts) {

  public BankboteProperties {
    targets = targets == null ? List.of() : List.copyOf(targets);
    accounts = accounts == null ? List.of() : List.copyOf(accounts);
  }

  /**
   * The Hibiscus Server.
   *
   * @param url the base URL, such as {@code https://127.0.0.1:8080}
   * @param certificate the PEM file with the certificate of the server, the only one trusted
   * @param username the user name; Hibiscus Server checks only the password
   * @param passwordFile the file with the master password
   */
  public record Hibiscus(
      URI url,
      Path certificate,
      @DefaultValue("admin") String username,
      Path passwordFile,
      @DefaultValue("10s") Duration connectTimeout,
      @DefaultValue("120s") Duration requestTimeout) {}

  /**
   * An ERPNext installation of one company.
   *
   * @param name the name the accounts refer to
   * @param url the base URL of the site
   * @param apiKeyFile the file with the API key of the service user
   * @param apiSecretFile the file with the API secret of the service user
   * @param certificateAuthorities a PEM file with the authorities to trust instead of those of the
   *     system, or null
   * @param allowPlainHttp whether {@code http} is accepted, for a site in the same trusted network
   */
  public record Target(
      String name,
      URI url,
      Path apiKeyFile,
      Path apiSecretFile,
      Path certificateAuthorities,
      @DefaultValue("false") boolean allowPlainHttp,
      @DefaultValue("10s") Duration connectTimeout,
      @DefaultValue("60s") Duration requestTimeout) {}

  /**
   * An account and the one target it goes to.
   *
   * @param iban the IBAN of the account
   * @param target the name of the target
   */
  public record Account(String iban, String target) {}
}
