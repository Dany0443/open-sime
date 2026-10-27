package md.sime.opensime.core.portal

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * `Europe/Chisinau` date, time, semester, and calendar timestamp utilities.
 *
 * Verified source behavior ported from `web/server.js:153-211`, `290-319`, `931-947`, and `1031-1039`.
 */
object ChisinauTime {
    const val TZ_ID: String = "Europe/Chisinau"
    val CHISINAU_ZONE: ZoneId = ZoneId.of(TZ_ID)

    private val HM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    private val ISO_DATE_RE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val DATE_RE = Regex("""(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2,4}))?""")
    private val FULL_DATE_RE = Regex("""\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|\d{4}-\d{2}-\d{2}""")
    private val EVENT_TIME_RE =
        Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?(Z|[+-]\d{2}(?::?\d{2})?)?$""")

    /**
     * Formats [epochMillis] as `YYYY-MM-DD` in `Europe/Chisinau`.
     * Mirrors `chisinauDate(ms)` in `web/server.js:183-186`.
     */
    fun chisinauDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(CHISINAU_ZONE)
            .toLocalDate()
            .toString()

    /**
     * Formats [epochMillis] as 24-hour `HH:mm` in `Europe/Chisinau`.
     * Mirrors `chisinauHm(ms)` in `web/server.js:187-190`.
     */
    fun chisinauHm(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(CHISINAU_ZONE)
            .toLocalTime()
            .format(HM_FORMATTER)

    /**
     * Returns today's date (`YYYY-MM-DD`) in `Europe/Chisinau`.
     * Mirrors `todayChisinau()` in `web/server.js:191`.
     */
    fun todayChisinau(nowEpochMillis: Long = System.currentTimeMillis()): String =
        chisinauDate(nowEpochMillis)

    /**
     * Converts a naive `Europe/Chisinau` local date-time into UTC epoch milliseconds.
     * Mirrors `localToEpoch(y, mo, d, h, mi, s)` in `web/server.js:177-181`.
     */
    fun localToEpochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
    ): Long {
        val ldt = LocalDateTime.of(year, month, day, hour, minute, second)
        return ldt.atZone(CHISINAU_ZONE).toInstant().toEpochMilli()
    }

    /**
     * Returns an ISO date string (`YYYY-MM-DD`) or `null` when the components do not form a real calendar date.
     * Mirrors `makeDate(y, m, d)` in `web/server.js:197-203`.
     */
    fun makeDate(year: Int, month: Int, day: Int): String? {
        if (year < 1 || month !in 1..12 || day !in 1..31) return null
        return try {
            val ld = LocalDate.of(year, month, day)
            String.format(Locale.ROOT, "%04d-%02d-%02d", ld.year, ld.monthValue, ld.dayOfMonth)
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Adds [days] to an ISO `YYYY-MM-DD` date string.
     * Mirrors `addDays(iso, n)` in `web/server.js:206`.
     */
    fun addDays(isoDate: String, days: Int): String =
        LocalDate.parse(isoDate).plusDays(days.toLong()).toString()

    /**
     * Returns the Monday ISO date (`YYYY-MM-DD`) of the week containing [isoDate].
     * Mirrors `weekStart(iso)` in `web/server.js:208-211`.
     */
    fun weekStart(isoDate: String): String =
        LocalDate.parse(isoDate)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toString()

    /**
     * Parses a portal date (`YYYY-MM-DD`, `DD.MM.YYYY`, `DD.MM.YY`, or yearless `DD.MM`)
     * into `YYYY-MM-DD`. When no year is present, picks the closest plausible year never more
     * than 7 days ahead of [todayIso].
     *
     * Mirrors `parseDate(text, today)` in `web/server.js:290-309`.
     */
    fun parseDate(text: String?, todayIso: String = todayChisinau()): String? {
        if (text.isNullOrBlank()) return null

        val isoMatch = ISO_DATE_RE.find(text)
        if (isoMatch != null) {
            val y = isoMatch.groupValues[1].toIntOrNull() ?: return null
            val m = isoMatch.groupValues[2].toIntOrNull() ?: return null
            val d = isoMatch.groupValues[3].toIntOrNull() ?: return null
            return makeDate(y, m, d)
        }

        val dmyMatch = DATE_RE.find(text) ?: return null
        val day = dmyMatch.groupValues[1].toIntOrNull() ?: return null
        val month = dmyMatch.groupValues[2].toIntOrNull() ?: return null
        val rawYear = dmyMatch.groupValues[3]
        if (rawYear.isNotEmpty()) {
            var year = rawYear.toIntOrNull() ?: return null
            if (year < 100) year += 2000
            return makeDate(year, month, day)
        }

        val todayDate = try {
            LocalDate.parse(todayIso)
        } catch (_: DateTimeException) {
            return null
        }
        val currentYear = todayDate.year
        val candidateIso = makeDate(currentYear, month, day) ?: return null
        val candidateDate = LocalDate.parse(candidateIso)
        return if (candidateDate.isAfter(todayDate.plusDays(7))) {
            makeDate(currentYear - 1, month, day)
        } else {
            candidateIso
        }
    }

    /**
     * Accepts only strings that carry a full date with year (`DD.MM.YYYY` or `YYYY-MM-DD`),
     * so a decimal mark like `"9.5"` is never misread as May 9.
     *
     * Mirrors `parseFullDate(text)` in `web/server.js:311-314`.
     */
    fun parseFullDate(text: String?): String? =
        if (!text.isNullOrBlank() && FULL_DATE_RE.containsMatchIn(text)) {
            parseDate(text)
        } else {
            null
        }

    /**
     * Parses a FullCalendar event timestamp into epoch milliseconds; naive timestamps without
     * a timezone offset are interpreted in `Europe/Chisinau`.
     *
     * Mirrors `parseEventTime(raw)` in `web/server.js:934-947`.
     */
    fun parseEventTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = EVENT_TIME_RE.matchEntire(raw.trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val minute = match.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val second = match.groupValues[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

        if (makeDate(year, month, day) == null || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }

        val tzToken = match.groupValues[7]
        if (tzToken.isEmpty()) {
            return localToEpochMillis(year, month, day, hour, minute, second)
        }
        val ldt = LocalDateTime.of(year, month, day, hour, minute, second)
        if (tzToken == "Z") {
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        val sign = if (tzToken.startsWith("-")) -1 else 1
        val digits = tzToken.substring(1).replace(":", "")
        val offsetHours = digits.substring(0, 2).toIntOrNull() ?: return null
        val offsetMinutes = if (digits.length >= 4) digits.substring(2, 4).toIntOrNull() ?: 0 else 0
        val totalOffsetSeconds = sign * (offsetHours * 3600 + offsetMinutes * 60)
        val utcMillis = ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
        return utcMillis - totalOffsetSeconds * 1000L
    }

    /**
     * Infers the semester (`1` for Sep–Dec, `2` for Jan–Aug) from an ISO date or returns [fallback].
     * Mirrors `inferSemester(isoDay, fallback)` in `web/server.js:316-319`.
     */
    fun inferSemester(isoDay: String?, fallback: Int? = null): Int? {
        if (isoDay.isNullOrBlank() || isoDay.length < 7) return fallback
        val month = isoDay.substring(5, 7).toIntOrNull() ?: return fallback
        return if (month >= 9) 1 else 2
    }

    /**
     * Infers semester for an absence record (`1` for Sep–Dec, `2` for Jan–Aug), preferring [explicitSemester]
     * when it is `1` or `2`.
     * Mirrors `inferAbsenceSemester(isoDay, explicitSemester)` in `web/server.js:1031-1039`.
     */
    fun inferAbsenceSemester(isoDay: String?, explicitSemester: Int? = null): Int {
        if (explicitSemester == 1 || explicitSemester == 2) return explicitSemester
        if (isoDay.isNullOrBlank() || isoDay.length < 7) return 1
        val month = isoDay.substring(5, 7).toIntOrNull() ?: return 1
        return when (month) {
            in 9..12 -> 1
            in 1..8 -> 2
            else -> 1
        }
    }
}

