const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[char]));
const numberFormat = value => new Intl.NumberFormat().format(Number(value || 0));
const isTrue = value => value === true || value === 1 || value === '1' || value === 'true';
const dateObject = value => new Date(String(value).slice(0, 10) + 'T00:00:00');
const dateFormat = value => value ? dateObject(value).toLocaleDateString(undefined, {
  month: 'short', day: 'numeric', year: 'numeric'
}) : '—';
const statusTag = value => {
  const label = String(value || '').toLowerCase().replace(/_/g, ' ');
  return `<span class="status ${esc(label)}">${esc(label.replace(/^./, char => char.toUpperCase()))}</span>`;
};
let currentUser;
let currentTab = '';
let activeStudentCourse;

async function api(path, options = {}) {
  const headers = { 'X-Requested-With': 'fetch' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`api${path}`, {
    method: options.method || 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: 'same-origin'
  });
  let data = {};
  try { data = await response.json(); } catch (_) {}
  if (!response.ok) {
    if (response.status === 401 && currentUser) {
      currentUser = null;
      $('#appView').hidden = true;
      $('#authView').hidden = false;
    }
    throw new Error(data.error || `Request failed (${response.status})`);
  }
  return data;
}

function toast(message, isError = false) {
  const element = document.createElement('div');
  element.className = `toast${isError ? ' error' : ''}`;
  element.textContent = message;
  $('#toasts').append(element);
  setTimeout(() => element.remove(), 4200);
}

async function action(callback) {
  try {
    const result = await callback();
    if (result?.message) toast(result.message);
    return result || true;
  } catch (error) {
    toast(error.message, true);
    return false;
  }
}

const dashboards = {
  ADMIN: {
    label: 'Admin workspace',
    tabs: [['overview', '◫', 'Overview'], ['courses', '✓', 'Course approvals'],
      ['users', '♙', 'User management'], ['activity', '◷', 'System activity'],
      ['settings', '⚙', 'System settings']]
  },
  INSTRUCTOR: {
    label: 'Instructor workspace',
    tabs: [['overview', '◫', 'Overview'], ['courses', '▤', 'My courses'],
      ['students', '♙', 'Student progress'], ['messages', '↗', 'Class messages']]
  },
  STUDENT: {
    label: 'Student workspace',
    tabs: [['catalog', '⌕', 'Course catalog'], ['learning', '▤', 'My learning'],
      ['messages', '↗', 'Course messages'], ['profile', '◎', 'My profile']]
  }
};

function authMode(mode) {
  const isLogin = mode === 'login';
  $$('.auth-tabs button').forEach(button => button.classList.toggle('active', button.dataset.auth === mode));
  $('#loginForm').hidden = !isLogin;
  $('#registerForm').hidden = isLogin;
  $('#authTitle').textContent = isLogin ? 'Welcome back' : 'Start your learning journey';
  $('#authIntro').textContent = isLogin
    ? 'Sign in to pick up where you left off.'
    : 'Create an account and find your next favorite course.';
  $('#authEyebrow').textContent = isLogin ? 'YOUR NEXT CHAPTER STARTS HERE' : 'IT STARTS WITH CURIOSITY';
}

$$('.auth-tabs button').forEach(button => button.addEventListener('click', () => authMode(button.dataset.auth)));

$('#loginForm').addEventListener('submit', async event => {
  event.preventDefault();
  $('#loginError').textContent = '';
  try {
    const user = await api('/auth/login', {
      method: 'POST',
      body: { email: $('#loginEmail').value, password: $('#loginPassword').value }
    });
    await enter(user);
  } catch (error) {
    $('#loginError').textContent = error.message;
  }
});

$('#registerForm').addEventListener('submit', async event => {
  event.preventDefault();
  $('#registerError').textContent = '';
  try {
    const result = await api('/auth/register', {
      method: 'POST',
      body: {
        name: $('#registerName').value,
        email: $('#registerEmail').value,
        password: $('#registerPassword').value,
        role: $('#registerRole').value
      }
    });
    toast(result.message);
    authMode('login');
    $('#loginEmail').value = $('#registerEmail').value;
    $('#registerForm').reset();
  } catch (error) {
    $('#registerError').textContent = error.message;
  }
});

