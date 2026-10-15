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
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/api/instructor/*")
public class InstructorServlet extends ApiServlet {
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User instructor = me(request);
    String[] path = segs(request);
    if (path.length == 0) throw new ApiError(404, "Not found");
    if ("courses".equals(path[0]) && path.length == 1) {
      send(response, 200, Db.query(
          "SELECT c.*,(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) enrollments," +
          "(SELECT COUNT(*) FROM lessons l WHERE l.course_id=c.id) lesson_count," +
          "COALESCE((SELECT ROUND(AVG(CASE WHEN t.total=0 THEN 0 ELSE t.completed*100.0/t.total END)) " +
          "FROM (SELECT e.id,COUNT(l.id) total,COUNT(p.id) completed FROM enrollments e " +
          "LEFT JOIN lessons l ON l.course_id=e.course_id " +
          "LEFT JOIN lesson_progress p ON p.lesson_id=l.id AND p.student_id=e.student_id " +
          "WHERE e.course_id=c.id GROUP BY e.id) t),0) avg_progress " +
          "FROM courses c WHERE c.instructor_id=? ORDER BY c.created_at DESC", instructor.id));
    } else if ("courses".equals(path[0]) && path.length == 3 && "lessons".equals(path[2])) {
      long courseId = id(path[1]);
      requireCourse(instructor.id, courseId);
      send(response, 200, Db.query(
          "SELECT id,course_id,title,description,resource_url,position FROM lessons WHERE course_id=? ORDER BY position,id",
          courseId));
    } else if ("students".equals(path[0]) && path.length == 1) {
      send(response, 200, Db.query(
          "SELECT e.course_id,c.title course_title,e.student_id,u.name student_name,u.email student_email," +
          "e.created_at enrolled_at,COUNT(DISTINCT l.id) lesson_count,COUNT(DISTINCT p.id) completed_count," +
          "CASE WHEN COUNT(DISTINCT l.id)=0 THEN 0 " +
          "ELSE ROUND(COUNT(DISTINCT p.id)*100.0/COUNT(DISTINCT l.id)) END progress " +
          "FROM enrollments e JOIN courses c ON c.id=e.course_id " +
          "JOIN users u ON u.id=e.student_id LEFT JOIN lessons l ON l.course_id=c.id " +
          "LEFT JOIN lesson_progress p ON p.lesson_id=l.id AND p.student_id=u.id " +
          "WHERE c.instructor_id=? GROUP BY e.id,c.title,u.id,u.name,u.email,e.created_at ORDER BY c.title,u.name",
          instructor.id));
    } else if ("messages".equals(path[0]) && path.length == 1) {
      send(response, 200, Db.query(
          "SELECT m.id,m.course_id,m.body,m.created_at,c.title course_title " +
          "FROM course_messages m JOIN courses c ON c.id=m.course_id " +
          "WHERE m.instructor_id=? ORDER BY m.created_at DESC", instructor.id));
    } else {
      throw new ApiError(404, "Not found");
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User instructor = me(request);
    String[] path = segs(request);
    JsonObject body = body(request);
    if (path.length == 1 && "courses".equals(path[0])) {
      CourseFields fields = fields(body);
      String status = Settings.bool("require_course_approval", true) ? "PENDING" : "APPROVED";
      long courseId = Db.insert(
          "INSERT INTO courses(instructor_id,title,description,syllabus,category,status) VALUES(?,?,?,?,?,?)",
          instructor.id, fields.title, fields.description, fields.syllabus, fields.category, status);
      Activity.log(instructor.id, "Course created", fields.title + " was submitted for review");
      send(response, 201, Map.of("message",
          status.equals("PENDING") ? "Course submitted for approval" : "Course published",
          "courseId", courseId));
      return;
    }
    if (path.length == 3 && "courses".equals(path[0]) && "lessons".equals(path[2])) {
      long courseId = id(path[1]);
      requireCourse(instructor.id, courseId);
      LessonFields fields = lessonFields(body);
      long position = Db.count("SELECT COALESCE(MAX(position),0)+1 FROM lessons WHERE course_id=?", courseId);
      Db.insert("INSERT INTO lessons(course_id,title,description,resource_url,position) VALUES(?,?,?,?,?)",
          courseId, fields.title, fields.description, fields.resourceUrl, position);
      Activity.log(instructor.id, "Lesson added", fields.title + " was added to a course");
      message(response, 201, "Learning material added");
      return;
    }
    if (path.length == 1 && "messages".equals(path[0])) {
      long courseId = number(body, "courseId", 1, Long.MAX_VALUE);
      String content = str(body, "body", true);
      if (content.length() > 2000) throw new ApiError(400, "Message cannot exceed 2000 characters");
      requireCourse(instructor.id, courseId);
      Db.insert("INSERT INTO course_messages(course_id,instructor_id,body) VALUES(?,?,?)",
          courseId, instructor.id, content);
      Activity.log(instructor.id, "Student message sent", content.substring(0, Math.min(content.length(), 120)));
      message(response, 201, "Message sent to enrolled students");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  @Override
  protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User instructor = me(request);
    String[] path = segs(request);
    if (path.length == 2 && "courses".equals(path[0])) {
      long courseId = id(path[1]);
      requireCourse(instructor.id, courseId);
      CourseFields fields = fields(body(request));
      String status = Settings.bool("require_course_approval", true) ? "PENDING" : "APPROVED";
      Db.update("UPDATE courses SET title=?,description=?,syllabus=?,category=?,status=? WHERE id=? AND instructor_id=?",
          fields.title, fields.description, fields.syllabus, fields.category, status, courseId, instructor.id);
      Activity.log(instructor.id, "Course updated", fields.title + " was updated");
      message(response, 200, status.equals("PENDING") ? "Course updated and submitted for approval" : "Course updated");
      return;
    }
    if (path.length == 2 && "lessons".equals(path[0])) {
      long lessonId = id(path[1]);
      Map<String, Object> lesson = Db.one(
          "SELECT l.course_id FROM lessons l JOIN courses c ON c.id=l.course_id " +
          "WHERE l.id=? AND c.instructor_id=?", lessonId, instructor.id);
      if (lesson == null) throw new ApiError(404, "Learning material not found");
      LessonFields fields = lessonFields(body(request));
      Db.update("UPDATE lessons SET title=?,description=?,resource_url=? WHERE id=?",
          fields.title, fields.description, fields.resourceUrl, lessonId);
      message(response, 200, "Learning material updated");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    User instructor = me(request);
    String[] path = segs(request);
    if (path.length == 2 && "courses".equals(path[0])) {
      long courseId = id(path[1]);
      Map<String, Object> course = Db.one("SELECT title FROM courses WHERE id=? AND instructor_id=?",
          courseId, instructor.id);
      if (course == null) throw new ApiError(404, "Course not found");
      Db.update("DELETE FROM courses WHERE id=? AND instructor_id=?", courseId, instructor.id);
      Activity.log(instructor.id, "Course deleted", course.get("title") + " was removed");
      message(response, 200, "Course deleted");
      return;
    }
    if (path.length == 2 && "lessons".equals(path[0])) {
      long lessonId = id(path[1]);
      int removed = Db.update(
          "DELETE l FROM lessons l JOIN courses c ON c.id=l.course_id WHERE l.id=? AND c.instructor_id=?",
          lessonId, instructor.id);
      if (removed == 0) throw new ApiError(404, "Learning material not found");
      message(response, 200, "Learning material removed");
      return;
    }
    throw new ApiError(404, "Not found");
  }

  private void requireCourse(long instructorId, long courseId) {
    if (Db.count("SELECT COUNT(*) FROM courses WHERE id=? AND instructor_id=?", courseId, instructorId) == 0) {
      throw new ApiError(404, "Course not found");
    }
  }

  private CourseFields fields(JsonObject body) {
    String title = str(body, "title", true);
    String description = str(body, "description", true);
    String syllabus = str(body, "syllabus", true);
    String category = str(body, "category", false);
    if (title.length() > 150) throw new ApiError(400, "Course title is too long");
    return new CourseFields(title, description, syllabus, category.isEmpty() ? "General" : category);
  }

  private LessonFields lessonFields(JsonObject body) {
    String title = str(body, "title", true);
    String description = str(body, "description", false);
    String resourceUrl = str(body, "resourceUrl", false);
    if (title.length() > 150 || resourceUrl.length() > 1000) throw new ApiError(400, "Learning material details are too long");
    if (!resourceUrl.isEmpty() && !resourceUrl.matches("(?i)^(https?://|/).+")) {
      throw new ApiError(400, "Resource link must begin with http://, https://, or /");
    }
    return new LessonFields(title, description, resourceUrl);
  }

  private static final class CourseFields {
    final String title, description, syllabus, category;

    CourseFields(String title, String description, String syllabus, String category) {
      this.title = title;
      this.description = description;
      this.syllabus = syllabus;
      this.category = category;
    }
  }

  private static final class LessonFields {
    final String title, description, resourceUrl;

    LessonFields(String title, String description, String resourceUrl) {
      this.title = title;
      this.description = description;
      this.resourceUrl = resourceUrl;
    }
  }
}
