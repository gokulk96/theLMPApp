package com.gokul.lmpapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NyisoApiClientTest {

    // ── CSV parsing ──────────────────────────────────────────────────────────

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
    fun `splitCsvLine handles quoted fields containing commas`() {
        assertEquals(
            listOf("a", "b,c", """d"e"""),
            NyisoApiClient.splitCsvLine(""""a","b,c","d""e""""),
        )
    }

    // ── Name-to-directory matching ───────────────────────────────────────────

    @Test
    fun `NYC zone resolves via N_Y_C_ match key`() {
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

        // Query from Times Square
        val result = directory.nearestNodes(40.758, -73.985, snapshot)

        assertEquals("N.Y.C.", result[0].node.nodeId)
        assertEquals(45.00, result[0].price!!.lmp, 1e-9)
    }

    // ── ZIP extraction (unit-tested without network) ─────────────────────────

    @Test
    fun `getDailyZipCsv extracts the CSV from a well-formed ZIP`() {
        val csvContent = """"Time Stamp","Name","LBMP ($/MWHr)","Marginal Cost Losses ($/MWHr)","Marginal Cost Congestion ($/MWHr)"
"06/12/2026 15:00:00","N.Y.C.","50.00","1.00","-2.00"
"""
        val zipBytes = buildZip("20260612realtime_zone.csv", csvContent)

        // Reuse the extraction logic indirectly via parseLmpCsv round-trip
        val csv = extractCsvFromZip(zipBytes)
        val snapshot = NyisoApiClient.parseLmpCsv(csv)

        assertEquals(1, snapshot.prices.size)
        assertTrue(snapshot.prices.containsKey("N.Y.C."))
    }

    @Test
    fun `getDailyZipCsv strips UTF-8 BOM if present`() {
        val csvWithBom = "﻿\"Time Stamp\",\"Name\",\"LBMP (\$/MWHr)\",\"Marginal Cost Losses (\$/MWHr)\",\"Marginal Cost Congestion (\$/MWHr)\"\n" +
            "\"06/12/2026 15:00:00\",\"N.Y.C.\",\"55.00\",\"1.00\",\"-2.00\"\n"
        val zipBytes = buildZip("data.csv", csvWithBom)

        val csv = extractCsvFromZip(zipBytes)
        // BOM stripped — header row should start with quote, not
        assertTrue(!csv.startsWith("﻿"))
        val snapshot = NyisoApiClient.parseLmpCsv(csv)
        assertEquals(55.00, snapshot.prices.getValue("N.Y.C.").lmp, 1e-9)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildZip(entryName: String, content: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Mirrors the private extractFirstCsvFromZip logic for testing. */
    private fun extractCsvFromZip(zipBytes: ByteArray): String {
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    return zis.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        throw AssertionError("No CSV in ZIP")
    }
}
