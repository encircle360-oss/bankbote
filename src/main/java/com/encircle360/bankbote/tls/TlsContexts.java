package com.encircle360.bankbote.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/** The TLS contexts Bankbote connects with. */
public final class TlsContexts {

  private TlsContexts() {}

  /**
   * A context that trusts exactly one certificate and nothing else, whatever host name it names.
   * A Hibiscus Server makes its own certificate, usually without the name it is reached by; the
   * comparison with the configured certificate replaces the check of the name.
   */
  public static SSLContext pinned(Path pem) {
    List<X509Certificate> certificates = certificates(pem);
    if (certificates.size() != 1) {
      throw new TlsConfigurationException(
          "certificate file "
              + pem
              + " must hold exactly one certificate, it holds "
              + certificates.size());
    }
    return context(new TrustManager[] {new PinnedTrustManager(certificates.getFirst())});
  }

  /**
   * A context that trusts the certificate authorities of the file instead of those of the system,
   * with the usual check of the host name.
   */
  public static SSLContext trustingAuthorities(Path pem) {
    List<X509Certificate> certificates = certificates(pem);
    if (certificates.isEmpty()) {
      throw new TlsConfigurationException("certificate file " + pem + " holds no certificate");
    }
    try {
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      for (int i = 0; i < certificates.size(); i++) {
        store.setCertificateEntry("authority-" + i, certificates.get(i));
      }
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      return context(factory.getTrustManagers());
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("the JDK cannot build a trust store", e);
    }
  }

  /** The default context of the JDK with the certificate authorities of the system. */
  public static SSLContext system() {
    try {
      return SSLContext.getDefault();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the JDK has no default TLS context", e);
    }
  }

  /** The certificates of a PEM file, in their order. */
  public static List<X509Certificate> certificates(Path pem) {
    try (InputStream in = Files.newInputStream(pem)) {
      return CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
          .map(X509Certificate.class::cast)
          .toList();
    } catch (IOException e) {
      throw new TlsConfigurationException("certificate file " + pem + " cannot be read");
    } catch (CertificateException e) {
      throw new TlsConfigurationException(
          "certificate file " + pem + " holds no readable certificate");
    }
  }

  private static SSLContext context(TrustManager[] trustManagers) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagers, null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the JDK cannot build a TLS context", e);
    }
  }
}
