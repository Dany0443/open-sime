package md.sime.opensime.core.data

import md.sime.opensime.core.model.*
import md.sime.opensime.core.portal.SimpleJson

/** Versioned explicit schema; no reflection or executable object deserialization. */
internal object SnapshotCodec {
    fun encode(snapshot: StudentAgendaSnapshot): ByteArray = json(encodeStudentAgendaSnapshot(snapshot)).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): StudentAgendaSnapshot = decodeStudentAgendaSnapshot(SimpleJson.parse(bytes.toString(Charsets.UTF_8)))
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> SimpleJson.quoteString(value)
        is Number, is Boolean -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key) + ":" + json(it.value) }
        else -> error("Unsupported cache value")
    }
    private fun encodeMarkValue(value: MarkValue): List<Any> = when (value) {
        is MarkValue.Numeric -> listOf("numeric", value.score, value.decimalScore)
        is MarkValue.Qualifier -> listOf("qualifier", value.qualifier.name)
        is MarkValue.Other -> listOf("other", value.label)
    }
    private fun decodeMarkValue(value: Any?): MarkValue {
        val parts = value as List<*>
        return when (parts[0]) {
            "numeric" -> MarkValue.Numeric((parts[1] as Number).toInt(), (parts[2] as Number).toDouble())
            "qualifier" -> MarkValue.Qualifier(QualifierCode.valueOf(parts[1] as String))
            "other" -> MarkValue.Other(parts[1] as String)
            else -> error("Unknown mark kind")
        }
    }
    private fun encodeStudentProfile(value: StudentProfile): Map<String, Any?> = mapOf(
        "id" to value.id.value,
        "fullName" to value.fullName,
        "className" to value.className,
        "schoolName" to value.schoolName,
        "classMasterName" to value.classMasterName,
        "currentSemester" to value.currentSemester,
        "academicYear" to value.academicYear,
        "enrollmentStatus" to value.enrollmentStatus?.let { it },
        "birthDate" to value.birthDate?.let { it },
    )
    private fun decodeStudentProfile(value: Any?): StudentProfile {
        val data = value as Map<*, *>
        return StudentProfile(
            id = StudentId(data["id"] as String),
            fullName = data["fullName"] as String,
            className = data["className"] as String,
            schoolName = data["schoolName"] as String,
            classMasterName = data["classMasterName"] as String,
            currentSemester = (data["currentSemester"] as Number).toInt(),
            academicYear = data["academicYear"] as String,
            enrollmentStatus = data["enrollmentStatus"]?.let { it as String },
            birthDate = data["birthDate"]?.let { it as String },
        )
    }
    private fun encodeMarkItem(value: MarkItem): Map<String, Any?> = mapOf(
        "id" to value.id,
        "subject" to value.subject,
        "value" to encodeMarkValue(value.value),
        "isoDate" to value.isoDate,
        "semester" to value.semester,
        "isThesis" to value.isThesis,
        "teacherName" to value.teacherName?.let { it },
        "topic" to value.topic?.let { it },
    )
    private fun decodeMarkItem(value: Any?): MarkItem {
        val data = value as Map<*, *>
        return MarkItem(
            id = data["id"] as String,
            subject = data["subject"] as String,
            value = decodeMarkValue(data["value"]),
            isoDate = data["isoDate"] as String,
            semester = (data["semester"] as Number).toInt(),
            isThesis = data["isThesis"] as Boolean,
            teacherName = data["teacherName"]?.let { it as String },
            topic = data["topic"]?.let { it as String },
        )
    }
    private fun encodeSubjectMarksSummary(value: SubjectMarksSummary): Map<String, Any?> = mapOf(
        "subject" to value.subject,
        "teacherName" to value.teacherName,
        "semester" to value.semester,
        "marks" to value.marks.map { encodeMarkItem(it) },
        "portalAverage" to value.portalAverage?.let { it },
        "scale" to value.scale.name,
    )
    private fun decodeSubjectMarksSummary(value: Any?): SubjectMarksSummary {
        val data = value as Map<*, *>
        return SubjectMarksSummary(
            subject = data["subject"] as String,
            teacherName = data["teacherName"] as String,
            semester = (data["semester"] as Number).toInt(),
            marks = (data["marks"] as List<*>).map { decodeMarkItem(it) },
            portalAverage = data["portalAverage"]?.let { (it as Number).toDouble() },
            scale = SubjectScale.valueOf(data["scale"] as String),
        )
    }
    private fun encodeLessonItem(value: LessonItem): Map<String, Any?> = mapOf(
        "id" to value.id,
        "periodNumber" to value.periodNumber,
        "startTime" to value.startTime,
        "endTime" to value.endTime,
        "subject" to value.subject,
        "room" to value.room,
        "teacherName" to value.teacherName,
        "topic" to value.topic?.let { it },
        "homeworkSummary" to value.homeworkSummary?.let { it },
        "status" to value.status.name,
        "dayOfWeekIndex" to value.dayOfWeekIndex,
    )
    private fun decodeLessonItem(value: Any?): LessonItem {
        val data = value as Map<*, *>
        return LessonItem(
            id = data["id"] as String,
            periodNumber = (data["periodNumber"] as Number).toInt(),
            startTime = data["startTime"] as String,
            endTime = data["endTime"] as String,
            subject = data["subject"] as String,
            room = data["room"] as String,
            teacherName = data["teacherName"] as String,
            topic = data["topic"]?.let { it as String },
            homeworkSummary = data["homeworkSummary"]?.let { it as String },
            status = LessonStatus.valueOf(data["status"] as String),
            dayOfWeekIndex = (data["dayOfWeekIndex"] as Number).toInt(),
        )
    }
    private fun encodeAbsenceItem(value: AbsenceItem): Map<String, Any?> = mapOf(
        "id" to value.id,
        "subject" to value.subject,
        "isoDate" to value.isoDate,
        "semester" to value.semester,
        "status" to value.status.name,
        "periodNumber" to value.periodNumber?.let { it },
        "periodTime" to value.periodTime?.let { it },
        "room" to value.room?.let { it },
        "teacherName" to value.teacherName?.let { it },
        "note" to value.note?.let { it },
    )
    private fun decodeAbsenceItem(value: Any?): AbsenceItem {
        val data = value as Map<*, *>
        return AbsenceItem(
            id = data["id"] as String,
            subject = data["subject"] as String,
            isoDate = data["isoDate"] as String,
            semester = (data["semester"] as Number).toInt(),
            status = AbsenceStatus.valueOf(data["status"] as String),
            periodNumber = data["periodNumber"]?.let { (it as Number).toInt() },
            periodTime = data["periodTime"]?.let { it as String },
            room = data["room"]?.let { it as String },
            teacherName = data["teacherName"]?.let { it as String },
            note = data["note"]?.let { it as String },
        )
    }
    private fun encodeAbsenceSummary(value: AbsenceSummary): Map<String, Any?> = mapOf(
        "totalCount" to value.totalCount,
        "excusedCount" to value.excusedCount,
        "unexcusedCount" to value.unexcusedCount,
        "presencePercent" to value.presencePercent?.let { it },
    )
    private fun decodeAbsenceSummary(value: Any?): AbsenceSummary {
        val data = value as Map<*, *>
        return AbsenceSummary(
            totalCount = (data["totalCount"] as Number).toInt(),
            excusedCount = (data["excusedCount"] as Number).toInt(),
            unexcusedCount = (data["unexcusedCount"] as Number).toInt(),
            presencePercent = data["presencePercent"]?.let { (it as Number).toDouble() },
        )
    }
    private fun encodeHomeworkItem(value: HomeworkItem): Map<String, Any?> = mapOf(
        "id" to value.id,
        "subject" to value.subject,
        "description" to value.description,
        "assignedIsoDate" to value.assignedIsoDate,
        "dueIsoDate" to value.dueIsoDate,
        "isDonePreview" to value.isDonePreview,
    )
    private fun decodeHomeworkItem(value: Any?): HomeworkItem {
        val data = value as Map<*, *>
        return HomeworkItem(
            id = data["id"] as String,
            subject = data["subject"] as String,
            description = data["description"] as String,
            assignedIsoDate = data["assignedIsoDate"] as String,
            dueIsoDate = data["dueIsoDate"] as String,
            isDonePreview = data["isDonePreview"] as Boolean,
        )
    }
    private fun encodeTeacherItem(value: TeacherItem): Map<String, Any?> = mapOf(
        "name" to value.name,
        "subjects" to value.subjects.map { it },
        "isClassMaster" to value.isClassMaster,
    )
    private fun decodeTeacherItem(value: Any?): TeacherItem {
        val data = value as Map<*, *>
        return TeacherItem(
            name = data["name"] as String,
            subjects = (data["subjects"] as List<*>).map { it as String },
            isClassMaster = data["isClassMaster"] as Boolean,
        )
    }
    private fun encodeStudentAgendaSnapshot(value: StudentAgendaSnapshot): Map<String, Any?> = mapOf(
        "student" to encodeStudentProfile(value.student),
        "dataSourceMode" to value.dataSourceMode.name,
        "referenceIsoDate" to value.referenceIsoDate,
        "referenceTimeHm" to value.referenceTimeHm,
        "recentMarks" to value.recentMarks.map { encodeMarkItem(it) },
        "subjectSummaries" to value.subjectSummaries.map { encodeSubjectMarksSummary(it) },
        "todayLessons" to value.todayLessons.map { encodeLessonItem(it) },
        "weekLessonsByDay" to value.weekLessonsByDay.entries.associate { (k, v) -> k.toString() to v.map { encodeLessonItem(it) } },
        "absenceSummary" to encodeAbsenceSummary(value.absenceSummary),
        "absences" to value.absences.map { encodeAbsenceItem(it) },
        "homeworkItems" to value.homeworkItems.map { encodeHomeworkItem(it) },
        "teachers" to value.teachers.map { encodeTeacherItem(it) },
        "weekDayIsoDates" to value.weekDayIsoDates.map { it },
        "lessonsByDate" to value.lessonsByDate.entries.associate { (k, v) -> k.toString() to v.map { encodeLessonItem(it) } },
        "successfulSyncMillis" to value.successfulSyncMillis.entries.associate { (k, v) -> k.toString() to v },
        "stale" to value.stale,
        "sem1AbsenceSummary" to value.sem1AbsenceSummary?.let { encodeAbsenceSummary(it) },
        "sem2AbsenceSummary" to value.sem2AbsenceSummary?.let { encodeAbsenceSummary(it) },
    )
    private fun decodeStudentAgendaSnapshot(value: Any?): StudentAgendaSnapshot {
        val data = value as Map<*, *>
        return StudentAgendaSnapshot(
            student = decodeStudentProfile(data["student"]),
            dataSourceMode = DataSourceMode.valueOf(data["dataSourceMode"] as String),
            referenceIsoDate = data["referenceIsoDate"] as String,
            referenceTimeHm = data["referenceTimeHm"] as String,
            recentMarks = (data["recentMarks"] as List<*>).map { decodeMarkItem(it) },
            subjectSummaries = (data["subjectSummaries"] as List<*>).map { decodeSubjectMarksSummary(it) },
            todayLessons = (data["todayLessons"] as List<*>).map { decodeLessonItem(it) },
            weekLessonsByDay = (data["weekLessonsByDay"] as Map<*, *>).entries.associate { (k, v) -> k.toString().toInt() to (v as List<*>).map { decodeLessonItem(it) } },
            absenceSummary = decodeAbsenceSummary(data["absenceSummary"]),
            absences = (data["absences"] as List<*>).map { decodeAbsenceItem(it) },
            homeworkItems = (data["homeworkItems"] as List<*>).map { decodeHomeworkItem(it) },
            teachers = (data["teachers"] as List<*>).map { decodeTeacherItem(it) },
            weekDayIsoDates = (data["weekDayIsoDates"] as List<*>).map { it as String },
            lessonsByDate = (data["lessonsByDate"] as Map<*, *>).entries.associate { (k, v) -> k as String to (v as List<*>).map { decodeLessonItem(it) } },
            successfulSyncMillis = (data["successfulSyncMillis"] as Map<*, *>).entries.associate { (k, v) -> k as String to (v as Number).toLong() },
            stale = data["stale"] as Boolean,
            sem1AbsenceSummary = data["sem1AbsenceSummary"]?.let { decodeAbsenceSummary(it) },
            sem2AbsenceSummary = data["sem2AbsenceSummary"]?.let { decodeAbsenceSummary(it) },
        )
    }
}
