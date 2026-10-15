package com.lms.util;

public class ApiError extends RuntimeException {
  public final int status;

  public ApiError(int status, String message) {
    super(message);
    this.status = status;
  }
}
