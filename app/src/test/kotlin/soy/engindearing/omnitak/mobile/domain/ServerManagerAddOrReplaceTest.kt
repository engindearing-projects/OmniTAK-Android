package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.ServerStoreApi
import soy.engindearing.omnitak.mobile.data.TAKServer
import kotlin.coroutines.ContinuationInterceptor

/**
 * The manual Add Server screen saves with [ServerManager.addOrReplaceServer]: adding
 * a server whose endpoint is already in the list replaces that entry's settings
 * instead of silently doing nothing, so a hand-added server that was refused can be
 * fixed by adding it again with its CA.
 *
 * Each test lets the first add settle (advanceUntilIdle) before the second one, the
 * way a person does: the first save into an empty store is read back by hydrate().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerManagerAddOrReplaceTest {

    private class FakeServerStore : ServerStoreApi {
        private val _servers = MutableStateFlow<List<TAKServer>>(emptyList())
        private val _activeId = MutableStateFlow<String?>(null)

        override val servers: Flow<List<TAKServer>> = _servers.asStateFlow()
        override val activeServerId: Flow<String?> = _activeId.asStateFlow()

        val saved: List<TAKServer> get() = _servers.value

        override suspend fun saveServers(list: List<TAKServer>) {
            _servers.value = list
        }

        override suspend fun saveActiveServerId(id: String?) {
            _activeId.value = id
        }
    }

    // A closed port on this machine: a dial fails at once, and nothing leaves the host.
    private fun server(
        name: String = "Hand added",
        port: Int = 9,
        caCertificateName: String? = null,
        enabled: Boolean = true,
        id: String = "id-$name-$port",
    ) = TAKServer(
        id = id,
        name = name,
        host = "127.0.0.1",
        port = port,
        protocol = ConnectionProtocol.TLS.wire,
        useTLS = true,
        enabled = enabled,
        caCertificateName = caCertificateName,
    )

    private fun makeManager(store: ServerStoreApi, scope: TestScope): ServerManager {
        val dispatcher = scope.coroutineContext[ContinuationInterceptor]!!
        return ServerManager(
            store = store,
            externalScope = CoroutineScope(dispatcher + SupervisorJob()),
        )
    }

    @Test
    fun `adding the same endpoint again with a CA keeps one entry with the same id and the CA set`() = runTest {
        val store = FakeServerStore()
        val manager = makeManager(store, this)
        advanceUntilIdle()
        val first = manager.addOrReplaceServer(server(name = "Refused", id = "first-id"))
        advanceUntilIdle()

        val again = manager.addOrReplaceServer(
            server(name = "With its CA", caCertificateName = "ca-manual-0123456789abcdef.pem", id = "another-id"),
        )
        advanceUntilIdle()

        val list = manager.servers.value
        assertEquals("one entry, not two", 1, list.size)
        assertEquals("it keeps its id", first.id, list.single().id)
        assertEquals("first-id", again.id)
        assertEquals("the CA is set", "ca-manual-0123456789abcdef.pem", list.single().caCertificateName)
        assertEquals("the new settings replace the old", "With its CA", list.single().name)
        assertEquals("what is saved is the replaced list", list, store.saved)
    }

    @Test
    fun `adding a different endpoint appends it`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        manager.addOrReplaceServer(server(name = "One", port = 9))
        advanceUntilIdle()

        manager.addOrReplaceServer(server(name = "Two", port = 19))
        advanceUntilIdle()

        assertEquals(listOf("One", "Two"), manager.servers.value.map { it.name })
        assertEquals(2, manager.servers.value.map { it.id }.toSet().size)
    }

    @Test
    fun `with no match it is addServer`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()

        val added = manager.addOrReplaceServer(server(name = "New", id = "new-id"))
        advanceUntilIdle()

        assertEquals("new-id", added.id)
        assertEquals(added, manager.activeServer.value)
        assertNotNull("an enabled server is dialed", manager.serverStates.value["new-id"])
    }

    @Test
    fun `the active server stays the replaced entry`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        val first = manager.addOrReplaceServer(server(name = "Refused"))
        advanceUntilIdle()
        assertEquals(first.id, manager.activeServer.value?.id)

        manager.addOrReplaceServer(server(name = "With its CA", caCertificateName = "ca-manual-1.pem"))
        advanceUntilIdle()

        assertEquals(first.id, manager.activeServer.value?.id)
        assertEquals("ca-manual-1.pem", manager.activeServer.value?.caCertificateName)
    }

    @Test
    fun `a replaced server's connection is dropped and dialed again`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        val first = manager.addOrReplaceServer(server(name = "Refused"))
        advanceUntilIdle()
        assertNotNull("dialed once", manager.serverStates.value[first.id])

        manager.addOrReplaceServer(server(name = "With its CA", caCertificateName = "ca-manual-1.pem"))

        // The old connection is gone at once: its state entry is removed, and the new
        // one reports its state only when its collector runs.
        assertNull("the old connection was dropped", manager.serverStates.value[first.id])
        advanceUntilIdle()
        assertNotNull("a new one was dialed", manager.serverStates.value[first.id])
    }

    @Test
    fun `the same settings again leave the connection alone`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        val first = manager.addOrReplaceServer(server(name = "Same", caCertificateName = "ca-manual-1.pem"))
        advanceUntilIdle()
        val listBefore = manager.servers.value

        val again = manager.addOrReplaceServer(
            server(name = "Same", caCertificateName = "ca-manual-1.pem", id = "other"),
        )

        assertEquals(first, again)
        assertSame(listBefore, manager.servers.value)
        assertNotNull("not dropped", manager.serverStates.value[first.id])
    }

    @Test
    fun `the ports the screen does not ask for stay and an untrusted TLS opt-out does not`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        manager.addServer(
            server(name = "Enrolled").copy(
                enrollmentPort = 9446,
                secureApiPort = 9443,
                isDefault = true,
                allowUntrustedTls = true,
            ),
        )
        advanceUntilIdle()

        manager.addOrReplaceServer(server(name = "Hand added again", caCertificateName = "ca-manual-1.pem"))
        advanceUntilIdle()

        val replaced = manager.servers.value.single()
        assertEquals(9446, replaced.enrollmentPort)
        assertEquals(9443, replaced.secureApiPort)
        assertTrue(replaced.isDefault)
        assertFalse("a pinned CA must not be overridden by an old trust-all opt-out", replaced.allowUntrustedTls)
    }

    @Test
    fun `a disabled entry added again is enabled, dialed and becomes the active server when there is none`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        val live = manager.addOrReplaceServer(server(name = "Live", port = 9))
        advanceUntilIdle()
        val off = manager.addOrReplaceServer(server(name = "Off", port = 19, enabled = false))
        advanceUntilIdle()
        manager.toggleEnabled(live.id)
        advanceUntilIdle()
        assertNull("no enabled server is left to be the active one", manager.activeServer.value)
        assertNull(manager.serverStates.value[off.id])

        manager.addOrReplaceServer(server(name = "On again", port = 19, enabled = true))
        advanceUntilIdle()

        assertTrue(manager.servers.value.first { it.id == off.id }.enabled)
        assertNotNull(manager.serverStates.value[off.id])
        assertEquals(off.id, manager.activeServer.value?.id)
    }
}
