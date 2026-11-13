package md.sime.opensime.core.portal

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class GradeStructureDiagnosticTest {
    @Test fun populatedFixtureReportsOnlyStructuralFacts() {
        val html = checkNotNull(javaClass.getResource("/fixtures/portal/report_sem1_marks_theses_qualifiers.html")).readText()
        val structure = DebugGradeSchemaInspector.inspectStructure(Jsoup.parse(html))
        assertTrue(structure.tablePresent)
        assertTrue(structure.subjectColumnPresent)
        assertTrue(structure.evaluationColumnPresent)
        assertEquals(3, structure.parsedRows)
        assertTrue(structure.allSubjectRowsHaveEvaluationCell)
        assertFalse(structure.duplicateSubjectRowHint)
        assertFalse(structure.paginationHint)
    }

    @Test fun unrecognizedAndPaginationHintsRemainDistinguishable() {
        val unknown = DebugGradeSchemaInspector.inspectStructure(Jsoup.parse("<div>Unknown</div>"))
        assertFalse(unknown.tablePresent)
        assertEquals(0, unknown.parsedRows)
        val possiblePagination = DebugGradeSchemaInspector.inspectStructure(Jsoup.parse("<table></table><div class='pagination'></div>"))
        assertTrue(possiblePagination.tablePresent)
        assertEquals(0, possiblePagination.parsedRows)
        assertTrue(possiblePagination.paginationHint)
    }

    @Test fun alternateHeadersAndNextLinksAreOnlyStructuralHints() {
        val root = Jsoup.parse("<table><tr><td headers='NOTE'><span title='example'>x</span></td></tr></table><a rel='next' aria-disabled='true'>next</a>")
        val structure = DebugGradeSchemaInspector.inspectStructure(root)
        assertTrue(structure.alternateEvaluationHeaderHint)
        assertTrue(structure.evaluationColumnPresent)
        assertTrue(structure.datedSpanHint)
        assertTrue(structure.nextLinkHint)
        assertFalse(structure.nextLinkNotMarkedDisabledHint)
    }

    @Test fun diagnosticMatchesParserForPrefixedHeaders() {
        val root = Jsoup.parse("<table><tr><td headers='REGION_DISCIPLINA'>Synthetic</td><td headers='REGION_LISTA_EVALUATION'>8</td></tr></table>")
        val structure = DebugGradeSchemaInspector.inspectStructure(root)
        assertTrue(structure.subjectColumnPresent)
        assertTrue(structure.evaluationColumnPresent)
        assertEquals(1, structure.parsedRows)
    }

    @Test fun paginationControlsAreReportedWithoutAssumingMorePages() {
        val root = Jsoup.parse("<div class='pagination'><button disabled>next</button></div>")
        val structure = DebugGradeSchemaInspector.inspectStructure(root)
        assertTrue(structure.paginationControlHint)
        assertFalse(structure.paginationControlNotMarkedDisabledHint)
    }

    @Test fun partialRowsAndPaginationRangeAreOnlyHints() {
        val root = Jsoup.parse("<table><tr><td headers='DISCIPLINA'>A</td><td headers='NOTE'>8</td></tr><tr><td headers='DISCIPLINA'>A</td></tr></table><div class='pagination'>1 - 10 of 20</div>")
        val structure = DebugGradeSchemaInspector.inspectStructure(root)
        assertFalse(structure.allSubjectRowsHaveEvaluationCell)
        assertTrue(structure.duplicateSubjectRowHint)
        assertTrue(structure.paginationRangeTextHint)
    }
}