async function enter(user) {
  currentUser = user;
  const dashboard = dashboards[user.role] || dashboards.STUDENT;
  const roleName = user.role[0] + user.role.slice(1).toLowerCase();
  $('#authView').hidden = true;
  $('#appView').hidden = false;
  $('#profileName').textContent = user.name;
  $('#profileRole').textContent = roleName;
  $('#topProfileName').textContent = user.name;
  $('#dropdownName').textContent = user.name;
  $('#dropdownRole').textContent = roleName;
  $('#avatar').textContent = user.name.trim().charAt(0) || 'C';
  $('#topAvatar').textContent = user.name.trim().charAt(0) || 'C';
  $('#dropdownAvatar').textContent = user.name.trim().charAt(0) || 'C';
  $('#roleLabel').textContent = dashboard.label;
  $('#todayLabel').textContent = new Date().toLocaleDateString(undefined, {
    weekday: 'short', month: 'short', day: 'numeric'
  });
  $('#nav').innerHTML = dashboard.tabs.map(([id, icon, label]) =>
    `<button data-tab="${id}"><span class="nav-icon">${icon}</span>${label}</button>`).join('');
  $$('#nav button').forEach(button => button.addEventListener('click', () => showTab(button.dataset.tab)));
  await showTab(dashboard.tabs[0][0]);
}

async function showTab(tab) {
  currentTab = tab;
  $$('#nav button').forEach(button => button.classList.toggle('active', button.dataset.tab === tab));
  $('#crumb').textContent = $(`#nav button[data-tab="${tab}"]`)?.textContent.trim() || 'Workspace';
  $('#content').innerHTML = '<div class="panel muted">Loading your workspace…</div>';
  try {
    if (currentUser.role === 'ADMIN') await renderAdmin(tab);
    else if (currentUser.role === 'INSTRUCTOR') await renderInstructor(tab);
    else await renderStudent(tab);
  } catch (error) {
    $('#content').innerHTML = `<div class="panel">${esc(error.message)}</div>`;
  }
}

function pageHead(title, description, button = '') {
  return `<div class="page-head"><div><span class="eyebrow">${currentUser.role === 'STUDENT' ? 'YOUR LEARNING DESK' : 'COURSECRAFT WORKSPACE'}</span><h1>${title}</h1><p>${description}</p></div>${button}</div>`;
}

function metric(value, label, icon, theme = '') {
  return `<div class="metric ${theme}"><div class="metric-top"><span>${label}</span><span class="metric-icon">${icon}</span></div><strong>${value}</strong><small></small></div>`;
}

function empty(message) {
  return `<div class="empty">${esc(message)}</div>`;
}

function progressBar(value, completed, total) {
  const progress = Math.max(0, Math.min(100, Number(value || 0)));
  return `<div class="progress-label"><span>Course progress</span><b>${progress}%</b></div><div class="progress-track" aria-label="${progress}% complete"><span style="width:${progress}%"></span></div><small class="muted">${numberFormat(completed)} of ${numberFormat(total)} lessons completed</small>`;
}

function courseCard(course, index, actionMarkup = '') {
  return `<article class="event-card course-card">
    <div class="event-cover ${['', 'blue', 'green', 'yellow'][index % 4]}"><span class="cover-mark">✦</span><span class="cover-category">${esc(course.category || 'General')}</span></div>
    <div class="event-card-body"><span class="eyebrow">${esc(course.instructor_name || 'Coursecraft instructor')} · ${numberFormat(course.lesson_count)} lessons</span>
      <h3>${esc(course.title)}</h3><p>${esc(course.description || '')}</p>
      <div class="event-meta"><span class="pill">${esc(course.status || 'APPROVED')}</span>${actionMarkup}</div>
    </div>
  </article>`;
}

function bindGoTo() {
  $$('[data-goto]').forEach(button => button.addEventListener('click', () => showTab(button.dataset.goto)));
}

