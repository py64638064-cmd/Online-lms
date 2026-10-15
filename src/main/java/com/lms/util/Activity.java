package com.lms.util;

public final class Activity {
  private Activity() {}

  public static void log(Long userId, String action, String detail) {
    Db.insert("INSERT INTO activity_log(user_id,action,detail) VALUES(?,?,?)", userId, action, detail);
  }
}
