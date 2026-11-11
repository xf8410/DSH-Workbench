package io.github.jackwener.dsh.feature.workbench

import io.github.jackwener.dsh.core.model.Risk
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkbenchViewModelTest {
    @Test fun blankArgsBecomeAnEmptyObject() {
        assertEquals(0, parseWorkbenchArgs("").size)
        assertEquals(0, parseWorkbenchArgs(" \n\t").size)
    }

    @Test fun nonObjectArgsAreRejectedBeforeToolInvocation() {
        listOf("[]", "null", "\"repo\"", "42").forEach { raw ->
            try {
                parseWorkbenchArgs(raw)
                fail("Expected non-object JSON to be rejected: $raw")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("JSON 对象"))
            }
        }
    }

    @Test fun repositoryDefaultsFollowTheEditedOwnerAndRepo() {
        val userRepos = defaultWorkbenchArgs("github_list_user_repos", "someone/project")
        assertEquals("someone", userRepos["owner"]?.jsonPrimitive?.contentOrNull)

        val readFile = defaultWorkbenchArgs("github_read_file", "someone/project")
        assertEquals("someone/project", readFile["repo"]?.jsonPrimitive?.contentOrNull)
        assertEquals("app/build.gradle.kts", readFile["path"]?.jsonPrimitive?.contentOrNull)

        val search = defaultWorkbenchArgs("github_search_code", "someone/project")
        assertEquals("someone/project", search["repo"]?.jsonPrimitive?.contentOrNull)
        assertEquals("CapabilityHub", search["keyword"]?.jsonPrimitive?.contentOrNull)
    }

    @Test fun createRepoAndUploadDefaultsAreReadyToEdit() {
        val create = defaultWorkbenchArgs("github_create_repo", "someone/project")
        assertEquals("new-repository", create["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, create["private"]?.jsonPrimitive?.booleanOrNull)

        val upload = defaultWorkbenchArgs("github_upload_files", "someone/project")
        assertEquals("someone/project", upload["repo"]?.jsonPrimitive?.contentOrNull)
        assertEquals("courier/", upload["prefix"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "/sdcard/Download/example.apk",
            upload["files"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test fun observationDefaultsBoundTheReturnedText() {
        val args = defaultWorkbenchArgs("uma_get_snapshot", "unused/repository")
        assertEquals(12_000, args["max_chars"]?.jsonPrimitive?.int)
        assertTrue(defaultWorkbenchArgs("github_auth_status", "unused/repository").isEmpty())
    }

    @Test fun everyWriteOrDangerousCallRequiresDshConfirmation() {
        assertFalse(requiresWorkbenchConfirmation(Risk.read))
        assertTrue(requiresWorkbenchConfirmation(Risk.write))
        assertTrue(requiresWorkbenchConfirmation(Risk.dangerous))
    }
}