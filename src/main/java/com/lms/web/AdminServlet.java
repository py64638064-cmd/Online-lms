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
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.mindrot.jbcrypt.BCrypt;

@WebServlet("/api/admin/*")
public class AdminServlet extends ApiServlet {
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String[] path = segs(request);
    String endpoint = path.length > 0 ? path[0] : "";
    if ("users".equals(endpoint)) {
      send(response, 200, Db.query("SELECT id,name,email,role,status,created_at FROM users ORDER BY created_at DESC"));
    } else if ("courses".equals(endpoint)) {
      send(response, 200, Db.query(
          "SELECT c.*,u.name instructor_name," +
          "(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) enrollments," +
          "(SELECT COUNT(*) FROM lessons l WHERE l.course_id=c.id) lesson_count " +
          "FROM courses c JOIN users u ON u.id=c.instructor_id " +
          "ORDER BY FIELD(c.status,'PENDING','APPROVED','REJECTED'),c.created_at DESC"));
    } else if ("activity".equals(endpoint)) {
      send(response, 200, Db.query(
          "SELECT a.action,a.detail,a.created_at,u.name actor_name FROM activity_log a " +
          "LEFT JOIN users u ON u.id=a.user_id ORDER BY a.created_at DESC LIMIT 30"));
    } else if ("stats".equals(endpoint)) {
      send(response, 200, Map.of(
          "courses", Db.count("SELECT COUNT(*) FROM courses"),
          "pending", Db.count("SELECT COUNT(*) FROM courses WHERE status='PENDING'"),
          "users", Db.count("SELECT COUNT(*) FROM users"),
          "students", Db.count("SELECT COUNT(*) FROM users WHERE role='STUDENT' AND status='ACTIVE'"),
          "enrollments", Db.count("SELECT COUNT(*) FROM enrollments"),
          "recent", Db.query(
              "SELECT c.title,c.status,c.created_at,u.name instructor_name," +
              "(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) enrollments " +
              "FROM courses c JOIN users u ON u.id=c.instructor_id ORDER BY c.created_at DESC LIMIT 6")));
    } else if ("settings".equals(endpoint)) {
      send(response, 200, Map.of(
          "siteName", Settings.get("site_name", "Coursecraft"),
          "contactEmail", Settings.get("contact_email", "hello@coursecraft.local"),
          "allowRegistration", Settings.bool("allow_registration", true),
          "requireApproval", Settings.bool("require_course_approval", true)));
    } else {
      throw new ApiError(404, "Not found");
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User actor = me(request);
    String[] path = segs(request);
    if (path.length == 2 && "courses".equals(path[0])) {
      JsonObject body = body(request);
      String status = str(body, "status", true);
      if (!Set.of("APPROVED", "REJECTED", "PENDING").contains(status)) {
        throw new ApiError(400, "Invalid course status");
      }
      long courseId = id(path[1]);
      Map<String, Object> course = Db.one("SELECT title FROM courses WHERE id=?", courseId);
      if (course == null) throw new ApiError(404, "Course not found");
      Db.update("UPDATE courses SET status=? WHERE id=?", status, courseId);
      Activity.log(actor.id, "Course " + status.toLowerCase(),
          course.get("title") + " was " + status.toLowerCase());
      message(response, 200, "Course status updated");
      return;
    }
    if (path.length == 1 && "users".equals(path[0])) {
      JsonObject body = body(request);
      String name = str(body, "name", true);
      String email = str(body, "email", true).toLowerCase();
      String role = str(body, "role", true);
      String password = str(body, "password", true);
      if (!validRole(role) || password.length() < 6 || !AuthServlet.validEmail(email)) {
        throw new ApiError(400, "Enter a valid email, role, and password of at least 6 characters");
      }
      Db.insert("INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,?)",
          name, email, BCrypt.hashpw(password, BCrypt.gensalt()), role);
      Activity.log(actor.id, "User created", name + " added as " + role.toLowerCase());
      message(response, 201, "User created");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  @Override
  protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User actor = me(request);
    String[] path = segs(request);
    JsonObject body = body(request);
    if (path.length == 2 && "users".equals(path[0])) {
      String status = str(body, "status", true);
      String role = str(body, "role", true);
      String name = str(body, "name", true);
      String email = str(body, "email", true).toLowerCase();
      long userId = id(path[1]);
      if (!Set.of("ACTIVE", "SUSPENDED").contains(status) || !validRole(role)
          || !AuthServlet.validEmail(email)) {
        throw new ApiError(400, "Enter a valid email, role, and status");
      }
      if (userId == actor.id && !"ADMIN".equals(role)) {
        throw new ApiError(400, "You cannot remove your own administrator role");
      }
      if (Db.count("SELECT COUNT(*) FROM users WHERE id=?", userId) == 0) {
        throw new ApiError(404, "User not found");
      }
      Db.update("UPDATE users SET name=?,email=?,role=?,status=? WHERE id=?",
          name, email, role, status, userId);
      Activity.log(actor.id, "User updated", name + " · " + role.toLowerCase() + " · " + status.toLowerCase());
      message(response, 200, "User updated");
      return;
    }
    if (path.length == 1 && "settings".equals(path[0])) {
      set("site_name", str(body, "siteName", true));
      set("contact_email", str(body, "contactEmail", true));
      set("allow_registration", boolString(body, "allowRegistration"));
      set("require_course_approval", boolString(body, "requireApproval"));
      Activity.log(actor.id, "Settings updated", "Platform configuration was changed");
      message(response, 200, "Settings saved");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User actor = me(request);
    String[] path = segs(request);
    if (path.length == 2 && "users".equals(path[0])) {
      long userId = id(path[1]);
      if (userId == actor.id) throw new ApiError(400, "You cannot delete your own account");
      Map<String, Object> target = Db.one("SELECT name FROM users WHERE id=? AND role<>'ADMIN'", userId);
      if (target == null) throw new ApiError(400, "User not found or protected administrator");
      Db.update("DELETE FROM users WHERE id=? AND role<>'ADMIN'", userId);
      Activity.log(actor.id, "User deleted", target.get("name") + " was removed");
      message(response, 200, "User deleted");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  private boolean validRole(String role) {
    return Set.of("ADMIN", "INSTRUCTOR", "STUDENT").contains(role);
  }

  private void set(String key, String value) {
    Db.update("INSERT INTO settings(setting_key,setting_value) VALUES(?,?) " +
        "ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", key, value);
  }
}
