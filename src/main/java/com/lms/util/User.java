package com.lms.util;

public class User {
  public long id;
  public String name;
  public String email;
  public String role;

  public User(long id, String name, String email, String role) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.role = role;
  }
}
