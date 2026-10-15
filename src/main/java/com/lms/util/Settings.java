package com.lms.util;

public final class Settings {
  private Settings() {}

  public static String get(String key, String fallback) {
    java.util.Map<String, Object> row = Db.one("SELECT setting_value FROM settings WHERE setting_key=?", key);
    return row == null ? fallback : (String) row.get("setting_value");
  }

  public static boolean bool(String key, boolean fallback) {
    return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
  }
}
