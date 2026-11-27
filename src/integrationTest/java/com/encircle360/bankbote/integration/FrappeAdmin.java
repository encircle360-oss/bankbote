package com.encircle360.bankbote.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Administrator of a Frappe site over its REST API with a session, for fixtures and for looking
 * at what Bankbote has delivered. Bankbote itself never uses this account.
 */
public final class FrappeAdmin {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  private final HttpClient http;

  FrappeAdmin(URI base, String password) {
    this.base = base;
    this.http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    send("POST", "/api/method/login", Map.of("usr", "Administrator", "pwd", password));
  }

  /** The answer of a call, which fails the test unless it is 200. */
  public record Answer(int status, JsonNode body) {}

  public static String resource(String doctype) {
    return "/api/resource/"
        + URLEncoder.encode(doctype, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public static String resource(String doctype, String name) {
    return resource(doctype)
        + "/"
        + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Inserts a document and gives its data. */
  public JsonNode insert(String doctype, Map<String, ?> fields) {
    return send("POST", resource(doctype), fields).path("data");
  }

  /** Changes fields of a document as the desk does, which also submits a draft. */
  public JsonNode update(String doctype, String name, Map<String, ?> fields) {
    return send("PUT", resource(doctype, name), fields).path("data");
  }

  public JsonNode get(String doctype, String name) {
    return send("GET", resource(doctype, name), null).path("data");
  }

  /** Calls a whitelisted method with JSON arguments and gives its {@code message}. */
  public JsonNode call(String method, Map<String, ?> arguments) {
    return send("POST", "/api/method/" + method, arguments).path("message");
  }

  /** Calls a whitelisted method with form arguments, as the desk does, and gives its message. */
  public JsonNode callForm(String method, Map<String, String> arguments) {
    String form =
        arguments.entrySet().stream()
            .map(entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return check(
            "POST /api/method/" + method,
            exchange(
                HttpRequest.newBuilder(base.resolve("/api/method/" + method))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))))
        .path("message");
  }

  private JsonNode send(String method, String path, Object body) {
    HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
    if (body == null) {
      request.GET();
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    return check(method + " " + path, exchange(request));
  }

  private Answer exchange(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          http.send(
              request.header("Accept", "application/json").timeout(Duration.ofMinutes(5)).build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode body;
      try {
        body = JSON.readTree(response.body());
      } catch (RuntimeException e) {
        body = JSON.createObjectNode().put("text", response.body());
      }
      return new Answer(response.statusCode(), body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode check(String what, Answer answer) {
    if (answer.status() != 200) {
      throw new IllegalStateException(
          what + " answered HTTP " + answer.status() + ": " + answer.body().toString());
    }
    return answer.body();
  }
}
