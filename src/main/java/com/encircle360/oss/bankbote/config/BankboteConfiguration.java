package com.encircle360.oss.bankbote.config;

import com.encircle360.oss.bankbote.account.Iban;
import com.encircle360.oss.bankbote.config.BankboteProperties.Hibiscus;
import com.encircle360.oss.bankbote.config.BankboteProperties.Target;
import com.encircle360.oss.bankbote.delivery.Delivery;
import com.encircle360.oss.bankbote.delivery.Route;
import com.encircle360.oss.bankbote.delivery.RunCoordinator;
import com.encircle360.oss.bankbote.delivery.RunReportLog;
import com.encircle360.oss.bankbote.erpnext.ErpNextClient;
import com.encircle360.oss.bankbote.hibiscus.HibiscusClient;
import com.encircle360.oss.bankbote.hibiscus.HibiscusSource;
import com.encircle360.oss.bankbote.secret.Secret;
import com.encircle360.oss.bankbote.tls.TlsContexts;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Builds Bankbote from its checked configuration. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class BankboteConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(BankboteConfiguration.class);

  @Bean
  RunCoordinator runCoordinator(BankboteProperties properties, Environment environment) {
    ConfigurationCheck.requireValid(properties, Clock.systemUTC());
    ConfigurationCheck.obsoleteKeys(environment).forEach(LOG::warn);
    Clock clock = Clock.system(ZoneId.of(properties.timeZone()));
    Hibiscus hibiscus = properties.hibiscus();
    HibiscusSource source =
        new HibiscusSource(
            new HibiscusClient(
                hibiscus.url(),
                hibiscus.username(),
                Secret.fromFile(hibiscus.passwordFile()),
                TlsContexts.pinned(hibiscus.certificate()),
                hibiscus.connectTimeout(),
                hibiscus.requestTimeout()),
            clock);
    List<ErpNextClient> targets =
        properties.targets().stream().map(BankboteConfiguration::client).toList();
    List<Route> routes =
        properties.accounts().stream()
            .map(account -> new Route(Iban.parse(account.iban()), account.target()))
            .toList();
    Delivery delivery = new Delivery(source, routes, targets, clock);
    return new RunCoordinator(
        delivery::run,
        clock,
        properties.lookbackDays(),
        properties.backfillFrom(),
        properties.notifyDelay(),
        RunReportLog::log);
  }

  private static ErpNextClient client(Target target) {
    return new ErpNextClient(
        target.name(),
        target.url(),
        Secret.fromFile(target.apiKeyFile()),
        Secret.fromFile(target.apiSecretFile()),
        target.certificateAuthorities() == null
            ? TlsContexts.system()
            : TlsContexts.trustingAuthorities(target.certificateAuthorities()),
        target.connectTimeout(),
        target.requestTimeout());
  }
}
