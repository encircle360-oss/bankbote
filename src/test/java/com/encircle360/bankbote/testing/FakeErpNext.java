package com.encircle360.bankbote.testing;

import com.encircle360.bankbote.testing.FakeHttpServer.Request;
import com.encircle360.bankbote.testing.FakeHttpServer.Response;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An ERPNext of one company, as far as Bankbote talks to it: the resources Bank Account and Bank
 * Transaction of the REST API with token authentication, kept in memory.
 */
public final class FakeErpNext implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A bank account of the fake. */
  public record BankAccount(String name, String iban, boolean companyAccount) {}

  private final FakeHttpServer server;
  private final String token;
  private final List<BankAccount> bankAccounts = new CopyOnWriteArrayList<>();
  private final List<ObjectNode> transactions = new CopyOnWriteArrayList<>();
  private final AtomicInteger sequence = new AtomicInteger();
  private volatile boolean keepsTransactionId = true;
  private volatile boolean submitsOnInsert = true;
  private volatile Function<Request, Response> override = request -> null;

  public FakeErpNext(String apiKey, String apiSecret) {
    this.token = "token " + apiKey + ":" + apiSecret;
    this.server = FakeHttpServer.http().handler(this::handle);
  }

  public FakeHttpServer server() {
    return server;
  }

  public FakeErpNext bankAccount(String name, String iban, boolean companyAccount) {
    bankAccounts.add(new BankAccount(name, iban, companyAccount));
    return this;
  }

  /** Lets the next answers come from {@code handler} where it returns a response. */
  public FakeErpNext override(Function<Request, Response> handler) {
    this.override = handler;
    return this;
  }

  /** Makes the fake drop {@code transaction_id} on insert, as a read-only field could be. */
  public FakeErpNext dropTransactionId() {
    this.keepsTransactionId = false;
    return this;
  }

  /** Makes the fake keep inserted documents as drafts, whatever their docstatus. */
  public FakeErpNext insertAsDraft() {
    this.submitsOnInsert = false;
    return this;
  }

  /** Adds a bank transaction as it would stand in the database. */
  public FakeErpNext existing(String transactionId, String bankAccount, int docstatus) {
    ObjectNode doc = JSON.createObjectNode();
    doc.put("name", "ACC-BTN-2026-" + String.format("%05d", sequence.incrementAndGet()));
    doc.put("transaction_id", transactionId);
    doc.put("bank_account", bankAccount);
    doc.put("docstatus", docstatus);
    transactions.add(doc);
    return this;
  }

  public List<ObjectNode> transactions() {
    return List.copyOf(transactions);
  }

  public List<Request> requests() {
    return server.requests();
  }

  private Response handle(Request request) throws Exception {
    Response overridden = override.apply(request);
    if (overridden != null) {
      return overridden;
    }
    if (!token.equals(request.header("Authorization"))) {
      return Response.json(401, "{\"exc_type\":\"AuthenticationError\"}");
    }
    String path = request.uri().getRawPath();
    Map<String, String> query = query(request.uri().getRawQuery());
    if (request.method().equals("GET") && path.equals("/api/resource/Bank%20Account")) {
      return list(
          bankAccounts.stream()
              .filter(account -> matches(account, query))
              .map(
                  account -> {
                    ObjectNode node = JSON.createObjectNode();
                    node.put("name", account.name());
                    node.put("iban", account.iban());
                    return node;
                  })
              .toList(),
          query);
    }
    if (request.method().equals("GET") && path.equals("/api/resource/Bank%20Transaction")) {
      return list(transactions.stream().filter(doc -> matches(doc, query)).toList(), query);
    }
    if (request.method().equals("POST") && path.equals("/api/resource/Bank%20Transaction")) {
      ObjectNode doc = (ObjectNode) JSON.readTree(request.body());
      doc.put("name", "ACC-BTN-2026-" + String.format("%05d", sequence.incrementAndGet()));
      if (!keepsTransactionId) {
        doc.remove("transaction_id");
      }
      if (!submitsOnInsert) {
        doc.put("docstatus", 0);
      }
      transactions.add(doc);
      return Response.json(200, JSON.writeValueAsString(Map.of("data", doc)));
    }
    String prefix = "/api/resource/Bank%20Transaction/";
    if (request.method().equals("PUT") && path.startsWith(prefix)) {
      String name = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
      for (ObjectNode doc : transactions) {
        if (doc.get("name").asString().equals(name)) {
          JsonNode update = JSON.readTree(request.body());
          doc.put("docstatus", update.get("docstatus").asInt());
          return Response.json(200, JSON.writeValueAsString(Map.of("data", doc)));
        }
      }
      return Response.json(404, "{\"exc_type\":\"DoesNotExistError\"}");
    }
    return Response.json(404, "{}");
  }

  private Response list(List<? extends JsonNode> rows, Map<String, String> query) {
    int start = Integer.parseInt(query.getOrDefault("limit_start", "0"));
    int length = Integer.parseInt(query.getOrDefault("limit_page_length", "20"));
    List<JsonNode> page =
        new ArrayList<>(
            rows.subList(Math.min(start, rows.size()), Math.min(start + length, rows.size())));
    return Response.json(200, JSON.writeValueAsString(Map.of("data", page)));
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> query = new HashMap<>();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        String[] parts = pair.split("=", 2);
        query.put(
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
            parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
      }
    }
    return query;
  }

  private static boolean matches(BankAccount account, Map<String, String> query) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("is_company_account", account.companyAccount() ? 1 : 0);
    fields.put("iban", account.iban());
    fields.put("name", account.name());
    return matches(fields, query);
  }

  private static boolean matches(ObjectNode doc, Map<String, String> query) {
    Map<String, Object> fields = new LinkedHashMap<>();
    doc.properties()
        .forEach(
            entry ->
                fields.put(
                    entry.getKey(),
                    entry.getValue().isNumber() ? entry.getValue().asInt() : entry.getValue().asString()));
    return matches(fields, query);
  }

  private static boolean matches(Map<String, Object> fields, Map<String, String> query) {
    String filters = query.get("filters");
    if (filters == null) {
      return true;
    }
    for (JsonNode filter : JSON.readTree(filters)) {
      Object value = fields.get(filter.get(0).asString());
      String operator = filter.get(1).asString();
      JsonNode expected = filter.get(2);
      boolean ok =
          switch (operator) {
            case "=" ->
                expected.isNumber()
                    ? value instanceof Integer number && number == expected.asInt()
                    : expected.asString().equals(String.valueOf(value));
            case "in" -> {
              boolean found = false;
              for (JsonNode candidate : expected) {
                found |= candidate.asString().equals(String.valueOf(value));
              }
              yield found;
            }
            default -> throw new IllegalArgumentException("operator " + operator);
          };
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void close() {
    server.close();
  }
}
