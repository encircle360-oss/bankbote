package com.encircle360.oss.bankbote.integration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;

/**
 * Accounts and transactions written straight into the database of a Hibiscus Server, in the columns
 * of the vendor schema {@code plugins/hibiscus/sql/mysql-create.sql}. No bank is involved: every
 * value is invented.
 */
public final class HibiscusFixtures {

  private final HibiscusServer server;

  public HibiscusFixtures(HibiscusServer server) {
    this.server = server;
  }

  /** Inserts an account and gives its id. {@code iban} may be null for an account without one. */
  public String account(String iban, String currency) throws SQLException {
    try (Connection connection = server.database();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO konto (kontonummer, blz, name, kundennummer, waehrung, iban, bic,"
                    + " bezeichnung) VALUES (?, '12345678', 'Beispiel GmbH', '4711', ?, ?,"
                    + " 'TESTDEFFXXX', 'Girokonto')",
                Statement.RETURN_GENERATED_KEYS)) {
      insert.setString(1, iban == null ? "0" : iban.substring(Math.max(0, iban.length() - 10)));
      insert.setString(2, currency);
      insert.setString(3, iban);
      insert.executeUpdate();
      return generatedId(insert);
    }
  }

  /** A transaction to insert; every field but account, date and amount may stay empty. */
  public static final class Row {
    private final String accountId;
    private final LocalDate date;
    private final double amount;
    private String purpose;
    private String purpose2;
    private String purpose3;
    private String name;
    private String name2;
    private String counterpartyAccount;
    private String type;
    private String customerReference;
    private String endToEndId;
    private Integer flags;

    private Row(String accountId, LocalDate date, double amount) {
      this.accountId = accountId;
      this.date = date;
      this.amount = amount;
    }

    public Row purpose(String first, String second, String third) {
      this.purpose = first;
      this.purpose2 = second;
      this.purpose3 = third;
      return this;
    }

    public Row name(String first, String second) {
      this.name = first;
      this.name2 = second;
      return this;
    }

    public Row counterparty(String account) {
      this.counterpartyAccount = account;
      return this;
    }

    public Row type(String value) {
      this.type = value;
      return this;
    }

    public Row references(String endToEnd, String customer) {
      this.endToEndId = endToEnd;
      this.customerReference = customer;
      return this;
    }

    /** The column {@code flags}; {@code Umsatz.FLAG_NOTBOOKED} is 2. Null leaves it empty. */
    public Row flags(Integer value) {
      this.flags = value;
      return this;
    }
  }

  public static Row row(String accountId, LocalDate date, double amount) {
    return new Row(accountId, date, amount);
  }

  /** Inserts a transaction and gives its id. */
  public String insert(Row row) throws SQLException {
    try (Connection connection = server.database();
        PreparedStatement insert = prepare(connection)) {
      bind(insert, row);
      insert.executeUpdate();
      return generatedId(insert);
    }
  }

  /** Inserts the same transaction {@code count} times in one batch. */
  public void insertMany(Row row, int count) throws SQLException {
    try (Connection connection = server.database();
        PreparedStatement insert = prepare(connection)) {
      connection.setAutoCommit(false);
      for (int i = 0; i < count; i++) {
        bind(insert, row);
        insert.addBatch();
      }
      insert.executeBatch();
      connection.commit();
    }
  }

  private static PreparedStatement prepare(Connection connection) throws SQLException {
    return connection.prepareStatement(
        "INSERT INTO umsatz (konto_id, datum, valuta, betrag, zweck, zweck2, zweck3,"
            + " empfaenger_name, empfaenger_name2, empfaenger_konto, art, customerref, endtoendid,"
            + " flags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        Statement.RETURN_GENERATED_KEYS);
  }

  private static void bind(PreparedStatement insert, Row row) throws SQLException {
    insert.setInt(1, Integer.parseInt(row.accountId));
    insert.setDate(2, Date.valueOf(row.date));
    insert.setDate(3, Date.valueOf(row.date));
    insert.setDouble(4, row.amount);
    insert.setString(5, row.purpose);
    insert.setString(6, row.purpose2);
    insert.setString(7, row.purpose3);
    insert.setString(8, row.name);
    insert.setString(9, row.name2);
    insert.setString(10, row.counterpartyAccount);
    insert.setString(11, row.type);
    insert.setString(12, row.customerReference);
    insert.setString(13, row.endToEndId);
    if (row.flags == null) {
      insert.setNull(14, Types.INTEGER);
    } else {
      insert.setInt(14, row.flags);
    }
  }

  private static String generatedId(PreparedStatement insert) throws SQLException {
    try (ResultSet keys = insert.getGeneratedKeys()) {
      keys.next();
      return Long.toString(keys.getLong(1));
    }
  }
}