async function renderAdmin(tab) {
  if (tab === 'overview') {
    const [stats, courses] = await Promise.all([api('/admin/stats'), api('/admin/courses')]);
    const activeCourses = courses.filter(course => course.status === 'APPROVED')
      .sort((a, b) => Number(b.enrollments) - Number(a.enrollments)).slice(0, 5);
    const largest = Math.max(1, ...activeCourses.map(course => Number(course.enrollments || 0)));
    const recent = stats.recent || [];
    $('#content').innerHTML = pageHead(`Curiosity is growing, ${esc(currentUser.name.split(' ')[0])}.`,
      'Here’s what’s happening across your learning community.') +
      `<div class="stats-grid">${metric(numberFormat(stats.courses), 'Total courses', '▤')}${metric(numberFormat(stats.pending), 'Awaiting review', '◷', 'yellow')}${metric(numberFormat(stats.students), 'Active students', '♙', 'blue')}${metric(numberFormat(stats.enrollments), 'Course enrollments', '↗', 'green')}</div>
      <div class="dashboard-grid">
        <section class="panel"><div class="panel-head"><div><h2>Recent submissions</h2><p>Courses shared by your instructors</p></div><button class="text-link" data-goto="courses">Review courses ↗</button></div>
          ${recent.length ? `<div class="event-list">${recent.map(course => `<div class="event-row"><div class="lesson-number">✦</div><div><h3>${esc(course.title)}</h3><p>By ${esc(course.instructor_name)} · ${numberFormat(course.enrollments)} enrolled</p></div><div class="event-actions">${statusTag(course.status)}</div></div>`).join('')}</div>` : empty('New course submissions will appear here.')}</section>
        <section class="panel"><div class="panel-head"><div><h2>Learning community</h2><p>Platform at a glance</p></div><span class="metric-icon">✦</span></div>
          <div class="mini-stat-list"><div class="mini-stat"><span>Registered accounts</span><strong>${numberFormat(stats.users)}</strong></div><div class="mini-stat"><span>Courses in review</span><strong>${numberFormat(stats.pending)}</strong></div><div class="mini-stat"><span>Active students</span><strong>${numberFormat(stats.students)}</strong></div><div class="mini-stat"><span>Total enrollments</span><strong>${numberFormat(stats.enrollments)}</strong></div></div>
          <div class="notice" style="margin:18px 0 0">Every great learning journey starts with one lesson.</div>
          <div style="margin-top:20px"><h3>Enrollments by course</h3><div class="bar-chart">${activeCourses.length ? activeCourses.map(course => `<div class="bar-col" title="${esc(course.title)}: ${numberFormat(course.enrollments)} enrollments"><i class="bar" style="height:${Math.max(8, Math.round(Number(course.enrollments || 0) / largest * 100))}%"></i><span>${esc(course.title.slice(0, 8))}</span></div>`).join('') : empty('Approved courses will appear in this chart.')}</div></div>
        </section>
      </div>`;
    bindGoTo();
    return;
  }
  if (tab === 'courses') {
    const courses = await api('/admin/courses');
    const pending = courses.filter(course => course.status === 'PENDING').length;
    $('#content').innerHTML = pageHead('Course review', 'Review instructor submissions and decide what is ready to publish.') +
      `<div class="stats-grid">${metric(numberFormat(pending), 'Needs your review', '◷', 'yellow')}${metric(numberFormat(courses.filter(course => course.status === 'APPROVED').length), 'Published courses', '✓', 'green')}${metric(numberFormat(courses.length), 'All courses', '▤', 'blue')}${metric(numberFormat(courses.reduce((sum, course) => sum + Number(course.enrollments || 0), 0)), 'Enrollments', '♙')}</div>
      <section class="panel"><div class="panel-head"><div><h2>Submitted courses</h2><p>Approve a course to make it available in the student catalog.</p></div></div>
      <div class="table-wrap"><table><thead><tr><th>Course</th><th>Instructor</th><th>Lessons</th><th>Students</th><th>Status</th><th></th></tr></thead><tbody>
        ${courses.length ? courses.map(course => `<tr><td><span class="td-title">${esc(course.title)}</span><span class="td-sub">${esc(course.description.slice(0, 150))}</span><span class="td-sub">Syllabus: ${esc(course.syllabus.slice(0, 110))}</span></td><td>${esc(course.instructor_name)}</td><td>${numberFormat(course.lesson_count)}</td><td>${numberFormat(course.enrollments)}</td><td>${statusTag(course.status)}</td><td><div class="row-btns">${course.status === 'PENDING' ? `<button class="btn small primary" data-course-status="APPROVED" data-id="${course.id}">Approve</button><button class="btn small danger" data-course-status="REJECTED" data-id="${course.id}">Reject</button>` : ''}</div></td></tr>`).join('') : `<tr><td colspan="6">${empty('No course submissions yet.')}</td></tr>`}
      </tbody></table></div></section>`;
    $$('[data-course-status]').forEach(button => button.addEventListener('click', async () => {
      if (await action(() => api(`/admin/courses/${button.dataset.id}`, {
        method: 'POST', body: { status: button.dataset.courseStatus }
      }))) renderAdmin('courses');
    }));
    return;
  }
  if (tab === 'users') {
    const users = await api('/admin/users');
    $('#content').innerHTML = pageHead('People make the platform.', 'Manage instructor, student, and administrator access.',
      '<button class="btn primary" id="addUser">＋ Add a user</button>') +
      `<section class="panel"><div class="panel-head"><div><h2>All accounts <span class="muted">(${users.length})</span></h2><p>Update roles or suspend accounts when needed.</p></div></div>
      <div class="table-wrap"><table><thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Joined</th><th>Status</th><th></th></tr></thead><tbody>
        ${users.map(user => `<tr><td class="td-title">${esc(user.name)}</td><td>${esc(user.email)}</td><td>${esc(user.role[0] + user.role.slice(1).toLowerCase())}</td><td>${dateFormat(user.created_at)}</td><td>${statusTag(user.status)}</td><td><div class="row-btns">${user.role === 'ADMIN' ? '' : `<button class="btn small" data-user-edit="${user.id}">Edit</button><button class="btn small" data-user-toggle="${user.id}" data-status="${user.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'}">${user.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}</button><button class="btn small danger" data-user-delete="${user.id}">Delete</button>`}</div></td></tr>`).join('')}
      </tbody></table></div></section>`;
    $('#addUser').addEventListener('click', () => $('#userDialog').showModal());
    $$('[data-user-edit]').forEach(button => button.addEventListener('click', async () => {
      const user = users.find(row => row.id == button.dataset.userEdit);
      const name = prompt('Full name', user.name);
      if (name === null) return;
      const email = prompt('Email address', user.email);
      if (email === null) return;
      const role = prompt('Role: ADMIN, INSTRUCTOR, or STUDENT', user.role);
      if (role === null) return;
      if (await action(() => api(`/admin/users/${user.id}`, {
        method: 'PUT', body: { name, email, role: role.toUpperCase(), status: user.status }
      }))) renderAdmin('users');
    }));
    $$('[data-user-toggle]').forEach(button => button.addEventListener('click', async () => {
      const user = users.find(row => row.id == button.dataset.userToggle);
      if (await action(() => api(`/admin/users/${user.id}`, {
        method: 'PUT', body: { name: user.name, email: user.email, role: user.role, status: button.dataset.status }
      }))) renderAdmin('users');
    }));
    $$('[data-user-delete]').forEach(button => button.addEventListener('click', async () => {
      if (confirm('Delete this account and its learning records?')
        && await action(() => api(`/admin/users/${button.dataset.userDelete}`, { method: 'DELETE' }))) {
        renderAdmin('users');
      }
    }));
    return;
  }
  if (tab === 'activity') {
    const rows = await api('/admin/activity');
    $('#content').innerHTML = pageHead('A record of what happened', 'Recent account, course, and platform activity.') +
      `<section class="panel"><div class="panel-head"><div><h2>System activity</h2><p>Latest changes across Coursecraft</p></div><button class="btn small" id="refreshActivity">Refresh ↻</button></div>
      <div class="updates-list">${rows.length ? rows.map(row => `<article class="update-item"><div style="display:flex;justify-content:space-between;gap:12px"><b>${esc(row.action)}</b><small>${dateFormat(row.created_at)}</small></div><p>${esc(row.detail)}</p><small>By ${esc(row.actor_name || 'A new member')}</small></article>`).join('') : empty('System changes will appear here as people use Coursecraft.')}</div></section>`;
    $('#refreshActivity').addEventListener('click', () => renderAdmin('activity'));
    return;
  }
  if (tab === 'settings') {
    const settings = await api('/admin/settings');
    $('#content').innerHTML = pageHead('Set the learning environment', 'A few thoughtful defaults help Coursecraft run smoothly.') +
      `<form class="panel settings-form" id="settingsForm"><div class="panel-head"><div><h2>Platform settings</h2><p>These options apply across the whole platform.</p></div></div>
      <div class="field-grid"><label class="span-2">Platform name<input id="settingName" value="${esc(settings.siteName)}" required></label><label class="span-2">Support email<input id="settingEmail" type="email" value="${esc(settings.contactEmail)}" required></label></div>
      <label class="check-row"><input id="settingRegistration" type="checkbox" ${settings.allowRegistration ? 'checked' : ''}><span><b>Allow self-serve registration</b><small>People can create instructor and student accounts.</small></span></label>
      <label class="check-row"><input id="settingApproval" type="checkbox" ${settings.requireApproval ? 'checked' : ''}><span><b>Review courses before publishing</b><small>New and edited instructor courses need admin approval.</small></span></label>
      <div class="dialog-actions"><button class="btn primary" type="submit">Save settings ↗</button></div></form>`;
    $('#settingsForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (await action(() => api('/admin/settings', {
        method: 'PUT',
        body: {
          siteName: $('#settingName').value,
          contactEmail: $('#settingEmail').value,
          allowRegistration: $('#settingRegistration').checked,
          requireApproval: $('#settingApproval').checked
        }
      }))) {
        $$('.siteName').forEach(node => { node.textContent = $('#settingName').value; });
        $('#supportLink').href = `mailto:${encodeURIComponent($('#settingEmail').value)}`;
      }
    });
  }
}

