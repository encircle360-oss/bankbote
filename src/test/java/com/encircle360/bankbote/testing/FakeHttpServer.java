package com.encircle360.bankbote.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;

/**
 * A local HTTP or HTTPS server of the JDK on a free port of 127.0.0.1, which records every request
 * and answers with a handler the test sets.
 */
public final class FakeHttpServer implements AutoCloseable {

  /** A recorded request. */
  public record Request(String method, URI uri, Map<String, List<String>> headers, String body) {

    public String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(entry -> entry.getValue().getFirst())
          .findFirst()
          .orElse(null);
    }
  }

  /** A response of the fake server. */
  public record Response(int status, String contentType, String body, Map<String, String> headers) {

    public static Response json(int status, String body) {
      return new Response(status, "application/json", body, Map.of());
    }

    public static Response xml(String body) {
      return new Response(200, "text/xml", body, Map.of());
    }
  }

  /** Answers a recorded request. */
  @FunctionalInterface
  public interface Handler {
    Response handle(Request request) throws Exception;
  }

  /** A response header that makes the server announce more bytes than it sends and hang up. */
  public static final String TRUNCATE = "X-Fake-Truncate";

  private final HttpServer server;
  private final String scheme;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private volatile Handler handler = request -> Response.json(404, "{}");

  private FakeHttpServer(HttpServer server, String scheme) {
    this.server = server;
    this.scheme = scheme;
    server.createContext("/", this::exchange);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  public static FakeHttpServer http() {
    try {
      return new FakeHttpServer(HttpServer.create(loopback(), 0), "http");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static FakeHttpServer https(SSLContext context) {
    try {
      HttpsServer server = HttpsServer.create(loopback(), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(context));
      return new FakeHttpServer(server, "https");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static InetSocketAddress loopback() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
  }

  public FakeHttpServer handler(Handler newHandler) {
    this.handler = newHandler;
    return this;
  }

  public URI uri() {
    return URI.create(scheme + "://127.0.0.1:" + server.getAddress().getPort());
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  private void exchange(HttpExchange exchange) throws IOException {
    try (exchange) {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Request request =
          new Request(
              exchange.getRequestMethod(),
              exchange.getRequestURI(),
              Map.copyOf(exchange.getRequestHeaders()),
              body);
      requests.add(request);
      Response response;
      try {
        response = handler.handle(request);
      } catch (Exception e) {
        response = new Response(500, "text/plain", "fake server failed: " + e, Map.of());
      }
      byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", response.contentType());
      response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
      if (response.headers().containsKey(TRUNCATE)) {
        exchange.sendResponseHeaders(response.status(), bytes.length + 1000L);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
        return;
      }
      exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
