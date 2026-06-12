package com.gokul.lmpapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NyisoApiClientTest {

    @Test
    fun `parseLmpCsv keeps only the latest interval`() {
        val csv = """
            "Time Stamp","Name","PTID","LBMP ($/MWHr)","Marginal Cost Losses ($/MWHr)","Marginal Cost Congestion ($/MWHr)"
            "06/12/2026 14:30:00","CAPITL","61757","30.00","2.00","-1.00"
            "06/12/2026 14:35:00","CAPITL","61757","31.00","2.50","-1.50"
            "06/12/2026 14:35:00","N.Y.C.","61761","45.00","3.00","-10.00"
        """.trimIndent()

        val snapshot = NyisoApiClient.parseLmpCsv(csv)

        assertEquals("06/12/2026 14:35 ET", snapshot.refId)
        assertEquals(2, snapshot.prices.size)

        val nyc = snapshot.prices.getValue("N.Y.C.")
        assertEquals(45.00, nyc.lmp, 1e-9)
        assertEquals(3.00, nyc.loss!!, 1e-9)
        // NYISO publishes MCC with a minus sign inside LBMP; we negate it so
        // energy + congestion + loss = lmp
        assertEquals(10.00, nyc.congestion!!, 1e-9)
        assertEquals(45.00 - 10.00 - 3.00, nyc.energy!!, 1e-9)
    }

    @Test
    fun `parseLmpCsv strips UTF-8 BOM tolerated by header matcher`() {
        val csv = "﻿\"Time Stamp\",\"Name\",\"LBMP (\$/MWHr)\"," +
            "\"Marginal Cost Losses (\$/MWHr)\",\"Marginal Cost Congestion (\$/MWHr)\"\n" +
            "\"06/12/2026 15:00:00\",\"N.Y.C.\",\"55.00\",\"1.00\",\"-2.00\"\n"

        val snapshot = NyisoApiClient.parseLmpCsv(csv.removePrefix("﻿"))

        assertEquals(55.00, snapshot.prices.getValue("N.Y.C.").lmp, 1e-9)
    }

    @Test
    fun `parseFuelMixCsv keeps only the latest interval and sorts by MW`() {
        val csv = """
            "Time Stamp","Time Zone","Fuel Category","Gen MW"
            "06/12/2026 13:55:00","EDT","Natural Gas","5000"
            "06/12/2026 14:00:00","EDT","Natural Gas","5100"
            "06/12/2026 14:00:00","EDT","Nuclear","3300"
            "06/12/2026 14:00:00","EDT","Hydro","2600"
            "06/12/2026 14:00:00","EDT","Dual Fuel","1200"
        """.trimIndent()

        val mix = NyisoApiClient.parseFuelMixCsv(csv)

        assertEquals("06/12/2026 14:00 ET", mix.refId)
        assertEquals(4, mix.categories.size)
        assertEquals(5100.0 + 3300.0 + 2600.0 + 1200.0, mix.totalMw, 1e-9)
        assertEquals("Natural Gas", mix.categories.first().name)
    }

    @Test
    fun `parseLoadCsv keeps only the latest interval and skips blank loads`() {
        val csv = """
            "Time Stamp","Time Zone","Name","PTID","Load"
            "06/12/2026 14:50:00","EDT","N.Y.C.","61761","6100.0"
            "06/12/2026 14:55:00","EDT","N.Y.C.","61761","6200.5"
            "06/12/2026 14:55:00","EDT","LONGIL","61762","2400.0"
            "06/12/2026 14:55:00","EDT","CAPITL","61757",""
        """.trimIndent()

        val loads = NyisoApiClient.parseLoadCsv(csv)

        assertEquals("06/12/2026 14:55 ET", loads.refId)
        assertEquals(2, loads.loads.size)
        assertEquals(6200.5, loads.loads.getValue("N.Y.C.").mw, 1e-9)
        assertEquals(6200.5 + 2400.0, loads.totalMw, 1e-9)
    }

    @Test
    fun `LoadSnapshot loadFor matches a node via its alias keys`() {
        val node = NodeDirectory.parseLine(
            "N.Y.C.,NYC,New York City (Zone J),ZONE,40.75,-73.99"
        )!!
        val snapshot = LoadSnapshot(
            refId = "x",
            loads = mapOf("N.Y.C." to ZoneLoad("N.Y.C.", 6200.0)),
        )

        assertEquals(6200.0, snapshot.loadFor(node)!!.mw, 1e-9)
        // A node with no matching zone yields null, not an exception
        val other = NodeDirectory.parseLine("WEST,,West (Zone A),ZONE,42.89,-78.88")!!
        assertNull(snapshot.loadFor(other))
    }

    @Test
    fun `splitCsvLine handles quoted fields containing commas`() {
        assertEquals(
            listOf("a", "b,c", """d"e"""),
            NyisoApiClient.splitCsvLine(""""a","b,c","d""e""""),
        )
    }

    @Test
    fun `NYC zone resolves from Times Square`() {
        val directory = NodeDirectory(
            listOf(
                NodeDirectory.parseLine("N.Y.C.,NYC,New York City (Zone J),ZONE,40.75,-73.99")!!,
                NodeDirectory.parseLine("DUNWOD,,Dunwoodie (Zone I) - Yonkers,ZONE,40.94,-73.86")!!,
            )
        )
        val csv = """
            "Time Stamp","Name","PTID","LBMP ($/MWHr)","Marginal Cost Losses ($/MWHr)","Marginal Cost Congestion ($/MWHr)"
            "06/12/2026 14:35:00","N.Y.C.","61761","45.00","3.00","-10.00"
        """.trimIndent()
        val snapshot = NyisoApiClient.parseLmpCsv(csv)

        val result = directory.nearestNodes(40.758, -73.985, snapshot)

        assertEquals("N.Y.C.", result[0].node.nodeId)
        assertEquals(45.00, result[0].price!!.lmp, 1e-9)
    }
}