async function renderInstructor(tab) {
  if (tab === 'overview') {
    const courses = await api('/instructor/courses');
    const students = await api('/instructor/students');
    const approved = courses.filter(course => course.status === 'APPROVED');
    const enrollments = courses.reduce((sum, course) => sum + Number(course.enrollments || 0), 0);
    const average = students.length
      ? Math.round(students.reduce((sum, student) => sum + Number(student.progress || 0), 0) / students.length) : 0;
    $('#content').innerHTML = pageHead(`Keep the momentum going, ${esc(currentUser.name.split(' ')[0])}.`,
      'Your teaching space, student progress, and course activity.') +
      `<div class="stats-grid">${metric(numberFormat(courses.length), 'Your courses', '▤')}${metric(numberFormat(enrollments), 'Student enrollments', '♙', 'blue')}${metric(`${average}%`, 'Average progress', '↗', 'green')}${metric(numberFormat(courses.filter(course => course.status === 'PENDING').length), 'In review', '◷', 'yellow')}</div>
      <section class="panel"><div class="panel-head"><div><h2>Courses you teach</h2><p>Keep course material current and follow your class progress.</p></div><button class="btn primary" id="createCourse">＋ Create a course</button></div>
      ${courses.length ? `<div class="event-list">${courses.slice(0, 6).map(course => `<div class="event-row"><div class="lesson-number">✦</div><div><h3>${esc(course.title)}</h3><p>${numberFormat(course.enrollments)} students · ${numberFormat(course.lesson_count)} lessons</p></div><div class="event-actions">${statusTag(course.status)}</div></div>`).join('')}</div>` : empty('Create your first course to get started.')}</section>`;
    $('#createCourse').addEventListener('click', () => openCourseDialog());
    return;
  }
  if (tab === 'courses') {
    const courses = await api('/instructor/courses');
    $('#content').innerHTML = pageHead('Your courses', 'Build your course, add learning materials, and manage student access.',
      '<button class="btn primary" id="createCourse">＋ Create a course</button>') +
      (courses.length ? `<div class="event-card-grid">${courses.map((course, index) =>
        courseCard(course, index, `<button class="btn small" data-course-lessons="${course.id}">Manage lessons</button><button class="btn small" data-course-edit="${course.id}">Edit</button><button class="btn small danger" data-course-delete="${course.id}">Delete</button>`)).join('')}</div>
      <section id="lessonPanel" class="panel lesson-panel" hidden></section>` : `<section class="panel">${empty('No courses yet. Create a course to share what you know.')}</section>`);
    $('#createCourse').addEventListener('click', () => openCourseDialog());
    $$('[data-course-edit]').forEach(button => button.addEventListener('click', () => {
      const course = courses.find(row => row.id == button.dataset.courseEdit);
      openCourseDialog(course);
    }));
    $$('[data-course-lessons]').forEach(button => button.addEventListener('click', () => {
      showInstructorLessons(button.dataset.courseLessons, courses.find(row => row.id == button.dataset.courseLessons));
    }));
    $$('[data-course-delete]').forEach(button => button.addEventListener('click', async () => {
      if (confirm('Delete this course and its enrollment records?')
        && await action(() => api(`/instructor/courses/${button.dataset.courseDelete}`, { method: 'DELETE' }))) {
        renderInstructor('courses');
      }
    }));
    return;
  }
  if (tab === 'students') {
    const students = await api('/instructor/students');
    $('#content').innerHTML = pageHead('Student progress', 'Follow enrollment, lesson completion, and progress across your classes.') +
      `<section class="panel"><div class="panel-head"><div><h2>Your students <span class="muted">(${students.length})</span></h2><p>Progress is calculated from completed lessons in each course.</p></div></div>
      <div class="table-wrap"><table><thead><tr><th>Student</th><th>Course</th><th>Lessons</th><th>Progress</th><th>Enrolled</th></tr></thead><tbody>
      ${students.length ? students.map(student => `<tr><td><span class="td-title">${esc(student.student_name)}</span><span class="td-sub">${esc(student.student_email)}</span></td><td>${esc(student.course_title)}</td><td>${numberFormat(student.completed_count)} / ${numberFormat(student.lesson_count)}</td><td><div class="course-progress"><div class="progress-track"><span style="width:${Number(student.progress)}%"></span></div><small>${Number(student.progress)}%</small></div></td><td>${dateFormat(student.enrolled_at)}</td></tr>`).join('') : `<tr><td colspan="5">${empty('Student enrollments will appear here.')}</td></tr>`}
      </tbody></table></div></section>`;
    return;
  }
  if (tab === 'messages') {
    const [courses, messages] = await Promise.all([api('/instructor/courses'), api('/instructor/messages')]);
    $('#content').innerHTML = pageHead('Class messages', 'Share an update or helpful note with enrolled students.') +
      `<div class="dashboard-grid"><form class="panel message-compose" id="messageForm"><div><h2>Send a course message</h2><p class="muted">Every enrolled student in the selected course can read this message.</p></div>
      <label class="form-label">Course<select id="messageCourse" required>${courses.map(course => `<option value="${course.id}">${esc(course.title)}</option>`).join('')}</select></label>
      <label class="form-label">Message<textarea id="messageBody" maxlength="2000" required placeholder="Share a class update, encouragement, or useful reminder..."></textarea></label>
      <div><button class="btn primary" type="submit" ${courses.length ? '' : 'disabled'}>Send to students ↗</button></div></form>
      <section class="panel"><div class="panel-head"><div><h2>Recently sent</h2><p>Your latest course announcements</p></div></div>
      <div class="updates-list">${messages.length ? messages.map(message => `<article class="update-item"><div style="display:flex;justify-content:space-between;gap:12px"><b>${esc(message.course_title)}</b><small>${dateFormat(message.created_at)}</small></div><p>${esc(message.body)}</p></article>`).join('') : empty('Messages you send will appear here.')}</div></section></div>`;
    $('#messageForm').addEventListener('submit', async event => {
      event.preventDefault();
      const result = await action(() => api('/instructor/messages', {
        method: 'POST',
        body: { courseId: Number($('#messageCourse').value), body: $('#messageBody').value }
      }));
      if (result) renderInstructor('messages');
    });
  }
}

