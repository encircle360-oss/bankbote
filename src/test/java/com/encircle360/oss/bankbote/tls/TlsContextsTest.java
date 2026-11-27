package com.encircle360.oss.bankbote.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.encircle360.oss.bankbote.testing.FakeHttpServer;
import com.encircle360.oss.bankbote.testing.FakeHttpServer.Response;
import com.encircle360.oss.bankbote.testing.TestCertificate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsContextsTest {

  @TempDir Path dir;

  private static int get(SSLContext context, URI uri) throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newBuilder().sslContext(context).build()) {
      return client
          .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }
  }

  @Test
  void pinnedContextTrustsTheConfiguredCertificate() throws Exception {
    TestCertificate certificate = TestCertificate.create(dir, "hibiscus");
    try (FakeHttpServer server = FakeHttpServer.https(certificate.serverContext())) {
      server.handler(request -> Response.json(200, "{}"));

      assertThat(get(TlsContexts.pinned(certificate.pem()), server.uri())).isEqualTo(200);
    }
  }

  @Test
  void pinnedContextTrustsTheCertificateWhateverNameItCarries() throws Exception {
    TestCertificate certificate =
        TestCertificate.create(dir, "hibiscus", "CN=hibiscus.example.com", null);
    try (FakeHttpServer server = FakeHttpServer.https(certificate.serverContext())) {
      server.handler(request -> Response.json(200, "{}"));

      assertThat(get(TlsContexts.pinned(certificate.pem()), server.uri())).isEqualTo(200);
    }
  }

  @Test
  void pinnedContextRefusesAnyOtherCertificate() {
    TestCertificate configured = TestCertificate.create(dir, "configured");
    TestCertificate other = TestCertificate.create(dir, "other");
    try (FakeHttpServer server = FakeHttpServer.https(other.serverContext())) {
      SSLContext context = TlsContexts.pinned(configured.pem());

      assertThatThrownBy(() -> get(context, server.uri()))
          .isInstanceOf(SSLHandshakeException.class);
      assertThat(server.requests()).isEmpty();
    }
  }

  @Test
  void pinnedTrustManagerRefusesClientsEmptyChainsAndNamesNoIssuer() throws Exception {
    TestCertificate configured = TestCertificate.create(dir, "configured");
    X509Certificate pinned = TlsContexts.certificates(configured.pem()).getFirst();
    X509ExtendedTrustManager manager = new PinnedTrustManager(pinned);
    X509Certificate[] chain = {pinned};

    manager.checkServerTrusted(chain, "EC");
    manager.checkServerTrusted(chain, "EC", (java.net.Socket) null);
    manager.checkServerTrusted(chain, "EC", (javax.net.ssl.SSLEngine) null);
    assertThat(manager.getAcceptedIssuers()).isEmpty();
    assertThatThrownBy(() -> manager.checkServerTrusted(new X509Certificate[0], "EC"))
        .isInstanceOf(CertificateException.class)
        .hasMessage("the server sent no certificate");
    assertThatThrownBy(() -> manager.checkServerTrusted(null, "EC"))
        .isInstanceOf(CertificateException.class);
    assertThatThrownBy(() -> manager.checkClientTrusted(chain, "EC"))
        .isInstanceOf(CertificateException.class)
        .hasMessage("client certificates are not accepted");
    assertThatThrownBy(() -> manager.checkClientTrusted(chain, "EC", (java.net.Socket) null))
        .isInstanceOf(CertificateException.class);
    assertThatThrownBy(
            () -> manager.checkClientTrusted(chain, "EC", (javax.net.ssl.SSLEngine) null))
        .isInstanceOf(CertificateException.class);
  }

  @Test
  void pinnedContextNeedsExactlyOneCertificate() throws IOException {
    TestCertificate first = TestCertificate.create(dir, "first");
    TestCertificate second = TestCertificate.create(dir, "second");
    Path both =
        Files.writeString(
            dir.resolve("both.pem"),
            Files.readString(first.pem()) + Files.readString(second.pem()));

    assertThatThrownBy(() -> TlsContexts.pinned(both))
        .isInstanceOf(TlsConfigurationException.class)
        .hasMessage("certificate file " + both + " must hold exactly one certificate, it holds 2");
  }

  @Test
  void refusesFilesWithoutCertificates() throws IOException {
    Path empty = Files.writeString(dir.resolve("empty.pem"), "");
    Path garbage =
        Files.writeString(dir.resolve("garbage.pem"), "-----BEGIN CERTIFICATE-----\nnope\n");
    Path missing = dir.resolve("missing.pem");

    assertThatThrownBy(() -> TlsContexts.pinned(empty))
        .isInstanceOf(TlsConfigurationException.class)
        .hasMessage("certificate file " + empty + " must hold exactly one certificate, it holds 0");
    assertThatThrownBy(() -> TlsContexts.pinned(garbage))
        .isInstanceOf(TlsConfigurationException.class)
        .hasMessage("certificate file " + garbage + " holds no readable certificate");
    assertThatThrownBy(() -> TlsContexts.pinned(missing))
        .isInstanceOf(TlsConfigurationException.class)
        .hasMessage("certificate file " + missing + " cannot be read");
    assertThatThrownBy(() -> TlsContexts.trustingAuthorities(empty))
        .isInstanceOf(TlsConfigurationException.class)
        .hasMessage("certificate file " + empty + " holds no certificate");
  }

  @Test
  void authorityContextTrustsCertificatesOfTheAuthorityForTheirName() throws Exception {
    TestCertificate certificate = TestCertificate.create(dir, "erpnext");
    try (FakeHttpServer server = FakeHttpServer.https(certificate.serverContext())) {
      server.handler(request -> Response.json(200, "{}"));

      assertThat(get(TlsContexts.trustingAuthorities(certificate.pem()), server.uri()))
          .isEqualTo(200);
    }
  }

  @Test
  void authorityContextChecksTheHostName() {
    TestCertificate certificate =
        TestCertificate.create(dir, "erpnext", "CN=erp.example.com", "dns:erp.example.com");
    try (FakeHttpServer server = FakeHttpServer.https(certificate.serverContext())) {
      SSLContext context = TlsContexts.trustingAuthorities(certificate.pem());

      assertThatThrownBy(() -> get(context, server.uri()))
          .isInstanceOf(SSLHandshakeException.class);
    }
  }

  @Test
  void systemContextRefusesSelfSignedCertificates() {
    TestCertificate certificate = TestCertificate.create(dir, "erpnext");
    try (FakeHttpServer server = FakeHttpServer.https(certificate.serverContext())) {
      assertThatThrownBy(() -> get(TlsContexts.system(), server.uri()))
          .isInstanceOf(SSLHandshakeException.class);
    }
  }

  @Test
  void aJdkWithoutTlsEndsInAnIllegalState() {
    assertThatThrownBy(() ->
            TlsContexts.jdk(() -> {
              throw new java.security.NoSuchAlgorithmException("TLS");
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the TLS of the JDK is not available");
  }
}
