package com.encircle360.oss.bankbote.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * A real ERPNext 16 from the image of encircle360-oss/erpnext_germanized, with MariaDB 11.8 and
 * Redis, set up as the site test of that repository does it, with ERPNext as the only app on a new
 * site. The site is named {@code localhost}, the host its clients reach it by, because Frappe picks
 * the site by the host of a request. The password of the Administrator and the root password of the
 * database are generated for each start.
 */
public final class ErpNextServer implements AutoCloseable, LogSource {

  /** The name of the site and the host of its address. */
  public static final String SITE = "localhost";

  private static final String SECRETS = "/run/secrets/erpnext";

  /**
   * The script of the ERPNext container, mounted read-only and run by bash. It writes the shared
   * configuration, creates the site with ERPNext only, makes sure the site has its encryption key
   * before the workers of gunicorn start, and then starts gunicorn as the image does.
   */
  private static final String SCRIPT =
      String.join(
          "\n",
          "set -eu",
          "root_password=$(head -n 1 " + SECRETS + "/db-root-password)",
          "admin_password=$(head -n 1 " + SECRETS + "/admin-password)",
          "ls -1 apps > sites/apps.txt",
          "bench set-config -g db_host db",
          "bench set-config -gp db_port 3306",
          "bench set-config -g redis_cache redis://redis:6379",
          "bench set-config -g redis_queue redis://redis:6379",
          "bench set-config -g redis_socketio redis://redis:6379",
          "wait-for-it -t 120 db:3306",
          "wait-for-it -t 120 redis:6379",
          "bench new-site --mariadb-user-host-login-scope='%' --db-root-username=root"
              + " --db-root-password=\"$root_password\" --admin-password=\"$admin_password\""
              + " --install-app erpnext --set-default "
              + SITE,
          "bench --site " + SITE + " execute frappe.utils.password.get_encryption_key > /dev/null",
          "echo 'bankbote: site ready'",
          "exec start.sh");

  private final Network network;
  private final GenericContainer<?> database;
  private final GenericContainer<?> redis;
  private final GenericContainer<?> erpnext;
  private final String adminPassword;
  private final String rootPassword;

  private ErpNextServer(
      Network network,
      GenericContainer<?> database,
      GenericContainer<?> redis,
      GenericContainer<?> erpnext,
      String adminPassword,
      String rootPassword) {
    this.network = network;
    this.database = database;
    this.redis = redis;
    this.erpnext = erpnext;
    this.adminPassword = adminPassword;
    this.rootPassword = rootPassword;
  }

  /** Starts the database, Redis and ERPNext and waits until the site answers. */
  @SuppressWarnings("resource")
  public static ErpNextServer start(Path secrets) {
    String adminPassword = randomPassword();
    String rootPassword = randomPassword();
    writeSecret(secrets, "admin-password", adminPassword);
    writeSecret(secrets, "db-root-password", rootPassword);
    writeSecret(secrets, "start-site.sh", SCRIPT);
    Network network = Network.newNetwork();
    GenericContainer<?> database =
        new GenericContainer<>(Images.image("mariadb"))
            .withNetwork(network)
            .withNetworkAliases("db")
            .withEnv("MARIADB_ROOT_PASSWORD", rootPassword)
            .withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_unicode_ci",
                "--skip-character-set-client-handshake",
                "--innodb-snapshot-isolation=OFF")
            .waitingFor(LogOrExit.line(".*ready for connections.*", 2, Duration.ofMinutes(3)));
    GenericContainer<?> redis =
        new GenericContainer<>(Images.image("redis"))
            .withNetwork(network)
            .withNetworkAliases("redis")
            .waitingFor(
                LogOrExit.line(".*Ready to accept connections.*", 1, Duration.ofMinutes(2)));
    GenericContainer<?> erpnext =
        new GenericContainer<>(Images.image("erpnext"))
            .withNetwork(network)
            .withFileSystemBind(secrets.toString(), SECRETS, BindMode.READ_ONLY)
            // The command goes to Docker as it is: Testcontainers would split a command string at
            // its spaces.
            .withCreateContainerCmdModifier(command ->
                command
                    .withEntrypoint("/usr/local/bin/entrypoint.sh")
                    .withCmd("bash", SECRETS + "/start-site.sh"))
            .withExposedPorts(8000)
            .waitingFor(LogOrExit.line("bankbote: site ready", 1, Duration.ofMinutes(15)));
    ErpNextServer server =
        new ErpNextServer(network, database, redis, erpnext, adminPassword, rootPassword);
    try {
      database.start();
      redis.start();
      erpnext.start();
      server.awaitSite(Duration.ofMinutes(5));
    } catch (RuntimeException e) {
      String excerpt = erpnext.isCreated() ? server.excerpt(120) : "";
      server.close();
      throw new IllegalStateException("ERPNext did not start\n" + excerpt, e);
    }
    return server;
  }

  /**
   * Waits until the site answers {@code ping}; gunicorn starts its workers after the line that ends
   * the creation of the site.
   */
  private void awaitSite(Duration timeout) {
    HttpRequest ping =
        HttpRequest.newBuilder(url().resolve("/api/method/ping"))
            .timeout(Duration.ofSeconds(10))
            .build();
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      Readiness.await(
          () -> http.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode(),
          timeout,
          Duration.ofMillis(500));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void writeSecret(Path dir, String name, String value) {
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
      Path file = Files.writeString(dir.resolve(name), value + "\n");
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String randomPassword() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The address of the site, by the host {@code localhost}, which names the site. */
  public URI url() {
    return URI.create("http://" + SITE + ":" + erpnext.getMappedPort(8000));
  }

  /** The Administrator, logged in. */
  public FrappeAdmin admin() {
    return new FrappeAdmin(url(), adminPassword);
  }

  /**
   * The end of the output of the container, which holds the creation of the site and gunicorn, and
   * of the logs of the bench, with both passwords masked.
   */
  @Override
  public String excerpt(int lines) {
    StringBuilder text = new StringBuilder();
    List<String> output = erpnext.getLogs().lines().toList();
    text.append("--- last ")
        .append(lines)
        .append(" lines of the output of the ERPNext container ---\n")
        .append(
            String.join("\n", output.subList(Math.max(0, output.size() - lines), output.size())));
    if (erpnext.isRunning()) {
      try {
        ExecResult logs =
            erpnext.execInContainer(
                "bash", "-c", "tail -n " + lines + " logs/*.log 2>/dev/null || true");
        text.append("\n--- logs of the bench ---\n").append(logs.getStdout());
      } catch (IOException e) {
        text.append("\n(the logs of the bench cannot be read: ").append(e).append(")");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return text.toString().replace(adminPassword, "****").replace(rootPassword, "****");
  }

  @Override
  public void close() {
    erpnext.stop();
    redis.stop();
    database.stop();
    network.close();
  }
}