function openCourseDialog(course) {
  $('#courseForm').reset();
  $('#courseId').value = course?.id || '';
  $('#courseDialogTitle').textContent = course ? 'Edit course' : 'Create a course';
  $('#courseTitle').value = course?.title || '';
  $('#courseDescription').value = course?.description || '';
  $('#courseSyllabus').value = course?.syllabus || '';
  $('#courseCategory').value = course?.category || 'General';
  $('#courseDialog').showModal();
}

$('#courseForm').addEventListener('submit', async event => {
  event.preventDefault();
  const courseId = $('#courseId').value;
  const result = await action(() => api(courseId ? `/instructor/courses/${courseId}` : '/instructor/courses', {
    method: courseId ? 'PUT' : 'POST',
    body: {
      title: $('#courseTitle').value,
      description: $('#courseDescription').value,
      syllabus: $('#courseSyllabus').value,
      category: $('#courseCategory').value
    }
  }));
  if (result) {
    $('#courseDialog').close();
    showTab(currentTab);
  }
});

async function showInstructorLessons(courseId, course) {
  const panel = $('#lessonPanel');
  panel.hidden = false;
  panel.innerHTML = '<div class="muted">Loading lessons…</div>';
  try {
    const lessons = await api(`/instructor/courses/${courseId}/lessons`);
    panel.innerHTML = `<div class="panel-head"><div><span class="eyebrow">COURSE MATERIALS</span><h2>${esc(course.title)}</h2><p>${numberFormat(lessons.length)} lessons · ${esc(course.syllabus)}</p></div><button class="btn primary" id="addLesson">＋ Add lesson</button></div>
      ${lessons.length ? `<div class="lesson-list">${lessons.map((lesson, index) => `<article class="lesson-row"><div class="lesson-number">${index + 1}</div><div class="lesson-copy"><b>${esc(lesson.title)}</b><p>${esc(lesson.description || '')}</p>${lesson.resource_url ? `<a href="${esc(lesson.resource_url)}" target="_blank" rel="noopener noreferrer">Open learning material ↗</a>` : ''}</div><div class="lesson-actions"><button class="btn small" data-lesson-edit="${lesson.id}">Edit</button><button class="btn small danger" data-lesson-delete="${lesson.id}">Remove</button></div></article>`).join('')}</div>` : empty('Add a lesson or resource for your students.')}`;
    $('#addLesson').addEventListener('click', () => openLessonDialog(courseId));
    $$('[data-lesson-edit]').forEach(button => button.addEventListener('click', () => {
      const lesson = lessons.find(row => row.id == button.dataset.lessonEdit);
      openLessonDialog(courseId, lesson);
    }));
    $$('[data-lesson-delete]').forEach(button => button.addEventListener('click', async () => {
      if (confirm('Remove this lesson and its completion records?')
        && await action(() => api(`/instructor/lessons/${button.dataset.lessonDelete}`, { method: 'DELETE' }))) {
        showInstructorLessons(courseId, course);
      }
    }));
  } catch (error) {
    panel.innerHTML = `<p>${esc(error.message)}</p>`;
  }
}

