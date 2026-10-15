package com.lms.web;

import com.google.gson.JsonObject;
import com.lms.util.Activity;
import com.lms.util.ApiError;
import com.lms.util.ApiServlet;
import com.lms.util.Db;
import com.lms.util.User;
import java.io.IOException;
import java.util.Map;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.mindrot.jbcrypt.BCrypt;

@WebServlet("/api/student/*")
public class StudentServlet extends ApiServlet {
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User student = me(request);
    String[] path = segs(request);
    if (path.length == 0) throw new ApiError(404, "Not found");
    if ("courses".equals(path[0]) && path.length == 1) {
      send(response, 200, Db.query(
          "SELECT c.id,c.title,c.description,c.syllabus,c.category,c.status,c.created_at,u.name instructor_name," +
          "(SELECT COUNT(*) FROM lessons l WHERE l.course_id=c.id) lesson_count," +
          "EXISTS(SELECT 1 FROM enrollments e WHERE e.course_id=c.id AND e.student_id=?) enrolled " +
          "FROM courses c JOIN users u ON u.id=c.instructor_id WHERE c.status='APPROVED' ORDER BY c.created_at DESC",
          student.id));
    } else if ("enrollments".equals(path[0]) && path.length == 1) {
      send(response, 200, Db.query(
          "SELECT e.id enrollment_id,e.course_id,e.status,e.created_at enrolled_at,c.title,c.description,c.syllabus,c.category," +
          "u.name instructor_name,COUNT(DISTINCT l.id) lesson_count,COUNT(DISTINCT p.id) completed_count," +
          "CASE WHEN COUNT(DISTINCT l.id)=0 THEN 0 " +
          "ELSE ROUND(COUNT(DISTINCT p.id)*100.0/COUNT(DISTINCT l.id)) END progress " +
          "FROM enrollments e JOIN courses c ON c.id=e.course_id JOIN users u ON u.id=c.instructor_id " +
          "LEFT JOIN lessons l ON l.course_id=c.id " +
          "LEFT JOIN lesson_progress p ON p.lesson_id=l.id AND p.student_id=e.student_id " +
          "WHERE e.student_id=? GROUP BY e.id,c.id,u.id ORDER BY e.created_at DESC", student.id));
    } else if ("courses".equals(path[0]) && path.length == 3 && "lessons".equals(path[2])) {
      long courseId = id(path[1]);
      requireEnrollment(student.id, courseId);
      send(response, 200, Db.query(
          "SELECT l.id,l.title,l.description,l.resource_url,l.position," +
          "EXISTS(SELECT 1 FROM lesson_progress p WHERE p.lesson_id=l.id AND p.student_id=?) completed " +
          "FROM lessons l WHERE l.course_id=? ORDER BY l.position,l.id", student.id, courseId));
    } else if ("messages".equals(path[0]) && path.length == 1) {
      send(response, 200, Db.query(
          "SELECT m.id,m.body,m.created_at,c.title course_title,u.name instructor_name " +
          "FROM course_messages m JOIN courses c ON c.id=m.course_id " +
          "JOIN users u ON u.id=m.instructor_id JOIN enrollments e ON e.course_id=c.id " +
          "WHERE e.student_id=? ORDER BY m.created_at DESC", student.id));
    } else if ("profile".equals(path[0]) && path.length == 1) {
      send(response, 200, Map.of("name", student.name, "email", student.email));
    } else {
      throw new ApiError(404, "Not found");
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User student = me(request);
    String[] path = segs(request);
    JsonObject body = body(request);
    if (path.length == 1 && "enrollments".equals(path[0])) {
      long courseId = number(body, "courseId", 1, Long.MAX_VALUE);
      Map<String, Object> course = Db.one("SELECT title FROM courses WHERE id=? AND status='APPROVED'", courseId);
      if (course == null) throw new ApiError(404, "Approved course not found");
      Db.insert("INSERT INTO enrollments(course_id,student_id) VALUES(?,?)", courseId, student.id);
      Activity.log(student.id, "Course enrollment", "Enrolled in " + course.get("title"));
      message(response, 201, "You are enrolled in this course");
      return;
    }
    if (path.length == 1 && "progress".equals(path[0])) {
      long lessonId = number(body, "lessonId", 1, Long.MAX_VALUE);
      Map<String, Object> lesson = Db.one(
          "SELECT l.course_id,l.title FROM lessons l JOIN courses c ON c.id=l.course_id " +
          "JOIN enrollments e ON e.course_id=c.id AND e.student_id=? " +
          "WHERE l.id=? AND c.status='APPROVED'", student.id, lessonId);
      if (lesson == null) throw new ApiError(404, "Course lesson not found");
      String marked = boolString(body, "completed");
      if (Boolean.parseBoolean(marked)) {
        Db.update("INSERT INTO lesson_progress(lesson_id,student_id) VALUES(?,?) " +
            "ON DUPLICATE KEY UPDATE completed_at=CURRENT_TIMESTAMP", lessonId, student.id);
      } else {
        Db.update("DELETE FROM lesson_progress WHERE lesson_id=? AND student_id=?", lessonId, student.id);
      }
      Db.update("UPDATE enrollments SET status=CASE WHEN (" +
          "SELECT COUNT(*) FROM lessons l WHERE l.course_id=enrollments.course_id)>0 AND (" +
          "SELECT COUNT(*) FROM lesson_progress p JOIN lessons l ON l.id=p.lesson_id " +
          "WHERE l.course_id=enrollments.course_id AND p.student_id=enrollments.student_id)=" +
          "(SELECT COUNT(*) FROM lessons l WHERE l.course_id=enrollments.course_id) " +
          "THEN 'COMPLETED' ELSE 'ACTIVE' END WHERE course_id=? AND student_id=?",
          lesson.get("course_id"), student.id);
      message(response, 200, Boolean.parseBoolean(marked) ? "Lesson marked complete" : "Lesson marked incomplete");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  @Override
  protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User student = me(request);
    String[] path = segs(request);
    if (path.length == 1 && "profile".equals(path[0])) {
      JsonObject body = body(request);
      String name = str(body, "name", true);
      String email = str(body, "email", true).toLowerCase();
      String password = str(body, "password", false);
      if (!AuthServlet.validEmail(email)) throw new ApiError(400, "Enter a valid email");
      if (password.length() > 0 && password.length() < 6) {
        throw new ApiError(400, "Password must be at least 6 characters");
      }
      if (password.isEmpty()) {
        Db.update("UPDATE users SET name=?,email=? WHERE id=?", name, email, student.id);
      } else {
        Db.update("UPDATE users SET name=?,email=?,password_hash=? WHERE id=?",
            name, email, BCrypt.hashpw(password, BCrypt.gensalt()), student.id);
      }
      student.name = name;
      student.email = email;
      Activity.log(student.id, "Profile updated", name + " updated their student profile");
      message(response, 200, "Profile updated");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  private void requireEnrollment(long studentId, long courseId) {
    if (Db.count("SELECT COUNT(*) FROM enrollments e JOIN courses c ON c.id=e.course_id " +
        "WHERE e.course_id=? AND e.student_id=? AND c.status='APPROVED'", courseId, studentId) == 0) {
      throw new ApiError(403, "Enroll in this course to access its learning materials");
    }
  }
}
