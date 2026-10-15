package com.lms.util;

public class DbException extends RuntimeException {
  public final String sqlState;

  public DbException(java.sql.SQLException cause) {
    super(cause);
    sqlState = cause.getSQLState();
  }
}
