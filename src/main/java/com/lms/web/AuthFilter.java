package com.lms.web;

import com.lms.util.ApiServlet;
import com.lms.util.Db;
import com.lms.util.User;
import java.io.IOException;
import java.util.Map;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@WebFilter("/api/*")
public class AuthFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    httpResponse.setHeader("Cache-Control", "no-store");
    String path = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
    if (!"GET".equals(httpRequest.getMethod()) && httpRequest.getHeader("X-Requested-With") == null) {
      ApiServlet.error(httpResponse, 403, "Missing X-Requested-With header");
      return;
    }
    if (path.startsWith("/api/auth")) {
      chain.doFilter(request, response);
      return;
    }
    HttpSession session = httpRequest.getSession(false);
    User user = session == null ? null : (User) session.getAttribute("user");
    if (user == null) {
      ApiServlet.error(httpResponse, 401, "Please sign in");
      return;
    }
    Map<String, Object> row = Db.one("SELECT status FROM users WHERE id=?", user.id);
    if (row == null || !"ACTIVE".equals(row.get("status"))) {
      session.invalidate();
      ApiServlet.error(httpResponse, 401, "Account is inactive");
      return;
    }
    String role = path.startsWith("/api/admin") ? "ADMIN"
        : path.startsWith("/api/instructor") ? "INSTRUCTOR"
        : path.startsWith("/api/student") ? "STUDENT" : null;
    if (role == null) {
      ApiServlet.error(httpResponse, 404, "Not found");
      return;
    }
    if (!role.equals(user.role)) {
      ApiServlet.error(httpResponse, 403, "You do not have access to this area");
      return;
    }
    chain.doFilter(request, response);
  }
}