function openLessonDialog(courseId, lesson) {
  $('#lessonForm').reset();
  $('#lessonCourseId').value = courseId;
  $('#lessonId').value = lesson?.id || '';
  $('#lessonDialogTitle').textContent = lesson ? 'Edit learning material' : 'Add a lesson';
  $('#lessonTitle').value = lesson?.title || '';
  $('#lessonDescription').value = lesson?.description || '';
  $('#lessonUrl').value = lesson?.resource_url || '';
  $('#lessonDialog').showModal();
}

$('#lessonForm').addEventListener('submit', async event => {
  event.preventDefault();
  const lessonId = $('#lessonId').value;
  const courseId = $('#lessonCourseId').value;
  const result = await action(() => api(lessonId ? `/instructor/lessons/${lessonId}` : `/instructor/courses/${courseId}/lessons`, {
    method: lessonId ? 'PUT' : 'POST',
    body: {
      title: $('#lessonTitle').value,
      description: $('#lessonDescription').value,
      resourceUrl: $('#lessonUrl').value
    }
  }));
  if (result) {
    $('#lessonDialog').close();
    const courses = await api('/instructor/courses');
    const course = courses.find(row => row.id == courseId);
    if (course) showInstructorLessons(courseId, course);
  }
});

async function renderStudent(tab) {
  if (tab === 'catalog') {
    const courses = await api('/student/courses');
    $('#content').innerHTML = pageHead('Find your next course', 'Explore instructor-led courses and start learning at your own pace.') +
      (courses.length ? `<div class="event-card-grid">${courses.map((course, index) => courseCard(course, index,
        isTrue(course.enrolled) ? '<span class="pill">Enrolled</span>' : `<button class="btn small primary" data-enroll="${course.id}">Join course ↗</button>`)).join('')}</div>` :
        `<section class="panel">${empty('No courses are published yet. Check back soon.')}</section>`);
    $$('[data-enroll]').forEach(button => button.addEventListener('click', async () => {
      if (await action(() => api('/student/enrollments', {
        method: 'POST', body: { courseId: Number(button.dataset.enroll) }
      }))) renderStudent('catalog');
    }));
    return;
  }
  if (tab === 'learning') {
    const enrollments = await api('/student/enrollments');
    $('#content').innerHTML = pageHead('My learning', 'Pick up where you left off and celebrate every completed lesson.') +
      (enrollments.length ? `<div class="event-card-grid">${enrollments.map((course, index) =>
        `<article class="event-card course-card"><div class="event-cover ${['', 'blue', 'green', 'yellow'][index % 4]}"><span class="cover-mark">✦</span><span class="cover-category">${esc(course.category)}</span></div>
        <div class="event-card-body"><span class="eyebrow">WITH ${esc(course.instructor_name)}</span><h3>${esc(course.title)}</h3><p>${esc(course.description)}</p>
        ${progressBar(course.progress, course.completed_count, course.lesson_count)}<div class="event-meta" style="margin-top:16px"><span>${statusTag(course.status)}</span><button class="btn small primary" data-open-course="${course.course_id}">Open course ↗</button></div></div></article>`).join('')}</div>
        <section class="panel lesson-panel" id="studentCoursePanel" hidden></section>` : `<section class="panel">${empty('You have not enrolled in a course yet. Browse the course catalog to get started.')}</section>`);
    $$('[data-open-course]').forEach(button => button.addEventListener('click', () => {
      const course = enrollments.find(row => row.course_id == button.dataset.openCourse);
      showStudentCourse(course);
    }));
    return;
  }
  if (tab === 'messages') {
    const messages = await api('/student/messages');
    $('#content').innerHTML = pageHead('Messages from your instructors', 'Class updates, helpful reminders, and notes from your teachers.') +
      `<section class="panel"><div class="panel-head"><div><h2>Course messages</h2><p>Updates for courses you are enrolled in.</p></div></div>
      <div class="updates-list">${messages.length ? messages.map(message => `<article class="update-item"><div style="display:flex;justify-content:space-between;gap:12px"><b>${esc(message.course_title)}</b><small>${dateFormat(message.created_at)}</small></div><p>${esc(message.body)}</p><small>From ${esc(message.instructor_name)}</small></article>`).join('') : empty('Course announcements from instructors will appear here.')}</div></section>`;
    return;
  }
  if (tab === 'profile') {
    const profile = await api('/student/profile');
    $('#content').innerHTML = pageHead('Your profile', 'Keep your personal details and sign-in password up to date.') +
      `<form class="panel settings-form" id="profileForm"><div class="panel-head"><div><h2>Personal details</h2><p>Your profile is visible to instructors in courses you join.</p></div></div>
      <div class="field-grid"><label class="span-2">Full name<input id="profileEditName" maxlength="100" value="${esc(profile.name)}" required></label><label class="span-2">Email address<input id="profileEditEmail" type="email" value="${esc(profile.email)}" required></label><label class="span-2">New password <small>Leave blank to keep your current password</small><input id="profileEditPassword" type="password" minlength="6" autocomplete="new-password"></label></div>
      <div class="dialog-actions"><button class="btn primary" type="submit">Save profile ↗</button></div></form>`;
    $('#profileForm').addEventListener('submit', async event => {
      event.preventDefault();
      const result = await action(() => api('/student/profile', {
        method: 'PUT',
        body: {
          name: $('#profileEditName').value,
          email: $('#profileEditEmail').value,
          password: $('#profileEditPassword').value
        }
      }));
      if (result) {
        currentUser.name = $('#profileEditName').value;
        currentUser.email = $('#profileEditEmail').value;
        $('#profileEditPassword').value = '';
        $('#profileName').textContent = currentUser.name;
        $('#topProfileName').textContent = currentUser.name;
        $('#dropdownName').textContent = currentUser.name;
        $('#avatar').textContent = currentUser.name.trim().charAt(0) || 'C';
        $('#topAvatar').textContent = currentUser.name.trim().charAt(0) || 'C';
        $('#dropdownAvatar').textContent = currentUser.name.trim().charAt(0) || 'C';
      }
    });
  }
}

