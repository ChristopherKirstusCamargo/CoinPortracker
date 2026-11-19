package com.coinportracker

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coinportracker.database.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Banco v1 → v2 → v3 pelas AutoMigrations e validação contra os esquemas exportados. */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To3() {
        helper.createDatabase("mig", 1).apply {
            execSQL("INSERT INTO wallets(id, name, type, createdAt, archived) VALUES (1, 'Principal', 'EXCHANGE', 0, 0)")
            close()
        }
        helper.runMigrationsAndValidate("mig", 2, true)
        helper.runMigrationsAndValidate("mig", 3, true)
    }
}
