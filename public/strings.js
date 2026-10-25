const STRINGS = {
  ro: {
    // Navigation
    tab_home: "Acasă",
    tab_marks: "Reușita",
    tab_timetable: "Orar",
    tab_absences: "Absențe",
    tab_homework: "Teme",
    tab_profile: "Profil și profesori",

    // Actions & Shell
    sync_now: "Sincronizează acum",
    sync_available_in: "Sincronizare disponibilă peste {time}",
    sync_syncing: "Se sincronizează",
    sync_synced: "Sincronizat {time}",
    sync_unsynced: "Nesincronizat",
    sync_stale: "Portal indisponibil. Ultima sincronizare {time}",
    sync_offline: "Actualizare eșuată. Ultima sincronizare {time}",
    sync_throttled: "Portalul limitează cererile, reîncearcă peste {time}",
    logout: "Deconectare",
    theme_change: "Schimbă tema",
    theme_light: "luminoasă",
    theme_dark: "întunecată",
    theme_auto: "automată",

    // Auth
    app_title: "open-sime",
    app_subtitle: "agendă școlară",
    username: "Nume de utilizator",
    password: "Parolă",
    role: "Rol",
    role_parent: "Părinte",
    role_student: "Elev",
    remember_me: "Ține-mă minte",
    login_btn: "Autentificare",
    login_required: "Introdu numele de utilizator și parola.",

    // Overview & Home
    live_badge: "În desfășurare",
    next_badge: "Urmează",
    past_badge: "Încheiată",
    all_marks: "Toate notele",
    all_homework: "Toate temele",
    recent_grades: "Note recente",
    homework_preview: "Teme și termene",
    general_average_estimated: "Media generală (estimată)",

    // Empty States
    no_grades_title: "Nicio notă încă",
    no_grades_sub: "Notele noi apar aici după sincronizare.",
    no_disciplines_title: "Nicio disciplină încă",
    no_disciplines_sub: "Notele apar aici după sincronizarea cu portalul.",
    no_homework_title: "Nicio temă",
    no_homework_sub: "Nu ai teme de făcut acum.",
    no_lessons_title: "Nicio oră în orar",
    no_lessons_sub: "Pentru această zi nu există lecții programate.",
    no_absences_title: "Nicio absență",
    no_absences_sub: "Nu sunt absențe înregistrate în Semestrul {sem}.",
    absences_no_details_title: "Detaliile nu sunt disponibile",
    absences_no_details_sub: "Portalul raportează {total} absențe în acest semestru, dar fără detalii pe ore.",
    absences_reconciliation: "Portalul raportează {total} absențe în Semestrul {sem}, dintre care {details} au detalii pe ore. Pentru restul de {missing} {missingLabel}, detaliile nu sunt publicate în orar.",
    abs_label_singular: "absență",
    abs_label_plural: "absențe",
    no_teachers_title: "Lista profesorilor nu este disponibilă",
    no_teachers_sub: "Profesorii apar după ce orarul și notele sunt încărcate.",

    // Reușita / Marks
    discipline: "Disciplină",
    semester_1_marks: "Semestrul 1: Note",
    semester_2_marks: "Semestrul 2: Note",
    semester_1: "Semestrul 1",
    semester_2: "Semestrul 2",
    thesis: "Teză",
    thesis_badge: "Teză",
    qualifiers_legend: "Calificative:",
    qual_fb: "Foarte Bine",
    qual_b: "Bine",
    qual_s: "Suficient",
    qual_i: "Insuficient",
    grade_prefix: "Nota ",
    thesis_prefix: "Teză: ",
    qualifier_prefix: "Calificativ: ",

    // Absences
    abs_total: "Total",
    abs_excused: "Motivate",
    abs_unexcused: "Nemotivate",
    abs_by_subject: "Pe discipline",
    abs_chronological: "Cronologic",

    // Timetable
    today: "Astăzi",
    break_text: "Pauză {time}",
    lesson_period: "Ora {period}",

    // Profile
    student_label: "Elev",
    school_label: "Școala",
    class_master_label: "Diriginte",
    study_year_label: "Anul de studiu",
    birth_date_label: "Data nașterii",
    class_prefix: "Clasa ",
    teachers_section: "Profesori",
  },

  ru: {
    // Navigation
    tab_home: "Главная",
    tab_marks: "Успеваемость",
    tab_timetable: "Расписание",
    tab_absences: "Пропуски",
    tab_homework: "Домашние задания",
    tab_profile: "Профиль и учителя",

    // Actions & Shell
    sync_now: "Синхронизировать сейчас",
    sync_available_in: "Синхронизация через {time}",
    sync_syncing: "Синхронизация...",
    sync_synced: "Синхронизировано {time}",
    sync_unsynced: "Не синхронизировано",
    sync_stale: "Портал недоступен. Последняя синхронизация: {time}",
    sync_offline: "Ошибка обновления. Последняя синхронизация: {time}",
    sync_throttled: "Портал ограничивает запросы, повторите через {time}",
    logout: "Выйти",
    theme_change: "Сменить тему",
    theme_light: "светлая",
    theme_dark: "тёмная",
    theme_auto: "авто",

    // Auth
    app_title: "open-sime",
    app_subtitle: "школьный дневник",
    username: "Имя пользователя",
    password: "Пароль",
    role: "Роль",
    role_parent: "Родитель",
    role_student: "Ученик",
    remember_me: "Запомнить меня",
    login_btn: "Войти",
    login_required: "Введите имя пользователя и пароль.",

    // Overview & Home
    live_badge: "Идёт урок",
    next_badge: "Следующий",
    past_badge: "Завершён",
    all_marks: "Все оценки",
    all_homework: "Все задания",
    recent_grades: "Последние оценки",
    homework_preview: "Задания и сроки",
    general_average_estimated: "Средний балл (расчётный)",

    // Empty States
    no_grades_title: "Пока нет оценок",
    no_grades_sub: "Новые оценки появятся здесь после синхронизации.",
    no_disciplines_title: "Пока нет предметов",
    no_disciplines_sub: "Оценки появятся после синхронизации с порталом.",
    no_homework_title: "Нет заданий",
    no_homework_sub: "На данный момент домашних заданий нет.",
    no_lessons_title: "Нет уроков в расписании",
    no_lessons_sub: "На этот день уроков не запланировано.",
    no_absences_title: "Нет пропусков",
    no_absences_sub: "В {sem} семестре пропусков нет.",
    absences_no_details_title: "Детали недоступны",
    absences_no_details_sub: "Портал сообщает о {total} пропусках, но без деталей по урокам.",
    absences_reconciliation: "Портал сообщает о {total} пропусках в {sem} семестре, из которых {details} имеют детали. Для остальных {missing} {missingLabel} уроки не указаны.",
    abs_label_singular: "пропуска",
    abs_label_plural: "пропусков",
    no_teachers_title: "Список учителей недоступен",
    no_teachers_sub: "Учителя появятся после загрузки расписания и оценок.",

    // Reușita / Marks
    discipline: "Предмет",
    semester_1_marks: "Семестр 1: Оценки",
    semester_2_marks: "Семестр 2: Оценки",
    semester_1: "Семестр 1",
    semester_2: "Семестр 2",
    thesis: "Теза",
    thesis_badge: "Теза",
    qualifiers_legend: "Оценки:",
    qual_fb: "Отлично (FB)",
    qual_b: "Хорошо (B)",
    qual_s: "Удовлетворительно (S)",
    qual_i: "Неудовлетворительно (I)",
    grade_prefix: "Оценка ",
    thesis_prefix: "Теза: ",
    qualifier_prefix: "Оценка: ",

    // Absences
    abs_total: "Всего",
    abs_excused: "Уважительные",
    abs_unexcused: "Неуважительные",
    abs_by_subject: "По предметам",
    abs_chronological: "Хронология",

    // Timetable
    today: "Сегодня",
    break_text: "Перемена {time}",
    lesson_period: "Урок {period}",

    // Profile
    student_label: "Ученик",
    school_label: "Школа",
    class_master_label: "Классный руководитель",
    study_year_label: "Учебный год",
    birth_date_label: "Дата рождения",
    class_prefix: "Класс ",
    teachers_section: "Учителя",
  },

  en: {
    // Navigation
    tab_home: "Home",
    tab_marks: "Grades",
    tab_timetable: "Timetable",
    tab_absences: "Absences",
    tab_homework: "Homework",
    tab_profile: "Profile & Teachers",

    // Actions & Shell
    sync_now: "Sync now",
    sync_available_in: "Sync available in {time}",
    sync_syncing: "Syncing...",
    sync_synced: "Synced {time}",
    sync_unsynced: "Not synced",
    sync_stale: "Portal unavailable. Last synced {time}",
    sync_offline: "Update failed. Last synced {time}",
    sync_throttled: "Portal rate limit reached, retry in {time}",
    logout: "Log out",
    theme_change: "Change theme",
    theme_light: "light",
    theme_dark: "dark",
    theme_auto: "auto",

    // Auth
    app_title: "open-sime",
    app_subtitle: "school agenda",
    username: "Username",
    password: "Password",
    role: "Role",
    role_parent: "Parent",
    role_student: "Student",
    remember_me: "Remember me",
    login_btn: "Log in",
    login_required: "Please enter your username and password.",

    // Overview & Home
    live_badge: "In progress",
    next_badge: "Next",
    past_badge: "Finished",
    all_marks: "All grades",
    all_homework: "All homework",
    recent_grades: "Recent grades",
    homework_preview: "Homework & Deadlines",
    general_average_estimated: "General GPA (estimated)",

    // Empty States
    no_grades_title: "No grades yet",
    no_grades_sub: "New grades will appear here after sync.",
    no_disciplines_title: "No subjects yet",
    no_disciplines_sub: "Grades appear here after syncing with the portal.",
    no_homework_title: "No homework",
    no_homework_sub: "You have no homework right now.",
    no_lessons_title: "No lessons scheduled",
    no_lessons_sub: "There are no lessons scheduled for this day.",
    no_absences_title: "No absences",
    no_absences_sub: "No absences recorded in Semester {sem}.",
    absences_no_details_title: "Details unavailable",
    absences_no_details_sub: "The portal reports {total} absences, but without lesson breakdown.",
    absences_reconciliation: "The portal reports {total} absences in Semester {sem}, of which {details} have hourly details. For the remaining {missing} {missingLabel}, details are not published in the timetable.",
    abs_label_singular: "absence",
    abs_label_plural: "absences",
    no_teachers_title: "Teacher list unavailable",
    no_teachers_sub: "Teachers will appear after schedule and grades are loaded.",

    // Reușita / Marks
    discipline: "Subject",
    semester_1_marks: "Semester 1: Grades",
    semester_2_marks: "Semester 2: Grades",
    semester_1: "Semester 1",
    semester_2: "Semester 2",
    thesis: "Thesis",
    thesis_badge: "Thesis",
    qualifiers_legend: "Qualifiers:",
    qual_fb: "Very Good (FB)",
    qual_b: "Good (B)",
    qual_s: "Sufficient (S)",
    qual_i: "Insufficient (I)",
    grade_prefix: "Grade ",
    thesis_prefix: "Thesis: ",
    qualifier_prefix: "Qualifier: ",

    // Absences
    abs_total: "Total",
    abs_excused: "Excused",
    abs_unexcused: "Unexcused",
    abs_by_subject: "By subject",
    abs_chronological: "Chronological",

    // Timetable
    today: "Today",
    break_text: "Break {time}",
    lesson_period: "Period {period}",

    // Profile
    student_label: "Student",
    school_label: "School",
    class_master_label: "Class master",
    study_year_label: "Study year",
    birth_date_label: "Date of birth",
    class_prefix: "Class ",
    teachers_section: "Teachers",
  },
};

function getString(key, params = {}, lang = "ro") {
  const dict = STRINGS[lang] || STRINGS.ro;
  let str = dict[key] || STRINGS.ro[key] || key;
  if (params && typeof params === "object") {
    for (const [k, v] of Object.entries(params)) {
      str = str.replaceAll(`{${k}}`, v == null || Number.isNaN(v) ? "" : String(v));
    }
  }
  return str;
}

if (typeof window !== "undefined") {
  window.STRINGS = STRINGS;
  window.getString = getString;
}

export { STRINGS, getString };
