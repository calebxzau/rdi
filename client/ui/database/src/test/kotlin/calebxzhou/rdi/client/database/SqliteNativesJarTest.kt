package calebxzhou.rdi.client.database

import org.sqlite.SQLiteJDBCLoader
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteNativesJarTest {
    @Test
    fun `sqlite native library loads from the platform natives jar`() {
        val resource = javaClass.classLoader.getResource("org/sqlite/native/Windows/x86_64/sqlitejdbc.dll")
        assertNotNull(resource, "sqlite原生库不在classpath上")
        assertTrue(
            resource.toString().contains("rdi-sqlite-natives-windows-x64.jar"),
            "sqlite原生库应来自rdi-sqlite-natives-windows-x64.jar，实际来自$resource"
        )

        val version = DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select sqlite_version()").use { result ->
                    assertTrue(result.next())
                    result.getString(1)
                }
            }
        }
        assertTrue(version.isNotBlank())
        assertTrue(SQLiteJDBCLoader.isNativeMode())
    }
}
