package com.lms.web;

import com.google.gson.JsonObject;
import com.lms.util.Activity;
import com.lms.util.ApiError;
import com.lms.util.ApiServlet;
import com.lms.util.Db;
import com.lms.util.Settings;
import com.lms.util.User;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.mindrot.jbcrypt.BCrypt;

@WebServlet("/api/auth/*")
public class AuthServlet extends ApiServlet {
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  public static boolean validEmail(String email) {
    return EMAIL.matcher(email).matches();
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String endpoint = segs(request).length > 0 ? segs(request)[0] : "";
    if ("me".equals(endpoint)) {
      send(response, 200, me(request));
    } else if ("settings".equals(endpoint)) {
      send(response, 200, Map.of(
          "siteName", Settings.get("site_name", "Coursecraft"),
          "allowRegistration", Settings.bool("allow_registration", true)));
    } else {
      throw new ApiError(404, "Not found");
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String endpoint = segs(request).length > 0 ? segs(request)[0] : "";
    if ("logout".equals(endpoint)) {
      HttpSession session = request.getSession(false);
      if (session != null) session.invalidate();
      message(response, 200, "Signed out");
      return;
    }
    JsonObject body = body(request);
    if ("register".equals(endpoint)) {
      if (!Settings.bool("allow_registration", true)) throw new ApiError(403, "Registration is closed");
      String name = str(body, "name", true);
      String email = str(body, "email", true).toLowerCase();
      String password = str(body, "password", true);
      String role = str(body, "role", true);
      if (!EMAIL.matcher(email).matches()) throw new ApiError(400, "Enter a valid email");
      if (!Set.of("INSTRUCTOR", "STUDENT").contains(role)) throw new ApiError(400, "Choose instructor or student");
      if (password.length() < 6) throw new ApiError(400, "Password must be at least 6 characters");
      Db.insert("INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,?)",
          name, email, BCrypt.hashpw(password, BCrypt.gensalt()), role);
      Activity.log(null, "Account registered", name + " joined as " + role.toLowerCase());
      message(response, 201, "Account created. You can sign in now.");
      return;
    }
    if ("login".equals(endpoint)) {
      String email = str(body, "email", true).toLowerCase();
      String password = str(body, "password", true);
      Map<String, Object> row = Db.one(
          "SELECT id,name,email,password_hash,role,status FROM users WHERE email=?", email);
      if (row == null) throw new ApiError(401, "Email or password is incorrect");
      String savedHash = (String) row.get("password_hash");
      boolean bootstrap = "admin@coursecraft.local".equals(email)
          && "ADMIN".equals(row.get("role"))
          && "__BOOTSTRAP_ADMIN__".equals(savedHash);
      boolean valid = bootstrap ? "Admin@123".equals(password) : BCrypt.checkpw(password, savedHash);
      if (!valid) throw new ApiError(401, "Email or password is incorrect");
      if (!"ACTIVE".equals(row.get("status"))) throw new ApiError(403, "Account suspended");
      if (bootstrap) {
        Db.update("UPDATE users SET password_hash=? WHERE id=? AND password_hash=?",
            BCrypt.hashpw(password, BCrypt.gensalt()), row.get("id"), "__BOOTSTRAP_ADMIN__");
      }
      HttpSession oldSession = request.getSession(false);
      if (oldSession != null) oldSession.invalidate();
      HttpSession session = request.getSession(true);
      User user = new User(((Number) row.get("id")).longValue(), (String) row.get("name"),
          (String) row.get("email"), (String) row.get("role"));
      session.setAttribute("user", user);
      session.setMaxInactiveInterval(28800);
      send(response, 200, user);
      return;
    }
    throw new ApiError(404, "Not found");
  }
}
