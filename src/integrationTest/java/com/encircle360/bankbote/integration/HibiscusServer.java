package com.encircle360.bankbote.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A real Hibiscus Server from the image of encircle360-oss/hibiscus-server-image with MariaDB 11.8,
 * started the way an operator runs it: read-only root file system, no capabilities, the master
 * password and the password of the database in files. Both passwords are generated for each start.
 */
public final class HibiscusServer implements AutoCloseable {

  /** The name the server puts into the certificate it makes at its first start. */
  public static final String CERTIFICATE_HOSTNAME = "hibiscus.example.com";

  private static final String SECRETS = "/run/secrets/hibiscus";

  private final Network network;
  private final GenericContainer<?> database;
  private final GenericContainer<?> server;
  private final String masterPassword;
  private final String databasePassword;

  private HibiscusServer(
      Network network,
      GenericContainer<?> database,
      GenericContainer<?> server,
      String masterPassword,
      String databasePassword) {
    this.network = network;
    this.database = database;
    this.server = server;
    this.masterPassword = masterPassword;
    this.databasePassword = databasePassword;
  }

  /**
   * Starts MariaDB and Hibiscus Server, which creates the schema of the vendor. The passwords go
   * into files in {@code secrets}, mounted read-only as the root file system of the server is.
   */
  @SuppressWarnings("resource")
  public static HibiscusServer start(Path secrets) {
    String masterPassword = randomPassword();
    String databasePassword = randomPassword();
    writeSecret(secrets, "master-password", masterPassword);
    writeSecret(secrets, "db-password", databasePassword);
    Network network = Network.newNetwork();
    GenericContainer<?> database =
        new GenericContainer<>(Images.image("mariadb"))
            .withNetwork(network)
            .withNetworkAliases("db")
            .withEnv(
                Map.of(
                    "MARIADB_ROOT_PASSWORD",
                    randomPassword(),
                    "MARIADB_DATABASE",
                    "hibiscus",
                    "MARIADB_USER",
                    "hibiscus",
                    "MARIADB_PASSWORD",
                    databasePassword))
            .withCommand("--character-set-server=utf8mb3", "--collation-server=utf8mb3_general_ci")
            .withExposedPorts(3306)
            .waitingFor(
                Wait.forLogMessage(".*ready for connections.*", 2)
                    .withStartupTimeout(Duration.ofMinutes(2)));
    database.start();
    GenericContainer<?> server =
        new GenericContainer<>(Images.image("hibiscus-server"))
            .withNetwork(network)
            .withEnv(
                Map.of(
                    "HIBISCUS_CERT_HOSTNAME", CERTIFICATE_HOSTNAME,
                    "HIBISCUS_DB_HOST", "db",
                    "TZ", "Europe/Berlin"))
            .withFileSystemBind(secrets.toString(), SECRETS, BindMode.READ_ONLY)
            .withTmpFs(Map.of("/tmp", "rw,size=64m"))
            .withCreateContainerCmdModifier(command ->
                command
                    .getHostConfig()
                    .withReadonlyRootfs(true)
                    .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                    .withSecurityOpts(java.util.List.of("no-new-privileges")))
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forLogMessage(".*all plugins initialised.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    try {
      server.start();
    } catch (RuntimeException e) {
      database.stop();
      network.close();
      throw e;
    }
    return new HibiscusServer(network, database, server, masterPassword, databasePassword);
  }

  /** The server runs as uid 65532, which reads the files through the mount. */
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

  /** The base URL of the server as Bankbote reaches it. */
  public URI url() {
    return URI.create("https://" + server.getHost() + ":" + server.getMappedPort(8080));
  }

  /** The master password, generated for this start. */
  public String masterPassword() {
    return masterPassword;
  }

  /** The password of the database, generated for this start. */
  public String databasePassword() {
    return databasePassword;
  }

  /** Writes the master password into a file, as the operator hands it to Bankbote. */
  public Path writeMasterPassword(Path dir) {
    try {
      return Files.writeString(dir.resolve("master-password"), masterPassword + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A connection to the database of the server, for fixtures. */
  public Connection database() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mariadb://" + database.getHost() + ":" + database.getMappedPort(3306) + "/hibiscus",
        "hibiscus",
        databasePassword);
  }

  /**
   * The certificate of the server from its key store {@code /data/cfg/jameica.keystore}, entry
   * {@code jameica}, which the master password opens: the copy an operator compares the presented
   * certificate with.
   */
  public X509Certificate certificateFromKeyStore() {
    AtomicReference<X509Certificate> certificate = new AtomicReference<>();
    server.copyFileFromContainer(
        "/data/cfg/jameica.keystore",
        (InputStream in) -> {
          KeyStore store = KeyStore.getInstance("JKS");
          store.load(in, masterPassword.toCharArray());
          certificate.set((X509Certificate) store.getCertificate("jameica"));
          return null;
        });
    return certificate.get();
  }

  /** Writes a certificate as PEM. */
  public static Path writePem(X509Certificate certificate, Path file) {
    try {
      String body =
          Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
              .encodeToString(certificate.getEncoded());
      return Files.writeString(
          file, "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The log of the server on stdout so far. */
  public String logs() {
    return server.getLogs();
  }

  /**
   * The last lines of the full log of Jameica, {@code /data/jameica.log}, with both passwords
   * masked, for the report of a failed test.
   */
  public String jameicaLog(int lines) {
    AtomicReference<String> log = new AtomicReference<>("");
    try {
      server.copyFileFromContainer(
          "/data/jameica.log",
          (InputStream in) -> {
            log.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return null;
          });
    } catch (RuntimeException e) {
      return "(the log of Jameica cannot be read: " + e.getClass().getSimpleName() + ")";
    }
    java.util.List<String> all = log.get().lines().toList();
    return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()))
        .replace(masterPassword, "****")
        .replace(databasePassword, "****");
  }

  @Override
  public void close() {
    server.stop();
    database.stop();
    network.close();
  }
}
