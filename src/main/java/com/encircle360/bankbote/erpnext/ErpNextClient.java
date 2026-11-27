package com.encircle360.bankbote.erpnext;

import com.encircle360.bankbote.erpnext.ErpNextException.Kind;
import com.encircle360.bankbote.net.Failures;
import com.encircle360.bankbote.secret.Secret;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A client of the REST API of one ERPNext, with the API key and secret of that ERPNext only.
 *
 * <p>Every request goes to an address built from the configured base URL and a fixed path, and
 * redirects are never followed, so the token of one ERPNext never reaches another host.
 */
public final class ErpNextClient {

  static final int DEFAULT_PAGE_LENGTH = 500;
  private static final int IDS_PER_REQUEST = 50;
  private static final String BANK_ACCOUNT = "api/resource/Bank%20Account";
  private static final String BANK_TRANSACTION = "api/resource/Bank%20Transaction";
  private static final Pattern EXCEPTION_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,80}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String target;
  private final URI base;
  private final String authorization;
  private final HttpClient http;
  private final Duration requestTimeout;
  private final int pageLength;

  public ErpNextClient(
      String target,
      URI baseUrl,
      Secret apiKey,
      Secret apiSecret,
      SSLContext tls,
      Duration connectTimeout,
      Duration requestTimeout) {
    this(
        target,
        baseUrl,
        apiKey,
        apiSecret,
        tls,
        connectTimeout,
        requestTimeout,
        DEFAULT_PAGE_LENGTH);
  }

  ErpNextClient(
      String target,
      URI baseUrl,
      Secret apiKey,
      Secret apiSecret,
      SSLContext tls,
      Duration connectTimeout,
      Duration requestTimeout,
      int pageLength) {
    this.target = target;
    String text = baseUrl.toString();
    this.base = URI.create(text.endsWith("/") ? text : text + "/");
    this.authorization = "token " + apiKey.reveal() + ":" + apiSecret.reveal();
    this.http =
        HttpClient.newBuilder()
            .sslContext(tls)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.requestTimeout = requestTimeout;
    this.pageLength = pageLength;
  }

  /** The name of the target, as configured. */
  public String target() {
    return target;
  }

  /** All Bank Accounts of ERPNext marked as accounts of the company. */
  public List<CompanyBankAccount> companyBankAccounts() throws ErpNextException {
    List<CompanyBankAccount> accounts = new ArrayList<>();
    for (JsonNode row :
        list(
            BANK_ACCOUNT,
            List.of(List.of("is_company_account", "=", 1)),
            List.of("name", "iban"))) {
      accounts.add(new CompanyBankAccount(text(row, "name"), text(row, "iban")));
    }
    return accounts;
  }

  /** The Bank Transactions of a bank account that carry one of the transaction ids. */
  public List<ExistingTransaction> existing(String bankAccount, Collection<String> transactionIds)
      throws ErpNextException {
    List<String> ids = List.copyOf(transactionIds);
    List<ExistingTransaction> existing = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += IDS_PER_REQUEST) {
      List<String> chunk = ids.subList(from, Math.min(from + IDS_PER_REQUEST, ids.size()));
      for (JsonNode row :
          list(
              BANK_TRANSACTION,
              List.of(
                  List.of("bank_account", "=", bankAccount),
                  List.of("transaction_id", "in", chunk)),
              List.of("name", "transaction_id", "docstatus"))) {
        existing.add(
            new ExistingTransaction(
                text(row, "name"), text(row, "transaction_id"), row.path("docstatus").asInt(-1)));
      }
    }
    return existing;
  }

  /**
   * Creates a Bank Transaction with docstatus 1, which ERPNext inserts and submits in one request.
   * The answer has to carry the transaction id that was sent: the duplicate check of Bankbote
   * depends on it.
   */
  public InsertedTransaction insertSubmitted(BankTransactionDocument document)
      throws ErpNextException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("docstatus", 1);
    body.put("date", document.date().toString());
    body.put("bank_account", document.bankAccount());
    body.put("currency", document.currency());
    body.put("description", document.description());
    body.put("reference_number", document.referenceNumber());
    body.put("transaction_id", document.transactionId());
    body.put("deposit", document.deposit());
    body.put("withdrawal", document.withdrawal());
    body.put("transaction_type", document.transactionType());
    body.put("bank_party_name", document.bankPartyName());
    body.put("bank_party_iban", document.bankPartyIban());
    body.put("bank_party_account_number", document.bankPartyAccountNumber());
    JsonNode data = data(send("POST", BANK_TRANSACTION, body));
    if (!document.transactionId().equals(text(data, "transaction_id"))) {
      throw new ErpNextException(
          Kind.INCONSISTENT,
          "ERPNext "
              + target
              + " did not keep the transaction_id of the new bank transaction;"
              + " delivering more could create duplicates");
    }
    return new InsertedTransaction(text(data, "name"), data.path("docstatus").asInt(-1));
  }

  private List<JsonNode> list(String resource, List<?> filters, List<String> fields)
      throws ErpNextException {
    List<JsonNode> rows = new ArrayList<>();
    for (int start = 0; ; start += pageLength) {
      String query =
          "filters="
              + queryValue(JSON.writeValueAsString(filters))
              + "&fields="
              + queryValue(JSON.writeValueAsString(fields))
              + "&limit_start="
              + start
              + "&limit_page_length="
              + pageLength;
      JsonNode page = send("GET", resource + "?" + query, null).path("data");
      if (!page.isArray()) {
        throw unreadable();
      }
      page.forEach(rows::add);
      if (page.size() < pageLength) {
        return rows;
      }
    }
  }

  private JsonNode send(String method, String path, Object body) throws ErpNextException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(requestTimeout)
            .header("Authorization", authorization)
            .header("Accept", "application/json");
    if (body == null) {
      request.GET();
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    HttpResponse<String> response;
    try {
      response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ErpNextException(
          Kind.UNAVAILABLE, "ERPNext " + target + " cannot be reached: " + Failures.describe(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ErpNextException(
          Kind.UNAVAILABLE, "interrupted while waiting for ERPNext " + target);
    }
    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new ErpNextException(
          Kind.AUTHENTICATION, "ERPNext " + target + " rejected the API key (HTTP " + status + ")");
    }
    if (status >= 300 && status < 400) {
      throw new ErpNextException(
          Kind.UNAVAILABLE,
          "ERPNext "
              + target
              + " answered with a redirect (HTTP "
              + status
              + "), which Bankbote does not follow");
    }
    if (status >= 500) {
      throw new ErpNextException(
          Kind.UNAVAILABLE, "ERPNext " + target + " is unavailable (HTTP " + status + ")");
    }
    JsonNode json;
    try {
      json = JSON.readTree(response.body());
    } catch (JacksonException e) {
      json = null;
    }
    if (status >= 400) {
      throw new ErpNextException(
          Kind.REJECTED,
          "ERPNext "
              + target
              + " rejected the request (HTTP "
              + status
              + exceptionType(json)
              + ")");
    }
    if (json == null || !json.isObject()) {
      throw unreadable();
    }
    return json;
  }

  /** Frappe names the class of its exception in {@code exc_type}, which holds no data. */
  private static String exceptionType(JsonNode json) {
    String type = json == null ? "" : text(json, "exc_type");
    return EXCEPTION_TYPE.matcher(type).matches() ? ", " + type : "";
  }

  private JsonNode data(JsonNode answer) throws ErpNextException {
    JsonNode data = answer.path("data");
    if (!data.isObject()) {
      throw unreadable();
    }
    return data;
  }

  private ErpNextException unreadable() {
    return new ErpNextException(
        Kind.UNAVAILABLE, "ERPNext " + target + " answered with something Bankbote cannot read");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asString();
  }

  private static String queryValue(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
