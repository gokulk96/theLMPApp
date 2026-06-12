package com.gokul.lmpapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NyisoApiClientTest {

    @Test
    fun `parses zonal LBMP CSV keeping only the latest interval`() {
        val csv = """
            "Time Stamp","Name","PTID","LBMP (${'$'}/MWHr)","Marginal Cost Losses (${'$'}/MWHr)","Marginal Cost Congestion (${'$'}/MWHr)"
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
        // NYISO publishes LBMP = energy + losses - congestion; the component
        // is negated so the additive identity holds
        assertEquals(10.00, nyc.congestion!!, 1e-9)
        assertEquals(45.00 - 10.00 - 3.00, nyc.energy!!, 1e-9)
    }

    @Test
    fun `parses fuel mix CSV keeping only the latest interval`() {
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
    fun `splits quoted CSV fields containing commas`() {
        assertEquals(
            listOf("a", "b,c", """d"e"""),
            NyisoApiClient.splitCsvLine(""""a","b,c","d""e""""),
        )
    }

    @Test
    fun `NYC zone matches the bundled NYISO directory`() {
        val directory = NodeDirectory(
            listOf(
                NodeDirectory.parseLine("N.Y.C.,NYC,New York City (Zone J),ZONE,40.75,-73.99")!!,
                NodeDirectory.parseLine("DUNWOD,,Dunwoodie (Zone I) - Yonkers,ZONE,40.94,-73.86")!!,
            )
        )
        val csv = """
            "Time Stamp","Name","PTID","LBMP (${'$'}/MWHr)","Marginal Cost Losses (${'$'}/MWHr)","Marginal Cost Congestion (${'$'}/MWHr)"
            "06/12/2026 14:35:00","N.Y.C.","61761","45.00","3.00","-10.00"
        """.trimIndent()
        val snapshot = NyisoApiClient.parseLmpCsv(csv)

        // Query from Times Square
        val result = directory.nearestNodes(40.758, -73.985, snapshot)

        assertEquals("N.Y.C.", result[0].node.nodeId)
        assertEquals(45.00, result[0].price!!.lmp, 1e-9)
    }
}