async function showStudentCourse(course) {
  const panel = $('#studentCoursePanel');
  if (!panel) return;
  activeStudentCourse = course;
  panel.hidden = false;
  panel.innerHTML = '<div class="muted">Loading course materials…</div>';
  try {
    const lessons = await api(`/student/courses/${course.course_id}/lessons`);
    const completed = lessons.filter(lesson => lesson.completed).length;
    const progress = lessons.length ? Math.round(completed * 100 / lessons.length) : 0;
    panel.innerHTML = `<div class="panel-head"><div><span class="eyebrow">YOUR COURSE</span><h2>${esc(course.title)}</h2><p>Instructor: ${esc(course.instructor_name)}</p></div></div>
      <div class="dashboard-grid"><div><h3>Course overview</h3><p class="syllabus">${esc(course.syllabus || 'Your instructor has not added a syllabus yet.')}</p>${progressBar(progress, completed, lessons.length)}</div>
      <div><h3>Learning materials</h3>${lessons.length ? `<div class="lesson-list">${lessons.map((lesson, index) => `<article class="lesson-row"><div class="lesson-number">${index + 1}</div><div class="lesson-copy"><b>${esc(lesson.title)}</b><p>${esc(lesson.description || '')}</p>${lesson.resource_url ? `<a href="${esc(lesson.resource_url)}" target="_blank" rel="noopener noreferrer">Open material ↗</a>` : '<small class="muted">No link attached</small>'}</div>
      <div class="lesson-actions"><button class="btn small ${isTrue(lesson.completed) ? '' : 'primary'}" data-lesson-complete="${lesson.id}" data-completed="${isTrue(lesson.completed)}">${isTrue(lesson.completed) ? 'Completed ✓' : 'Mark complete'}</button></div></article>`).join('')}</div>` : empty('Your instructor has not added lessons yet.')}</div></div>`;
    $$('[data-lesson-complete]').forEach(button => button.addEventListener('click', async () => {
      const result = await action(() => api('/student/progress', {
        method: 'POST',
        body: { lessonId: Number(button.dataset.lessonComplete), completed: button.dataset.completed !== 'true' }
      }));
      if (result) {
        const enrollments = await api('/student/enrollments');
        const updatedCourse = enrollments.find(row => row.course_id === course.course_id);
        await renderStudent('learning');
        if (updatedCourse) await showStudentCourse(updatedCourse);
      }
    }));
    panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) {
    panel.innerHTML = `<p>${esc(error.message)}</p>`;
  }
}

