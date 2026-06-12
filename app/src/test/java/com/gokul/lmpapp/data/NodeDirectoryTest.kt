package com.gokul.lmpapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeDirectoryTest {

    @Test
    fun `parses a CSV line with aliases`() {
        val node = NodeDirectory.parseLine(
            "ALTW,ALTW.LZ|ALTW.ALTW,ITC Midwest (Alliant West) - Iowa,ZONE,41.98,-91.67"
        )!!

        assertEquals("ALTW", node.nodeId)
        assertEquals(listOf("ALTW.LZ", "ALTW.ALTW"), node.aliases)
        assertEquals(NodeType.ZONE, node.type)
        assertEquals(setOf("ALTW", "ALTW.LZ", "ALTW.ALTW"), node.matchKeys)
    }

    @Test
    fun `skips comments and blank lines`() {
        assertNull(NodeDirectory.parseLine("# a comment"))
        assertNull(NodeDirectory.parseLine("   "))
    }

    @Test
    fun `haversine distance Minneapolis to Duluth is about 220 km`() {
        val km = NodeDirectory.haversineKm(44.98, -93.27, 46.79, -92.10)
        assertEquals(220.0, km, 15.0)
    }

    @Test
    fun `nearestNodes sorts by distance and joins prices via alias`() {
        val directory = NodeDirectory(
            listOf(
                NodeDirectory.parseLine("MINN.HUB,,Minnesota Hub,HUB,44.98,-93.27")!!,
                NodeDirectory.parseLine("MP,MP.LZ,Minnesota Power - Duluth,ZONE,46.79,-92.10")!!,
            )
        )
        val snapshot = LmpSnapshot(
            refId = "test",
            prices = mapOf(
                "MP.LZ" to NodePrice("MP.LZ", 21.0, 0.0, -0.5),
            ),
        )

        // Query from a point near Duluth
        val result = directory.nearestNodes(46.7, -92.2, snapshot)

        assertEquals("MP", result[0].node.nodeId)
        assertEquals(21.0, result[0].price!!.lmp, 1e-9)
        assertEquals("MINN.HUB", result[1].node.nodeId)
        assertNull(result[1].price)
    }
}
