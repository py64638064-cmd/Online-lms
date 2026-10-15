# Coursecraft — Online Learning Management System

Coursecraft is a standalone Java Servlet application for administrators, instructors, and students. It follows the same Java 11, Servlet 4, Maven, MySQL, and plain HTML/CSS/JavaScript stack as the Event_mgr app, with an independent schema and database.

## Features

| Role | Features |
| --- | --- |
| Admin | Manage users and roles, approve or reject courses, configure platform settings, and review usage analytics |
| Instructor | Create and edit courses, add learning materials, review course enrollment and student progress, and message enrolled students |
| Student | Browse and enroll in approved courses, open course materials, mark lessons complete, review progress, and update a profile |

## Setup

Requires JDK 11+ and MySQL 8. From this folder, use the copied `jm` launcher and tools:

```bat
jm init
jm migrate coursecraft_db
jm run lms.war
```

On Windows, use `jm.cmd` if `jm` is not on PATH. `jm init` downloads Maven and Tomcat 9 into the ignored `.jm` folder when needed. The migration creates the database, applies `database/schema.sql`, and writes local credentials to `src/main/resources/db.properties`. The application expects `db.url`, `db.user`, and `db.password` there; environment variables `DB_URL`, `DB_USER`, and `DB_PASSWORD` can override those values at runtime. Keep the local properties file private.

To set up manually, create the database, execute `database/schema.sql` in it, configure `src/main/resources/db.properties`, then build and deploy `target/lms.war` to Tomcat 9.

## First sign-in

- Email: `admin@coursecraft.local`
- Password: `Admin@123`

The schema seeds this administrator with a one-time bootstrap credential. On first successful login, it is replaced by a BCrypt password hash. Change the bootstrap password after signing in and create instructor/student accounts.

## API overview

- Authentication: `GET /api/auth/me|settings`, `POST /api/auth/login|register|logout`
- Admin: `/api/admin/users`, `/api/admin/courses`, `/api/admin/settings`, `/api/admin/stats`, `/api/admin/activity`
- Instructor: `/api/instructor/courses`, `/api/instructor/students`, `/api/instructor/messages`
- Student: `/api/student/courses`, `/api/student/enrollments`, `/api/student/messages`, `/api/student/profile`

All role-specific endpoints require an active signed-in user with the matching role. Passwords are BCrypt-hashed and student lesson progress is saved per enrollment.
