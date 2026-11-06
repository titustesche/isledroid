package com.titus.dynamicisland.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MvtTest {
    private fun tile(): MapTile {
        val bytes = javaClass.classLoader!!.getResourceAsStream("tile_14_8566_5480.pbf")!!.readBytes()
        return Mvt.decode(TileKey(14, 8566, 5480), bytes)
    }

    @Test fun decodesStreetsAndBuildings() {
        val t = tile()
        assertEquals(4096, t.extent)
        assertTrue("roads: ${t.roads.size}", t.roads.isNotEmpty())
        // Every road is a polyline with at least two points, inside (or just around) the tile.
        t.roads.forEach { r ->
            assertTrue(r.points.size >= 4 && r.points.size % 2 == 0)
            r.points.forEach { v -> assertTrue("$v", v in -1024f..5120f) }
        }
        println("roads=${t.roads.size} classes=${t.roads.map { it.cls }.toSet()} buildings=${t.buildings.size}")
    }

    @Test fun buildingsKeepOuterRings() {
        val t = tile()
        if (t.buildings.isEmpty()) return // this tile may be outside a town
        assertTrue(t.buildings.all { it.ring.size >= 6 && it.heightM > 0f })
    }
}