$('#userForm').addEventListener('submit', async event => {
  event.preventDefault();
  const result = await action(() => api('/admin/users', {
    method: 'POST',
    body: {
      name: $('#userName').value,
      email: $('#userEmail').value,
      role: $('#userRole').value,
      password: $('#userPassword').value
    }
  }));
  if (result) {
    $('#userDialog').close();
    $('#userForm').reset();
    renderAdmin('users');
  }
});

$$('dialog [data-close]').forEach(button => button.addEventListener('click', () => button.closest('dialog').close()));
$('#profileMenuToggle').addEventListener('click', () => {
  const dropdown = $('#profileDropdown');
  const open = dropdown.hidden;
  dropdown.hidden = !open;
  $('#profileMenuToggle').setAttribute('aria-expanded', String(open));
});
document.addEventListener('click', event => {
  if (!event.target.closest('.profile-menu')) {
    $('#profileDropdown').hidden = true;
    $('#profileMenuToggle').setAttribute('aria-expanded', 'false');
  }
});
$('#profileSignOut').addEventListener('click', async () => {
  const result = await action(() => api('/auth/logout', { method: 'POST' }));
  if (result) {
    currentUser = null;
    $('#appView').hidden = true;
    $('#authView').hidden = false;
    $('#profileDropdown').hidden = true;
    $('#profileMenuToggle').setAttribute('aria-expanded', 'false');
  }
});

(async function restoreSession() {
  try {
    await enter(await api('/auth/me'));
  } catch (error) {
    if (error.message !== 'Please sign in') toast(error.message, true);
  }
})();
