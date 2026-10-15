package com.lms.web;

import com.lms.util.Db;
import org.mindrot.jbcrypt.BCrypt;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class AppListener implements ServletContextListener {
  @Override
  public void contextInitialized(ServletContextEvent event) {
    try {
      if (Db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN'") == 0) {
        Db.insert("INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,'ADMIN')",
            "Administrator", "admin@coursecraft.local", BCrypt.hashpw("Admin@123", BCrypt.gensalt()));
      }
    } catch (Exception e) {
      event.getServletContext().log("Could not seed admin. Create the database with database/schema.sql and configure db.properties.", e);
    }
  }
}
