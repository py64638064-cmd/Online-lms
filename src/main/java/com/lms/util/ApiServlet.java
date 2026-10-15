package com.lms.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public abstract class ApiServlet extends HttpServlet {
  public static final Gson GSON = new Gson();

  public static void send(HttpServletResponse response, int status, Object body) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(GSON.toJson(body));
  }

  public static void message(HttpServletResponse response, int status, String message) throws IOException {
    send(response, status, Map.of("message", message));
  }

  public static void error(HttpServletResponse response, int status, String message) throws IOException {
    send(response, status, Map.of("error", message));
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    request.setCharacterEncoding("UTF-8");
    try {
      super.service(request, response);
    } catch (ApiError e) {
      error(response, e.status, e.getMessage());
    } catch (DbException e) {
      if (e.sqlState != null && e.sqlState.startsWith("23")) {
        error(response, 409, "That record already exists or is still in use.");
      } else {
        log("Database error", e);
        error(response, 500, "Database error. Check MySQL and db.properties.");
      }
    } catch (RuntimeException e) {
      log("Unexpected error", e);
      error(response, 500, "Something went wrong on the server.");
    }
  }

  protected User me(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    User user = session == null ? null : (User) session.getAttribute("user");
    if (user == null) throw new ApiError(401, "Please sign in");
    return user;
  }

  protected String[] segs(HttpServletRequest request) {
    String path = request.getPathInfo();
    return path == null || path.equals("/") ? new String[0] : path.substring(1).split("/");
  }

  protected long id(String value) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 1) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException e) {
      throw new ApiError(400, "Invalid id");
    }
  }

  protected JsonObject body(HttpServletRequest request) {
    try {
      JsonElement element = JsonParser.parseReader(request.getReader());
      return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    } catch (Exception e) {
      throw new ApiError(400, "Invalid JSON body");
    }
  }

  protected String str(JsonObject body, String key, boolean required) {
    JsonElement element = body.get(key);
    String value = element != null && !element.isJsonNull() ? element.getAsString().trim() : "";
    if (required && value.isEmpty()) throw new ApiError(400, "Missing required field: " + key);
    return value;
  }

  protected String boolString(JsonObject body, String key) {
    JsonElement element = body.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
      throw new ApiError(400, "Missing or invalid field: " + key);
    }
    return String.valueOf(element.getAsBoolean());
  }

  protected long number(JsonObject body, String key, long minimum, long maximum) {
    try {
      long value = body.get(key).getAsLong();
      if (value < minimum || value > maximum) throw new NumberFormatException();
      return value;
    } catch (Exception e) {
      throw new ApiError(400, key + " is out of range");
    }
  }
}
