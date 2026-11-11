package io.github.jackwener.dsh.core.workbench

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.model.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class HualuoToolsTest {
    private val tools = HualuoTools(SecretStore(ApplicationProvider.getApplicationContext<Context>())).tools.associateBy { it.name }

    @Test fun `github writes use dsh risk approval levels`() {
        assertEquals(Risk.read, tools.getValue("github_read_file").descriptor.risk)
        assertEquals(Risk.write, tools.getValue("github_update_file").descriptor.risk)
        assertEquals(Risk.write, tools.getValue("github_create_pull_request").descriptor.risk)
        assertEquals(Risk.dangerous, tools.getValue("github_merge_pull_request").descriptor.risk)
        assertEquals(Risk.write, tools.getValue("github_create_repo").descriptor.risk)
        assertEquals(Risk.write, tools.getValue("github_upload_files").descriptor.risk)
        assertFalse(tools.getValue("github_update_file").description.contains("App 会弹确认卡"))
    }

    @Test fun `observe tools are a read only whitelist`() {
        assertTrue(tools.containsKey("uma_health"))
        assertTrue(tools.containsKey("uma_ramen_planner_state"))
        assertFalse(tools.containsKey("uma_read_endpoint"))
        tools.filterKeys { it.startsWith("uma_") }.forEach { (name, tool) ->
            assertEquals(name, Risk.read, tool.descriptor.risk)
        }
    }
}