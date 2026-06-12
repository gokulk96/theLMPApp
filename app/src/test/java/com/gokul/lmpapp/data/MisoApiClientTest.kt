package com.gokul.lmpapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MisoApiClientTest {

    @Test
    fun `parses consolidated LMP table`() {
        val body = """
            {"LMPData":{"RefId":"12-Jun-2026 - Interval 14:35 EST",
             "FiveMinLMP":{"PricingNode":[
               {"name":"MINN.HUB","LMP":"24.51","loss":"-0.62","congestion":"1.10"},
               {"name":"ALTW","LMP":"22.03","loss":"-1.10","congestion":"0"}
             ]}}}
        """.trimIndent()

        val snapshot = MisoApiClient.parseLmpSnapshot(body)

        assertEquals("12-Jun-2026 - Interval 14:35 EST", snapshot.refId)
        assertEquals(2, snapshot.prices.size)
        val hub = snapshot.prices.getValue("MINN.HUB")
        assertEquals(24.51, hub.lmp, 1e-9)
        assertEquals(1.10, hub.congestion!!, 1e-9)
        assertEquals(-0.62, hub.loss!!, 1e-9)
        assertEquals(24.51 - 1.10 - (-0.62), hub.energy!!, 1e-9)
    }

    @Test
    fun `parses LMP rows with MCC and MLC key names`() {
        val body = """
            {"LMPData":{"RefId":"x","FiveMinLMP":{"PricingNode":
              {"name":"ILLINOIS.HUB","LMP":"30.00","MLC":"-0.50","MCC":"2.00"}
            }}}
        """.trimIndent()

        val snapshot = MisoApiClient.parseLmpSnapshot(body)

        val hub = snapshot.prices.getValue("ILLINOIS.HUB")
        assertEquals(2.00, hub.congestion!!, 1e-9)
        assertEquals(-0.50, hub.loss!!, 1e-9)
    }

    @Test
    fun `missing components yield null energy`() {
        val body = """
            {"LMPData":{"FiveMinLMP":{"PricingNode":[{"name":"N1","LMP":"10"}]}}}
        """.trimIndent()

        val price = MisoApiClient.parseLmpSnapshot(body).prices.getValue("N1")

        assertNull(price.congestion)
        assertNull(price.energy)
    }

    @Test
    fun `parses fuel mix and sorts by MW descending`() {
        val body = """
            {"RefId":"12-Jun-2026 - Interval 14:00 EST","TotalMW":"70000",
             "Fuel":{"Type":[
               {"CATEGORY":"Wind","ACT":"15000"},
               {"CATEGORY":"Natural Gas","ACT":"30000"},
               {"CATEGORY":"Coal","ACT":"20000"},
               {"CATEGORY":"Solar","ACT":"5000"}
             ]}}
        """.trimIndent()

        val mix = MisoApiClient.parseFuelMix(body)

        assertEquals(70000.0, mix.totalMw, 1e-9)
        assertEquals(
            listOf("Natural Gas", "Coal", "Wind", "Solar"),
            mix.categories.map { it.name },
        )
        assertEquals(30000.0 / 70000.0, mix.share(mix.categories.first()), 1e-9)
    }

    @Test
    fun `fuel mix total falls back to category sum`() {
        val body = """
            {"Fuel":{"Type":[
              {"CATEGORY":"Wind","ACT":"1,500"},
              {"CATEGORY":"Coal","ACT":"500"}
            ]}}
        """.trimIndent()

        val mix = MisoApiClient.parseFuelMix(body)

        assertEquals(2000.0, mix.totalMw, 1e-9)
    }
}
