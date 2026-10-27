package md.sime.opensime.core.data

import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.AbsenceSummary
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.HomeworkItem
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.LessonStatus
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentNotFoundException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.model.SubjectMarksSummary
import md.sime.opensime.core.model.SubjectScale
import md.sime.opensime.core.model.TeacherItem

/**
 * Sample repository providing clearly identified synthetic data for two distinct students
 * (a 7th-grade middle school student and an 11th-grade high school student) so student switching,
 * student isolation, qualifier marks, thesis marks, and Simple Mode can be reviewed and tested offline.
 */
class SampleAgendaRepository(
    customSnapshots: Map<StudentId, StudentAgendaSnapshot>? = null,
) : AgendaRepository {

    private val snapshotsByStudent: Map<StudentId, StudentAgendaSnapshot> =
        customSnapshots ?: buildDefaultSampleSnapshots()

    override fun listStudents(): List<StudentProfile> =
        snapshotsByStudent.values.map { it.student }

    override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
        val snapshot = snapshotsByStudent[studentId]
            ?: throw StudentNotFoundException(studentId)
        return snapshot.verifyStudentOwnership(studentId)
    }

    companion object {
        val STUDENT_SEVENTH_GRADE = StudentId("sample-student-7a")
        val STUDENT_ELEVENTH_GRADE = StudentId("sample-student-11r")

        fun buildDefaultSampleSnapshots(): Map<StudentId, StudentAgendaSnapshot> {
            val student7A = buildSeventhGradeSnapshot()
            val student11R = buildEleventhGradeSnapshot()
            return linkedMapOf(
                student7A.studentId to student7A,
                student11R.studentId to student11R,
            )
        }

        private fun buildSeventhGradeSnapshot(): StudentAgendaSnapshot {
            val profile = StudentProfile(
                id = STUDENT_SEVENTH_GRADE,
                fullName = "Elev Unu Exemplu",
                className = "7A",
                schoolName = "I.P. Liceul Teoretic „Exemplu”",
                classMasterName = "Prof. Exemplu Rusu",
                currentSemester = 1,
                academicYear = "2026–2027",
                enrollmentStatus = "Înmatriculat",
                birthDate = "15.05.2012",
            )

            val mathMarks = listOf(
                MarkItem(
                    id = "7a-m1",
                    subject = "Matematica",
                    value = MarkValue.Numeric(10),
                    isoDate = "2026-10-26",
                    semester = 1,
                    teacherName = "Prof. Exemplu Rusu",
                    topic = "Ecuații de gradul I",
                ),
                MarkItem(
                    id = "7a-m2",
                    subject = "Matematica",
                    value = MarkValue.Numeric(9),
                    isoDate = "2026-10-18",
                    semester = 1,
                    teacherName = "Prof. Exemplu Rusu",
                    topic = "Calcul algebric",
                ),
                MarkItem(
                    id = "7a-m3",
                    subject = "Matematica",
                    value = MarkValue.Numeric(10),
                    isoDate = "2026-10-05",
                    semester = 1,
                    teacherName = "Prof. Exemplu Rusu",
                ),
            )

            val romanianMarks = listOf(
                MarkItem(
                    id = "7a-r1",
                    subject = "Limba și literatura română",
                    value = MarkValue.Numeric(9),
                    isoDate = "2026-10-24",
                    semester = 1,
                    teacherName = "Prof. Exemplu Cazacu",
                    topic = "Textul liric",
                ),
                MarkItem(
                    id = "7a-r2",
                    subject = "Limba și literatura română",
                    value = MarkValue.Numeric(10),
                    isoDate = "2026-10-12",
                    semester = 1,
                    teacherName = "Prof. Exemplu Cazacu",
                ),
            )

            val historyMarks = listOf(
                MarkItem(
                    id = "7a-h1",
                    subject = "Istoria românilor și universală",
                    value = MarkValue.Numeric(9),
                    isoDate = "2026-10-21",
                    semester = 1,
                    teacherName = "Prof. Exemplu Munteanu",
                ),
                MarkItem(
                    id = "7a-h2",
                    subject = "Istoria românilor și universală",
                    value = MarkValue.Numeric(8),
                    isoDate = "2026-10-07",
                    semester = 1,
                    teacherName = "Prof. Exemplu Munteanu",
                ),
            )

            val techEdMarks = listOf(
                MarkItem(
                    id = "7a-t1",
                    subject = "Educația tehnologică",
                    value = MarkValue.Qualifier(QualifierCode.FB),
                    isoDate = "2026-10-22",
                    semester = 1,
                    teacherName = "Prof. Exemplu Vieru",
                    topic = "Proiect practic",
                ),
                MarkItem(
                    id = "7a-t2",
                    subject = "Educația tehnologică",
                    value = MarkValue.Qualifier(QualifierCode.B),
                    isoDate = "2026-10-08",
                    semester = 1,
                    teacherName = "Prof. Exemplu Vieru",
                ),
            )

            val biologyMarks = listOf(
                MarkItem(
                    id = "7a-b1",
                    subject = "Biologia",
                    value = MarkValue.Numeric(10),
                    isoDate = "2026-10-19",
                    semester = 1,
                    teacherName = "Prof. Exemplu Lungu",
                ),
            )

            val tuesdayLessons = listOf(
                LessonItem(
                    id = "7a-l1",
                    periodNumber = 1,
                    startTime = "08:00",
                    endTime = "08:45",
                    subject = "Matematica",
                    room = "Cab. 204",
                    teacherName = "Prof. Exemplu Rusu",
                    topic = "Sisteme de ecuații liniare",
                    homeworkSummary = "Ex. 4, 5 pag. 68",
                    status = LessonStatus.COMPLETED,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "7a-l2",
                    periodNumber = 2,
                    startTime = "08:55",
                    endTime = "09:40",
                    subject = "Limba și literatura română",
                    room = "Cab. 112",
                    teacherName = "Prof. Exemplu Cazacu",
                    topic = "Figuri de stil în pastel",
                    homeworkSummary = "Comentariu 8–10 rânduri, pag. 54",
                    status = LessonStatus.LIVE_NOW,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "7a-l3",
                    periodNumber = 3,
                    startTime = "09:55",
                    endTime = "10:40",
                    subject = "Biologia",
                    room = "Lab. Biologie",
                    teacherName = "Prof. Exemplu Lungu",
                    topic = "Respirația la plante",
                    homeworkSummary = "Fișa de observație pag. 41",
                    status = LessonStatus.NEXT_UP,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "7a-l4",
                    periodNumber = 4,
                    startTime = "10:55",
                    endTime = "11:40",
                    subject = "Istoria românilor și universală",
                    room = "Cab. 305",
                    teacherName = "Prof. Exemplu Munteanu",
                    topic = "Civilizațiile antice",
                    status = LessonStatus.UPCOMING,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "7a-l5",
                    periodNumber = 5,
                    startTime = "11:50",
                    endTime = "12:35",
                    subject = "Educația tehnologică",
                    room = "Atelier 1",
                    teacherName = "Prof. Exemplu Vieru",
                    topic = "Materiale ecologice",
                    status = LessonStatus.UPCOMING,
                    dayOfWeekIndex = 1,
                ),
            )

            val weekLessons = mapOf(
                0 to listOf(
                    LessonItem("7a-mon-1", 1, "08:00", "08:45", "Limba și literatura română", "Cab. 112", "Prof. Exemplu Cazacu", "Genul liric", dayOfWeekIndex = 0),
                    LessonItem("7a-mon-2", 2, "08:55", "09:40", "Matematica", "Cab. 204", "Prof. Exemplu Rusu", "Ecuații echivalente", dayOfWeekIndex = 0),
                    LessonItem("7a-mon-3", 3, "09:55", "10:40", "Limba engleză", "Cab. 210", "Prof. Exemplu Sandu", "Present Perfect Continuous", dayOfWeekIndex = 0),
                    LessonItem("7a-mon-4", 4, "10:55", "11:40", "Fizica", "Lab. Fizică", "Prof. Exemplu Ursu", "Mișcarea rectilinie uniformă", dayOfWeekIndex = 0),
                ),
                1 to tuesdayLessons,
                2 to listOf(
                    LessonItem("7a-wed-1", 1, "08:00", "08:45", "Matematica", "Cab. 204", "Prof. Exemplu Rusu", "Probleme cu ecuații", dayOfWeekIndex = 2),
                    LessonItem("7a-wed-2", 2, "08:55", "09:40", "Geografia", "Cab. 301", "Prof. Exemplu Ciobanu", "Relieful terestru", dayOfWeekIndex = 2),
                    LessonItem("7a-wed-3", 3, "09:55", "10:40", "Limba și literatura română", "Cab. 112", "Prof. Exemplu Cazacu", "Rima și măsura", dayOfWeekIndex = 2),
                    LessonItem("7a-wed-4", 4, "10:55", "11:40", "Informatica", "Lab. TIC", "Prof. Exemplu Grosu", "Prezentări electronice", dayOfWeekIndex = 2),
                ),
                3 to listOf(
                    LessonItem("7a-thu-1", 1, "08:00", "08:45", "Fizica", "Lab. Fizică", "Prof. Exemplu Ursu", "Viteza medie", dayOfWeekIndex = 3),
                    LessonItem("7a-thu-2", 2, "08:55", "09:40", "Istoria românilor și universală", "Cab. 305", "Prof. Exemplu Munteanu", "Grecia antică", dayOfWeekIndex = 3),
                    LessonItem("7a-thu-3", 3, "09:55", "10:40", "Biologia", "Lab. Biologie", "Prof. Exemplu Lungu", "Fotosinteza", dayOfWeekIndex = 3),
                    LessonItem("7a-thu-4", 4, "10:55", "11:40", "Limba engleză", "Cab. 210", "Prof. Exemplu Sandu", "Reading comprehension", dayOfWeekIndex = 3),
                ),
                4 to listOf(
                    LessonItem("7a-fri-1", 1, "08:00", "08:45", "Matematica", "Cab. 204", "Prof. Exemplu Rusu", "Evaluare formativă", dayOfWeekIndex = 4),
                    LessonItem("7a-fri-2", 2, "08:55", "09:40", "Limba și literatura română", "Cab. 112", "Prof. Exemplu Cazacu", "Atelier de lectură", dayOfWeekIndex = 4),
                    LessonItem("7a-fri-3", 3, "09:55", "10:40", "Dezvoltarea personală", "Cab. 204", "Prof. Exemplu Rusu", "Comunicarea eficientă", dayOfWeekIndex = 4),
                ),
            )

            return StudentAgendaSnapshot(
                student = profile,
                dataSourceMode = DataSourceMode.SAMPLE_PREVIEW,
                referenceIsoDate = "2026-10-27",
                referenceTimeHm = "09:15",
                recentMarks = listOf(
                    mathMarks[0],
                    romanianMarks[0],
                    techEdMarks[0],
                    historyMarks[0],
                    biologyMarks[0],
                ),
                subjectSummaries = listOf(
                    SubjectMarksSummary("Matematica", "Prof. Exemplu Rusu", 1, mathMarks),
                    SubjectMarksSummary("Limba și literatura română", "Prof. Exemplu Cazacu", 1, romanianMarks),
                    SubjectMarksSummary("Biologia", "Prof. Exemplu Lungu", 1, biologyMarks),
                    SubjectMarksSummary("Istoria românilor și universală", "Prof. Exemplu Munteanu", 1, historyMarks),
                    SubjectMarksSummary("Educația tehnologică", "Prof. Exemplu Vieru", 1, techEdMarks, scale = SubjectScale.QUALIFIER),
                ),
                todayLessons = tuesdayLessons,
                weekLessonsByDay = weekLessons,
                absenceSummary = AbsenceSummary(
                    totalCount = 2,
                    excusedCount = 1,
                    unexcusedCount = 1,
                    presencePercent = 98.4,
                ),
                absences = listOf(
                    AbsenceItem(
                        id = "7a-abs-1",
                        subject = "Istoria românilor și universală",
                        isoDate = "2026-10-14",
                        semester = 1,
                        status = AbsenceStatus.EXCUSED,
                        periodNumber = 4,
                        periodTime = "10:55 - 11:40",
                        room = "Cab. 305",
                        teacherName = "Prof. Exemplu Munteanu",
                    ),
                    AbsenceItem(
                        id = "7a-abs-2",
                        subject = "Fizica",
                        isoDate = "2026-10-08",
                        semester = 1,
                        status = AbsenceStatus.UNEXCUSED,
                        periodNumber = 1,
                        periodTime = "08:00 - 08:45",
                        room = "Lab. Fizică",
                        teacherName = "Prof. Exemplu Ursu",
                    ),
                ),
                homeworkItems = listOf(
                    HomeworkItem(
                        id = "7a-hw-1",
                        subject = "Matematica",
                        description = "Rezolvă exercițiile 4 și 5 de la pagina 68 (sisteme de ecuații).",
                        assignedIsoDate = "2026-10-27",
                        dueIsoDate = "2026-10-28",
                    ),
                    HomeworkItem(
                        id = "7a-hw-2",
                        subject = "Limba și literatura română",
                        description = "Scrie un comentariu de 8–10 rânduri despre imaginile vizuale din pastel (pag. 54).",
                        assignedIsoDate = "2026-10-27",
                        dueIsoDate = "2026-10-28",
                    ),
                    HomeworkItem(
                        id = "7a-hw-3",
                        subject = "Biologia",
                        description = "Completează fișa de observație privind respirația plantelor (pag. 41).",
                        assignedIsoDate = "2026-10-27",
                        dueIsoDate = "2026-10-29",
                    ),
                ),
                teachers = listOf(
                    TeacherItem("Prof. Exemplu Rusu", listOf("Matematica", "Dezvoltarea personală"), isClassMaster = true),
                    TeacherItem("Prof. Exemplu Cazacu", listOf("Limba și literatura română")),
                    TeacherItem("Prof. Exemplu Lungu", listOf("Biologia")),
                    TeacherItem("Prof. Exemplu Munteanu", listOf("Istoria românilor și universală")),
                    TeacherItem("Prof. Exemplu Ursu", listOf("Fizica")),
                    TeacherItem("Prof. Exemplu Vieru", listOf("Educația tehnologică")),
                ),
            )
        }

        private fun buildEleventhGradeSnapshot(): StudentAgendaSnapshot {
            val profile = StudentProfile(
                id = STUDENT_ELEVENTH_GRADE,
                fullName = "Elev Doi Exemplu",
                className = "11R",
                schoolName = "I.P. Liceul Teoretic „Exemplu”",
                classMasterName = "Prof. Exemplu Grosu",
                currentSemester = 1,
                academicYear = "2026–2027",
                enrollmentStatus = "Înmatriculat",
                birthDate = "15.05.2012",
            )

            val csMarks = listOf(
                MarkItem(
                    id = "11r-cs-1",
                    subject = "Informatica",
                    value = MarkValue.Numeric(10),
                    isoDate = "2026-10-25",
                    semester = 1,
                    isThesis = true,
                    teacherName = "Prof. Exemplu Grosu",
                    topic = "Teză semestrială",
                ),
                MarkItem(
                    id = "11r-cs-2",
                    subject = "Informatica",
                    value = MarkValue.Numeric(10),
                    isoDate = "2026-10-15",
                    semester = 1,
                    teacherName = "Prof. Exemplu Grosu",
                    topic = "Structuri de date și grafuri",
                ),
            )

            val physicsMarks = listOf(
                MarkItem(
                    id = "11r-ph-1",
                    subject = "Fizica",
                    value = MarkValue.Numeric(9),
                    isoDate = "2026-10-23",
                    semester = 1,
                    teacherName = "Prof. Exemplu Ursu",
                    topic = "Termodinamica",
                ),
                MarkItem(
                    id = "11r-ph-2",
                    subject = "Fizica",
                    value = MarkValue.Numeric(8),
                    isoDate = "2026-10-09",
                    semester = 1,
                    teacherName = "Prof. Exemplu Ursu",
                ),
            )

            val mathMarks = listOf(
                MarkItem(
                    id = "11r-ma-1",
                    subject = "Matematica",
                    value = MarkValue.Numeric(9),
                    isoDate = "2026-10-22",
                    semester = 1,
                    teacherName = "Prof. Exemplu Bălan",
                ),
            )

            val civicMarks = listOf(
                MarkItem(
                    id = "11r-cv-1",
                    subject = "Educație pentru societate",
                    value = MarkValue.Qualifier(QualifierCode.FB),
                    isoDate = "2026-10-20",
                    semester = 1,
                    teacherName = "Prof. Exemplu Munteanu",
                ),
            )

            val tuesdayLessons = listOf(
                LessonItem(
                    id = "11r-l1",
                    periodNumber = 1,
                    startTime = "08:00",
                    endTime = "08:45",
                    subject = "Informatica",
                    room = "Lab. TIC 2",
                    teacherName = "Prof. Exemplu Grosu",
                    topic = "Algoritmi pe grafuri orientate",
                    homeworkSummary = "Problema 3 din setul de laborator",
                    status = LessonStatus.COMPLETED,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "11r-l2",
                    periodNumber = 2,
                    startTime = "08:55",
                    endTime = "09:40",
                    subject = "Fizica",
                    room = "Lab. Fizică",
                    teacherName = "Prof. Exemplu Ursu",
                    topic = "Principiul I al termodinamicii",
                    homeworkSummary = "Problemele 12–14 pag. 79",
                    status = LessonStatus.LIVE_NOW,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "11r-l3",
                    periodNumber = 3,
                    startTime = "09:55",
                    endTime = "10:40",
                    subject = "Matematica",
                    room = "Cab. 310",
                    teacherName = "Prof. Exemplu Bălan",
                    topic = "Limite de funcții",
                    status = LessonStatus.NEXT_UP,
                    dayOfWeekIndex = 1,
                ),
                LessonItem(
                    id = "11r-l4",
                    periodNumber = 4,
                    startTime = "10:55",
                    endTime = "11:40",
                    subject = "Chimia",
                    room = "Lab. Chimie",
                    teacherName = "Prof. Exemplu Rotaru",
                    topic = "Compuși organici",
                    status = LessonStatus.UPCOMING,
                    dayOfWeekIndex = 1,
                ),
            )

            val weekLessons = mapOf(
                0 to listOf(
                    LessonItem("11r-mon-1", 1, "08:00", "08:45", "Matematica", "Cab. 310", "Prof. Exemplu Bălan", "Șiruri convergente", dayOfWeekIndex = 0),
                    LessonItem("11r-mon-2", 2, "08:55", "09:40", "Informatica", "Lab. TIC 2", "Prof. Exemplu Grosu", "Matricea de adiacență", dayOfWeekIndex = 0),
                    LessonItem("11r-mon-3", 3, "09:55", "10:40", "Fizica", "Lab. Fizică", "Prof. Exemplu Ursu", "Ecuația de stare a gazului ideal", dayOfWeekIndex = 0),
                ),
                1 to tuesdayLessons,
                2 to listOf(
                    LessonItem("11r-wed-1", 1, "08:00", "08:45", "Informatica", "Lab. TIC 2", "Prof. Exemplu Grosu", "Parcurgerea în lățime (BFS)", dayOfWeekIndex = 2),
                    LessonItem("11r-wed-2", 2, "08:55", "09:40", "Matematica", "Cab. 310", "Prof. Exemplu Bălan", "Funcții continue", dayOfWeekIndex = 2),
                    LessonItem("11r-wed-3", 3, "09:55", "10:40", "Educație pentru societate", "Cab. 305", "Prof. Exemplu Munteanu", "Cetățenia activă", dayOfWeekIndex = 2),
                ),
                3 to listOf(
                    LessonItem("11r-thu-1", 1, "08:00", "08:45", "Fizica", "Lab. Fizică", "Prof. Exemplu Ursu", "Procese izoterme", dayOfWeekIndex = 3),
                    LessonItem("11r-thu-2", 2, "08:55", "09:40", "Chimia", "Lab. Chimie", "Prof. Exemplu Rotaru", "Izomeria", dayOfWeekIndex = 3),
                ),
                // Friday (index 4) intentionally empty to demonstrate localized empty day timetable state
                4 to emptyList(),
            )

            return StudentAgendaSnapshot(
                student = profile,
                dataSourceMode = DataSourceMode.SAMPLE_PREVIEW,
                referenceIsoDate = "2026-10-27",
                referenceTimeHm = "09:15",
                recentMarks = listOf(csMarks[0], physicsMarks[0], mathMarks[0], civicMarks[0], csMarks[1]),
                subjectSummaries = listOf(
                    SubjectMarksSummary("Informatica", "Prof. Exemplu Grosu", 1, csMarks),
                    SubjectMarksSummary("Matematica", "Prof. Exemplu Bălan", 1, mathMarks),
                    SubjectMarksSummary("Fizica", "Prof. Exemplu Ursu", 1, physicsMarks),
                    SubjectMarksSummary("Educație pentru societate", "Prof. Exemplu Munteanu", 1, civicMarks, scale = SubjectScale.DESCRIPTOR),
                ),
                todayLessons = tuesdayLessons,
                weekLessonsByDay = weekLessons,
                absenceSummary = AbsenceSummary(
                    totalCount = 1,
                    excusedCount = 1,
                    unexcusedCount = 0,
                    presencePercent = 99.2,
                ),
                absences = listOf(
                    AbsenceItem(
                        id = "11r-abs-1",
                        subject = "Chimia",
                        isoDate = "2026-10-10",
                        semester = 1,
                        status = AbsenceStatus.EXCUSED,
                        periodNumber = 4,
                        periodTime = "10:55 - 11:40",
                        room = "Lab. Chimie",
                        teacherName = "Prof. Exemplu Rotaru",
                    ),
                ),
                homeworkItems = listOf(
                    HomeworkItem(
                        id = "11r-hw-1",
                        subject = "Informatica",
                        description = "Implementează parcurgerea în lățime (BFS) pentru problema 3 din laborator.",
                        assignedIsoDate = "2026-10-27",
                        dueIsoDate = "2026-10-29",
                    ),
                    HomeworkItem(
                        id = "11r-hw-2",
                        subject = "Fizica",
                        description = "Rezolvă problemele 12–14 pag. 79 (lucrul mecanic în procese izoterme).",
                        assignedIsoDate = "2026-10-27",
                        dueIsoDate = "2026-10-30",
                    ),
                ),
                teachers = listOf(
                    TeacherItem("Prof. Exemplu Grosu", listOf("Informatica"), isClassMaster = true),
                    TeacherItem("Prof. Exemplu Bălan", listOf("Matematica")),
                    TeacherItem("Prof. Exemplu Ursu", listOf("Fizica")),
                    TeacherItem("Prof. Exemplu Rotaru", listOf("Chimia")),
                    TeacherItem("Prof. Exemplu Munteanu", listOf("Educație pentru societate")),
                ),
            )
        }
    }
}
