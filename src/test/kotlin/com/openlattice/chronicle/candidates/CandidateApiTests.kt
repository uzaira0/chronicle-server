package com.openlattice.chronicle.candidates

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.ids.IdConstants
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CandidateApiTests : ChronicleServerTests() {

    private var c1: Candidate? = null
    private var c2: Candidate? = null

    @Before
    fun beforeEachTest() {
        c1 = Candidate()
        c2 = Candidate()
    }

    @After
    fun afterEachTest() {
        c1 = null
        c2 = null
    }

    @Test
    fun testRegisterCandidate() {
        val id = clientUser1.candidateApi.registerCandidate(c1!!)
        Assert.assertNotEquals(IdConstants.UNINITIALIZED.id, id)
    }

    @Test
    fun testRegisterExistingCandidate() {
        try {
            val id = clientUser1.candidateApi.registerCandidate(c1!!)
            c1!!.id = id
            clientUser1.candidateApi.registerCandidate(c1!!)
            Assert.fail()
        }
        catch (e: RhizomeRetrofitCallException) {
            Assert.assertTrue(
                "should fail with expected error message",
                e.body.contains("cannot register candidate with the given id")
            )
        }
    }

    @Test
    fun testRegisterCandidateWithRandomId() {
        try {
            c1!!.id = UUID.randomUUID()
            clientUser1.candidateApi.registerCandidate(c1!!)
            Assert.fail()
        }
        catch (e: RhizomeRetrofitCallException) {
            Assert.assertTrue(
                "should fail with expected error message",
                e.body.contains("cannot register candidate with the given id")
            )
        }
    }
}
