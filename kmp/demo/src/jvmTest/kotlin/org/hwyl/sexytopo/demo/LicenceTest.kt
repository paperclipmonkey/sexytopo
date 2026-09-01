package org.hwyl.sexytopo.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The curated licence list `TripDetailsDialog` offers, ported from `model.survey.Licence`. Free
 * text is still accepted beside these - see [Licence.forName] returning null for anything not in
 * the list - which is the whole reason a trip stores its licence as plain text rather than one of
 * these.
 */
class LicenceTest {

    @Test
    fun aCuratedNameResolvesBackToItsLicence() {
        assertEquals(Licence.CC_BY_SA_4, Licence.forName("CC BY-SA 4.0"))
    }

    @Test
    fun freeTextIsNotOneOfTheDefaults() {
        assertNull(Licence.forName("my own words"))
        assertNull(Licence.forName(""))
    }

    @Test
    fun theRecommendedLicenceIsCopyleftAndFree() {
        assertEquals(Licence.GPL_3_PLUS, Licence.RECOMMENDED)
        assertTrue(Licence.RECOMMENDED.isFree)
    }

    @Test
    fun allRightsReservedIsTheOnlyRestrictiveOne() {
        val restrictive = Licence.entries.filterNot { it.isFree }
        assertEquals(listOf(Licence.ALL_RIGHTS_RESERVED), restrictive)
        assertFalse(Licence.ALL_RIGHTS_RESERVED.hasUrl, "there is no canonical page for this one")
    }

    @Test
    fun everyFreeLicenceHasACanonicalPageToLinkTo() {
        for (licence in Licence.entries.filter { it.isFree }) {
            assertTrue(licence.hasUrl, "${licence.licenceName} should link to its own terms")
        }
    }
}
