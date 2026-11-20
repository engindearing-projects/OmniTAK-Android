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
 * a server whose endpoint is already in the list merges what was entered into that
 * entry instead of silently doing nothing, so a hand-added server that was refused
 * can be fixed by adding it again with its CA. A field the person filled in wins; a
 * field left empty keeps what the entry had, so a server enrolled by QR code keeps its
 * certificate and its pin when it is added again by hand with only a new name.
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
        certificateName: String? = null,
        certificatePassword: String? = null,
        username: String? = null,
        password: String? = null,
        allowUntrustedTls: Boolean = false,
    ) = TAKServer(
        id = id,
        name = name,
        host = "127.0.0.1",
        port = port,
        protocol = ConnectionProtocol.TLS.wire,
        useTLS = true,
        enabled = enabled,
        caCertificateName = caCertificateName,
        certificateName = certificateName,
        certificatePassword = certificatePassword,
        username = username,
        password = password,
        allowUntrustedTls = allowUntrustedTls,
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
    fun `the ports and the default flag the screen does not ask for stay`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        manager.addServer(server(name = "Enrolled").copy(enrollmentPort = 9446, secureApiPort = 9443, isDefault = true))
        advanceUntilIdle()

        manager.addOrReplaceServer(server(name = "Hand added again", caCertificateName = "ca-manual-1.pem"))
        advanceUntilIdle()

        val replaced = manager.servers.value.single()
        assertEquals(9446, replaced.enrollmentPort)
        assertEquals(9443, replaced.secureApiPort)
        assertTrue(replaced.isDefault)
    }

    @Test
    fun `adding a CA to an entry that had trust-all turns trust-all off`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        manager.addServer(server(name = "Trusting", allowUntrustedTls = true))
        advanceUntilIdle()

        manager.addOrReplaceServer(server(name = "Trusting", caCertificateName = "ca-manual-1.pem"))
        advanceUntilIdle()

        val replaced = manager.servers.value.single()
        assertEquals("ca-manual-1.pem", replaced.caCertificateName)
        assertFalse("a pinned CA must not be overridden by an old trust-all opt-out", replaced.allowUntrustedTls)
    }

    @Test
    fun `an old trust-all opt-out stays when the form supplies no CA`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        manager.addServer(server(name = "Trusting", allowUntrustedTls = true))
        advanceUntilIdle()

        manager.addOrReplaceServer(server(name = "Renamed"))
        advanceUntilIdle()

        assertTrue(manager.servers.value.single().allowUntrustedTls)
    }

    @Test
    fun `an entry with a certificate, a CA and credentials added again with only a new name keeps all of them and is not redialed`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        val enrolled = manager.addServer(
            server(
                name = "Enrolled",
                certificateName = "client.p12",
                certificatePassword = "cert-pw",
                caCertificateName = "ca-enrolled.pem",
                username = "operator",
                password = "secret",
            ),
        )
        advanceUntilIdle()
        assertNotNull("dialed once", manager.serverStates.value[enrolled.id])

        manager.addOrReplaceServer(server(name = "Renamed"))

        // A rename alone leaves the live connection running: its state entry is still there.
        assertNotNull("not dropped", manager.serverStates.value[enrolled.id])
        advanceUntilIdle()
        val kept = manager.servers.value.single()
        assertEquals("Renamed", kept.name)
        assertEquals(enrolled.id, kept.id)
        assertEquals("client.p12", kept.certificateName)
        assertEquals("cert-pw", kept.certificatePassword)
        assertEquals("ca-enrolled.pem", kept.caCertificateName)
        assertEquals("operator", kept.username)
        assertEquals("secret", kept.password)
        assertEquals("a rename is saved", kept, manager.activeServer.value)
    }

    @Test
    fun `what the person fills in wins over what the entry had, and the connection is redialed`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        val first = manager.addServer(
            server(
                name = "Old",
                certificateName = "old.p12",
                certificatePassword = "old-cert-pw",
                caCertificateName = "ca-old.pem",
                username = "old-user",
                password = "old-pw",
            ),
        )
        advanceUntilIdle()

        manager.addOrReplaceServer(
            server(
                name = "New",
                certificateName = "new.p12",
                certificatePassword = "new-cert-pw",
                caCertificateName = "ca-new.pem",
                username = "new-user",
                password = "new-pw",
            ),
        )
        assertNull("the old connection was dropped", manager.serverStates.value[first.id])
        advanceUntilIdle()

        val merged = manager.servers.value.single()
        assertEquals("new.p12", merged.certificateName)
        assertEquals("new-cert-pw", merged.certificatePassword)
        assertEquals("ca-new.pem", merged.caCertificateName)
        assertEquals("new-user", merged.username)
        assertEquals("new-pw", merged.password)
        assertNotNull("a new one was dialed", manager.serverStates.value[first.id])
    }

    @Test
    fun `fields are merged one by one, and a certificate picked without a password drops the old password`() = runTest {
        val manager = makeManager(FakeServerStore(), this)
        advanceUntilIdle()
        manager.addServer(
            server(name = "Enrolled", certificateName = "old.p12", certificatePassword = "old-cert-pw", username = "op", password = "secret"),
        )
        advanceUntilIdle()

        // Only a user name typed: the password stays. A certificate picked with no password: the old password does not.
        manager.addOrReplaceServer(server(name = "Enrolled", username = "other", certificateName = "new.p12"))
        advanceUntilIdle()

        val merged = manager.servers.value.single()
        assertEquals("other", merged.username)
        assertEquals("secret", merged.password)
        assertEquals("new.p12", merged.certificateName)
        assertNull(merged.certificatePassword)
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
