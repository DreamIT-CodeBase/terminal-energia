package com.terminalscout.app.prod.capture

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationMapDeviceTest {

    private val databaseName = "location_map_instrumentation_test.db"
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val context: Context = object : ContextWrapper(
        targetContext
    ) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File {
            require(name == "location_map.db")
            return baseContext.getDatabasePath(databaseName)
        }

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase {
            require(name == "location_map.db")
            return baseContext.openOrCreateDatabase(databaseName, mode, factory)
        }
    }

    @Before
    fun setUp() {
        assertNotEquals(targetContext.getDatabasePath("location_map.db"), context.getDatabasePath("location_map.db"))
        targetContext.deleteDatabase(databaseName)
        LocationMap.invalidate()
    }

    @After
    fun tearDown() {
        LocationMap.invalidate()
        targetContext.deleteDatabase(databaseName)
    }

    private fun station(id: String, name: String = id) =
        FuelStation(id, name, 58.4, 26.7, brand = "Olerex")

    @Test
    fun successfulSweepRemovesMissingIdsAndPreservesHistoryForReturnedIds() {
        assertEquals(
            LocationMap.UpsertResult(3, 0, 0),
            LocationMap.upsertAll(
                context,
                listOf(station("keep", "Old"), station("remove"), station("surveyed:old")),
                1000L
            )
        )
        LocationMap.markCaptured(context, "keep", 777L)
        LocationMap.markCaptured(context, "remove", 888L)
        LocationMap.stations(context)

        assertEquals(
            LocationMap.UpsertResult(1, 1, 2),
            LocationMap.upsertAll(context, listOf(station("keep", "New"), station("added")), 1000L)
        )
        val byId = LocationMap.stations(context).associateBy { it.id }
        assertEquals(setOf("keep", "added"), byId.keys)
        assertEquals("New", byId.getValue("keep").name)
        assertEquals(777L, byId.getValue("keep").lastCaptureTimeMs)
        assertNull(byId.getValue("added").lastCaptureTimeMs)
    }

    @Test
    fun emptySweepCannotEraseTheStoredMap() {
        LocationMap.upsertAll(context, listOf(station("keep")), 1000L)

        assertNull(LocationMap.upsertAll(context, emptyList(), 2000L))
        assertEquals(listOf("keep"), LocationMap.stations(context).map { it.id })
    }

    @Test
    fun sweepWithMoreThanSqliteBindLimitStillPrunesMissingIds() {
        val served = (0..1000).map { station("station-$it") }
        LocationMap.upsertAll(context, served + station("removed"), 1000L)

        assertEquals(
            LocationMap.UpsertResult(0, served.size, 1),
            LocationMap.upsertAll(context, served, 1000L)
        )
        assertEquals(served.size, LocationMap.count(context))
    }
}
