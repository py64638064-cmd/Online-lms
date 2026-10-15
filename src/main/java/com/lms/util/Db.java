package com.lms.util;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class Db {
  private static String url;
  private static String user;
  private static String password;

  static {
    try (InputStream in = Db.class.getClassLoader().getResourceAsStream("db.properties")) {
      if (in != null) {
        Properties properties = new Properties();
        properties.load(in);
        url = properties.getProperty("db.url");
        user = properties.getProperty("db.user");
        password = properties.getProperty("db.password");
      }
    } catch (Exception e) {
      throw new IllegalStateException("Could not load db.properties", e);
    }
  }

  private Db() {}

  public static Connection conn() throws SQLException {
    String configuredUrl = System.getenv("DB_URL");
    String configuredUser = System.getenv("DB_USER");
    String configuredPassword = System.getenv("DB_PASSWORD");
    String connectionUrl = configuredUrl == null ? url : configuredUrl;
    String connectionUser = configuredUser == null ? user : configuredUser;
    String connectionPassword = configuredPassword == null ? password : configuredPassword;
    if (connectionUrl == null || connectionUser == null || connectionPassword == null) {
      throw new SQLException("Set db.url, db.user, and db.password in db.properties or environment variables.");
    }
    try {
      Class.forName("com.mysql.cj.jdbc.Driver");
    } catch (ClassNotFoundException e) {
      throw new SQLException("MySQL JDBC driver is not available.", e);
    }
    return DriverManager.getConnection(connectionUrl, connectionUser, connectionPassword);
  }

  private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    return statement;
  }

  public static List<Map<String, Object>> query(String sql, Object... args) {
    try (Connection connection = conn();
         PreparedStatement statement = prepare(connection, sql, args);
         ResultSet result = statement.executeQuery()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      ResultSetMetaData metadata = result.getMetaData();
      while (result.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
          Object value = result.getObject(i);
          if (value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof java.sql.Timestamp) {
            value = value.toString();
          }
          row.put(metadata.getColumnLabel(i), value);
        }
        rows.add(row);
      }
      return rows;
    } catch (SQLException e) {
      throw new DbException(e);
    }
  }

  public static Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = query(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public static long count(String sql, Object... args) {
    Map<String, Object> row = one(sql, args);
    return row == null ? 0 : ((Number) row.values().iterator().next()).longValue();
  }

  public static int update(String sql, Object... args) {
    try (Connection connection = conn();
         PreparedStatement statement = prepare(connection, sql, args)) {
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new DbException(e);
    }
  }

  public static long insert(String sql, Object... args) {
    try (Connection connection = conn();
         PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        return keys.next() ? keys.getLong(1) : 0;
      }
    } catch (SQLException e) {
      throw new DbException(e);
    }
  }
}
