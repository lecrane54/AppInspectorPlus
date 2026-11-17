package io.github.networkrules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevicePushLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `device paths follow the layout the interceptor reads`() {
        assertEquals("/sdcard/Android/data/com.acme.app/files/network-inspector-rules", DevicePushLogic.remoteRoot("com.acme.app"))
        assertEquals("/sdcard/Android/data/com.acme.app/files/network-inspector-rules/checkout/payment.json", DevicePushLogic.remotePath("com.acme.app", "checkout/payment.json"))
        assertEquals("/sdcard/Android/data/com.acme.app/files/network-inspector-recordings", DevicePushLogic.recordingsRoot("com.acme.app"))
    }

    @Test fun `every team file and scenarios json are collected`() {
        val root = tmp.newFolder("network-inspector-rules")
        File(root, "login").mkdirs(); File(root, "login/session.json").writeText("{}"); File(root, "login/notes.txt").writeText("x")
        File(root, "checkout").mkdirs(); File(root, "checkout/payment.json").writeText("{}")
        File(root, "scenarios.json").writeText("{}")
        File(root, "README.md").writeText("x")
        assertEquals(listOf("checkout/payment.json", "login/session.json", "scenarios.json"), DevicePushLogic.collectFiles(root).map { it.first })
        assertTrue(DevicePushLogic.collectFiles(root).all { it.second.isFile })
        assertTrue(DevicePushLogic.collectFiles(tmp.newFolder("empty")).isEmpty())
    }

    @Test fun `the broadcast matches the receiver's contract`() {
        assertEquals(
            "am broadcast -a com.example.mockrules.CONTROL -p com.acme.app -f 0x20 --ez reload true",
            DevicePushLogic.broadcast(DevicePushLogic.DEFAULT_ACTION, "com.acme.app", DevicePushLogic.reloadExtras(null, null)),
        )
        assertEquals(
            listOf("--ez reload true", "--es scenario \"checkout-declined\"", "--es var \"EMAIL=qa@example.com\""),
            DevicePushLogic.reloadExtras("checkout-declined", "--es var \"EMAIL=qa@example.com\""),
        )
    }

    @Test fun `the summary is read from am broadcast output`() {
        val out = """Broadcasting: Intent { act=com.example.mockrules.CONTROL flg=0x400020 pkg=com.acme.app (has extras) }
            |Broadcast completed: result=0, data="scenario=checkout-declined enabled=[checkout/payment.json] activeRules=1/12"
            |""".trimMargin()
        assertEquals("scenario=checkout-declined enabled=[checkout/payment.json] activeRules=1/12", DevicePushLogic.summaryOf(out))
        assertNull(DevicePushLogic.summaryOf("Broadcasting: Intent { … }\nBroadcast completed: result=0\n"))
        assertNull(DevicePushLogic.summaryOf(""))
    }

    @Test fun `package and action names are checked`() {
        assertTrue(DevicePushLogic.VALID_PACKAGE.matches("com.acme.app"))
        assertTrue(DevicePushLogic.VALID_PACKAGE.matches("com.acme.app.debug"))
        assertFalse(DevicePushLogic.VALID_PACKAGE.matches("app"))
        assertFalse(DevicePushLogic.VALID_PACKAGE.matches("com.acme.app; rm -rf /"))
        assertTrue(DevicePushLogic.VALID_ACTION.matches("com.example.mockrules.CONTROL"))
        assertFalse(DevicePushLogic.VALID_ACTION.matches("a b"))
        assertEquals("'/a/b c'", DevicePushLogic.quote("/a/b c"))
        assertEquals("'/it'\\''s'", DevicePushLogic.quote("/it's"))
    }
}
