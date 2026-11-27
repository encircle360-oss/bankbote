package com.encircle360.oss.bankbote.hibiscus;

import com.encircle360.oss.bankbote.net.Failures;
import com.encircle360.oss.bankbote.secret.Secret;
import com.encircle360.oss.bankbote.source.SourceException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the JSON beans of Hibiscus Server under {@code /webadmin/rest/}, with HTTP Basic
 * authentication of the master password, the only credential Hibiscus Server has.
 *
 * <p>Redirects are never followed, so the master password goes nowhere but the configured address.
 */
public final class HibiscusClient {

  /** The largest answer Bankbote reads; a window of several years fits many times over. */
  static final int DEFAULT_MAX_BYTES = 128 * 1024 * 1024;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI restBase;
  private final String authorization;
  private final HttpClient http;
  private final Duration requestTimeout;
  private final int maxBytes;

  public HibiscusClient(
      URI baseUrl,
      String username,
      Secret masterPassword,
      SSLContext tls,
      Duration connectTimeout,
      Duration requestTimeout) {
    this(baseUrl, username, masterPassword, tls, connectTimeout, requestTimeout, DEFAULT_MAX_BYTES);
  }

  HibiscusClient(
      URI baseUrl,
      String username,
      Secret masterPassword,
      SSLContext tls,
      Duration connectTimeout,
      Duration requestTimeout,
      int maxBytes) {
    String base = baseUrl.toString();
    this.restBase = URI.create((base.endsWith("/") ? base : base + "/") + "webadmin/rest/");
    this.authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (username + ":" + masterPassword.reveal()).getBytes(StandardCharsets.UTF_8));
    this.http =
        HttpClient.newBuilder()
            .sslContext(tls)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.requestTimeout = requestTimeout;
    this.maxBytes = maxBytes;
  }

  /** Reads a bean that answers with a JSON list, such as {@code hibiscus/konto/list}. */
  JsonNode list(String command) throws SourceException {
    HttpRequest request =
        HttpRequest.newBuilder(restBase.resolve(command))
            .timeout(requestTimeout)
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<InputStream> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw unreachable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceException("interrupted while waiting for Hibiscus Server");
    }
    try (InputStream body = response.body()) {
      int status = response.statusCode();
      if (status == 401 || status == 403) {
        throw new SourceException(
            "Hibiscus Server rejected the master password (HTTP " + status + ")");
      }
      if (status != 200) {
        throw new SourceException("Hibiscus Server answered HTTP " + status);
      }
      byte[] bytes = body.readNBytes(maxBytes + 1);
      if (bytes.length > maxBytes) {
        throw new SourceException("Hibiscus Server answered with more than " + maxBytes + " bytes");
      }
      JsonNode node = JSON.readTree(new String(bytes, charset(response)));
      if (!node.isArray()) {
        throw notAList();
      }
      return node;
    } catch (JacksonException e) {
      throw notAList();
    } catch (IOException e) {
      throw unreachable(e);
    }
  }

  /**
   * The charset of the answer. Jetty writes {@code application/json} in UTF-8 and an answer without
   * a content type, which Hibiscus Server sends when {@code json.indent} is 0, in ISO-8859-1, the
   * default of the servlet specification.
   */
  static Charset charset(HttpResponse<?> response) {
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    for (String parameter : contentType.split(";")) {
      String[] pair = parameter.strip().split("=", 2);
      if (pair.length == 2 && pair[0].equalsIgnoreCase("charset")) {
        try {
          return Charset.forName(pair[1].strip().replace("\"", ""));
        } catch (IllegalArgumentException e) {
          return StandardCharsets.UTF_8;
        }
      }
    }
    return contentType.toLowerCase(Locale.ROOT).startsWith("application/json")
        ? StandardCharsets.UTF_8
        : StandardCharsets.ISO_8859_1;
  }

  private static SourceException notAList() {
    return new SourceException("Hibiscus Server answered with something other than a JSON list");
  }

  private static SourceException unreachable(IOException e) {
    return new SourceException("Hibiscus Server cannot be reached: " + Failures.describe(e));
  }
}
