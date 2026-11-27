package com.encircle360.bankbote.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A self-signed certificate for {@code localhost} and {@code 127.0.0.1}, made with the keytool of
 * the running JDK in a temporary directory, as a Hibiscus Server makes its own. The repository
 * holds no key.
 */
public final class TestCertificate {

  private static final char[] STORE_PASSWORD = "test-only".toCharArray();

  private final Path pem;
  private final SSLContext serverContext;

  private TestCertificate(Path pem, SSLContext serverContext) {
    this.pem = pem;
    this.serverContext = serverContext;
  }

  /** Makes a new key pair and certificate for localhost and 127.0.0.1 in {@code dir}. */
  public static TestCertificate create(Path dir, String alias) {
    return create(dir, alias, "CN=localhost", "dns:localhost,ip:127.0.0.1");
  }

  /**
   * Makes a new key pair and certificate with the given subject and subject alternative names, none
   * when {@code san} is null.
   */
  public static TestCertificate create(Path dir, String alias, String subject, String san) {
    try {
      Path store = dir.resolve(alias + ".p12");
      Path pem = dir.resolve(alias + ".pem");
      String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
      run(
          List.of(
              keytool,
              "-genkeypair",
              "-alias",
              alias,
              "-keyalg",
              "EC",
              "-groupname",
              "secp256r1",
              "-validity",
              "2",
              "-dname",
              subject,
              "-ext",
              san == null ? "KeyUsage=digitalSignature" : "SAN=" + san,
              "-storetype",
              "PKCS12",
              "-keystore",
              store.toString(),
              "-storepass",
              new String(STORE_PASSWORD)));
      run(
          List.of(
              keytool,
              "-exportcert",
              "-rfc",
              "-alias",
              alias,
              "-keystore",
              store.toString(),
              "-storepass",
              new String(STORE_PASSWORD),
              "-file",
              pem.toString()));
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(store)) {
        keyStore.load(in, STORE_PASSWORD);
      }
      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(keyStore, STORE_PASSWORD);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys.getKeyManagers(), null, null);
      return new TestCertificate(pem, context);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void run(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new IllegalStateException("keytool failed: " + new String(output));
    }
  }

  /** The certificate in PEM, as the operator hands it to Bankbote. */
  public Path pem() {
    return pem;
  }

  /** The context a test server presents the certificate with. */
  public SSLContext serverContext() {
    return serverContext;
  }
}
