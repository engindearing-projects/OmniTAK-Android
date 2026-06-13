package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.ChatStatus
import soy.engindearing.omnitak.mobile.data.ChatXml
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.domain.ChatStore
import soy.engindearing.omnitak.mobile.domain.ServerManager
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(initialConversationId: String? = null) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val conversations by app.chatStore.conversations.collectAsState()
    val messagesByConvo by app.chatStore.messagesByConversation.collectAsState()
    val prefs by app.userPrefsStore.prefs.collectAsState(initial = UserPrefs())
    val contacts by app.contactStore.contacts.collectAsState()
    val chatScope = rememberCoroutineScope()

    // Persist the canonical EUD UID on first chat-tab open even if PPLI
    // hasn't run yet (no server connected, no GPS fix). This is the same
    // UID PPLI uses — sending two different UIDs to a TAK server makes
    // ATAK render the operator as two separate contacts (#9).
    LaunchedEffect(Unit) { app.userPrefsStore.ensureSelfUid() }

    // Multi-server: resolve a serverId → display name so chat can badge which
    // server a message/thread came from. Badges only show when >1 server is
    // configured, otherwise they're noise.
    val servers by app.serverManager.servers.collectAsState()
    val serverNames = remember(servers) { servers.associate { it.id to it.name } }
    val multiServer = servers.size > 1
    val serverNameFor: (String?) -> String? = { id -> id?.let { serverNames[it] } }

    // GAP-124 — when arriving with an initial convo id (eg. from the
    // node-detail sheet's "Message" button), open that conversation
    // straight away instead of dropping the user on the conversation list.
    var selectedConversation by remember(initialConversationId) {
        mutableStateOf(initialConversationId)
    }
    LaunchedEffect(selectedConversation) {
        val id = selectedConversation ?: return@LaunchedEffect
        app.chatStore.markRead(id)
    }

    // Merge conversations with ContactStore contacts. Contacts that don't
    // already have a DM thread appear as "No messages yet" entries so the
    // user can initiate a message — matching Civtak / iTak behavior.
    // Own UID is excluded (don't show yourself as a contact to chat with).
    val mergedConversations = remember(conversations, contacts, prefs.selfUid) {
        val selfUid = prefs.selfUid
        val existing = conversations.values.toList()

        // Collect UIDs that already have a direct-message thread so stubs
        // aren't created for contacts you've already chatted with.
        val coveredUids = existing
            .filter { !it.isGroup }
            .flatMap { it.participants }
            .map { it.uid }
            .toSet()

        val contactStubs = contacts.values
            .filter { contact -> contact.uid != selfUid && contact.uid !in coveredUids }
            .map { contact ->
                // Use the same deterministic conversation-id formula as
                // ChatRoom.directConversationId so that when a real GeoChat
                // arrives later the stub seamlessly promotes to a real thread.
                val convoId = ChatRoom.directConversationId(selfUid, contact.uid)
                ChatConversation(
                    id = convoId,
                    title = contact.callsign ?: contact.uid,
                    isGroup = false,
                    participants = listOf(
                        ChatParticipant(
                            uid = contact.uid,
                            callsign = contact.callsign ?: contact.uid,
                        ),
                    ),
                )
            }

        // Real threads (with activity) sorted newest-first; contact stubs
        // sorted alphabetically below them.
        existing.sortedByDescending { it.lastActivityIso ?: "" } +
            contactStubs.sortedBy { it.title.lowercase() }
    }

    if (selectedConversation == null) {
        ConversationListView(
            conversations = mergedConversations,
            onSelect = { id ->
                // If the tapped entry is a contact stub (no real conversation
                // exists yet), seed the full stub into ChatStore so the detail
                // view and the send path have a backing record with participant
                // metadata (needed to resolve the recipient UID on send).
                if (!conversations.containsKey(id)) {
                    val stub = mergedConversations.find { it.id == id }
                    if (stub != null) {
                        app.chatStore.upsertConversationIfMissing(stub)
                    }
                }
                app.chatStore.markRead(id)
                selectedConversation = id
            },
            multiServer = multiServer,
            serverNameFor = serverNameFor,
        )
    } else {
        val convo = conversations[selectedConversation]
        val messages = messagesByConvo[selectedConversation].orEmpty()
        if (convo == null) {
            selectedConversation = null
            return
        }
        ConversationDetailView(
            conversation = convo,
            messages = messages,
            selfUid = prefs.selfUid,
            selfCallsign = prefs.callsign,
            onBack = { selectedConversation = null },
            onSend = { text -> sendChat(app, convo, prefs, text, chatScope) },
            multiServer = multiServer,
            serverNameFor = serverNameFor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListView(
    conversations: List<ChatConversation>,
    onSelect: (String) -> Unit,
    multiServer: Boolean,
    serverNameFor: (String?) -> String?,
) {
    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Chat", color = MaterialTheme.colorScheme.onBackground) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner: PaddingValues ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No chat traffic yet.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            items(conversations, key = { it.id }) { convo ->
                ConversationRow(
                    convo = convo,
                    onClick = { onSelect(convo.id) },
                    serverBadgeName = if (multiServer) serverNameFor(convo.serverId) else null,
                    serverId = convo.serverId,
                )
                HorizontalDivider(color = TacticalSurface)
            }
        }
    }
}

@Composable
private fun ConversationRow(
    convo: ChatConversation,
    onClick: () -> Unit,
    serverBadgeName: String?,
    serverId: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    convo.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (serverBadgeName != null) {
                    Spacer(Modifier.width(6.dp))
                    ChatServerBadge(name = serverBadgeName, serverId = serverId)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                convo.lastMessagePreview ?: if (convo.isGroup) "Broadcast chat" else "No messages yet",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        if (convo.unread > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(TacticalAccent)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    "${convo.unread}",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDetailView(
    conversation: ChatConversation,
    messages: List<ChatMessage>,
    selfUid: String,
    selfCallsign: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    multiServer: Boolean,
    serverNameFor: (String?) -> String?,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBackground),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TacticalBackground)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (conversation.isGroup) "Broadcast" else "Direct message",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider(color = TacticalSurface)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    selfUid = selfUid,
                    serverBadgeName = if (multiServer) serverNameFor(msg.serverId) else null,
                )
            }
        }

        HorizontalDivider(color = TacticalSurface)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TacticalBackground)
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 4,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedContainerColor = TacticalSurface,
                    unfocusedContainerColor = TacticalSurface,
                    focusedIndicatorColor = TacticalAccent,
                    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
                    cursorColor = TacticalAccent,
                ),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isNotEmpty()) {
                        onSend(trimmed)
                        draft = ""
                    }
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = TacticalAccent,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, selfUid: String, serverBadgeName: String?) {
    val isFromSelf = msg.isFromSelf || msg.senderUid == selfUid
    val bubbleColor = if (isFromSelf) TacticalAccent.copy(alpha = 0.25f) else TacticalSurface
    val alignment = if (isFromSelf) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (!isFromSelf) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            ) {
                Text(
                    msg.senderCallsign,
                    color = TacticalAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                // Which server this message arrived on (multi-server).
                if (serverBadgeName != null) {
                    Spacer(Modifier.width(6.dp))
                    ChatServerBadge(name = serverBadgeName, serverId = msg.serverId)
                }
            }
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    msg.text,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatTime(msg.timeIso) + statusSuffix(msg.status, isFromSelf),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Small colored chip naming which TAK server a message/thread belongs to.
 * The hue is derived deterministically from the serverId so a given server
 * always reads the same color across bubbles, rows and the contact list —
 * matching iOS's ChatServerBadge.
 */
@Composable
private fun ChatServerBadge(name: String, serverId: String?) {
    val color = serverBadgeColor(serverId)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            name,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private val SERVER_BADGE_PALETTE = listOf(
    Color(0xFF4FC3F7), // light blue
    Color(0xFF81C784), // green
    Color(0xFFFFB74D), // orange
    Color(0xFFBA68C8), // purple
    Color(0xFF4DD0E1), // cyan
    Color(0xFFE57373), // red
    Color(0xFFFFD54F), // amber
)

private fun serverBadgeColor(serverId: String?): Color {
    if (serverId == null) return Color(0xFF90A4AE) // blue-grey = broadcast/all servers
    val idx = (serverId.hashCode() and 0x7fffffff) % SERVER_BADGE_PALETTE.size
    return SERVER_BADGE_PALETTE[idx]
}

private fun statusSuffix(status: ChatStatus, isFromSelf: Boolean): String {
    if (!isFromSelf) return ""
    return when (status) {
        ChatStatus.SENDING -> " · sending"
        ChatStatus.SENT -> " · sent"
        ChatStatus.FAILED -> " · failed"
        ChatStatus.RECEIVED -> ""
    }
}

private val LOCAL_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTime(iso: String): String = runCatching {
    LOCAL_TIME_FMT.format(Instant.parse(iso))
}.getOrElse { iso }

private fun sendChat(
    app: OmniTAKApp,
    convo: ChatConversation,
    prefs: UserPrefs,
    text: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // Single EUD UID across PPLI + GeoChat + markers. Reuses the one
    // SelfPositionBroadcaster mints on first PPLI; if we send chat
    // before any PPLI has run (offline-typed message, fresh install)
    // ensureSelfUid lazily generates + persists one so the wire never
    // carries an ad-hoc identifier. Fixes #9 (two-contact ghost on
    // receiving ATAK clients).
    val now = soy.engindearing.omnitak.mobile.data.CotXml.isoMillis()
    scope.launch {
        sendChatInner(app, convo, prefs, text, now)
    }
}

private suspend fun sendChatInner(
    app: OmniTAKApp,
    convo: ChatConversation,
    prefs: UserPrefs,
    text: String,
    now: String,
) {
    val senderUid = app.userPrefsStore.ensureSelfUid()

    // GAP-122 / GAP-124 — Mesh conversations route through the active mesh
    // framework manager (Meshtastic portnum 1 or MeshCore native DM) instead
    // of the TAK server's CoT GeoChat path.
    // MESH-CH*/MESH-DM-* → Meshtastic (portnum 1).
    // MESHCORE-CH*/MESHCORE-DM-* → MeshCore (native pubkey-to-pubkey DM).
    if (convo.id.startsWith("MESH-CH") || convo.id.startsWith("MESH-DM-") ||
        convo.id.startsWith("MESHCORE-CH") || convo.id.startsWith("MESHCORE-DM-")
    ) {
        val isMeshCore = convo.id.startsWith("MESHCORE-")
        val channelIndex = when {
            convo.id.startsWith("MESH-CH") -> convo.id.removePrefix("MESH-CH").toIntOrNull() ?: 0
            convo.id.startsWith("MESHCORE-CH") -> convo.id.removePrefix("MESHCORE-CH").toIntOrNull() ?: 0
            else -> 0
        }
        // toNodeId: for DM conversations the nodeId is the hex suffix.
        // Meshtastic uses 32-bit ids (8 hex); MeshCore uses 48-bit (12 hex).
        val toNodeId: UInt? = when {
            convo.id.startsWith("MESH-DM-") ->
                convo.id.removePrefix("MESH-DM-").toLongOrNull(16)?.toUInt()
            convo.id.startsWith("MESHCORE-DM-") ->
                convo.id.removePrefix("MESHCORE-DM-").toLongOrNull(16)?.and(0xFFFFFFFFL)?.toUInt()
            else -> null
        }
        val msgId = UUID.randomUUID().toString()
        val outgoing = ChatMessage(
            id = msgId,
            conversationId = convo.id,
            senderUid = senderUid,
            senderCallsign = prefs.callsign,
            text = text,
            timeIso = now,
            status = ChatStatus.SENDING,
            isFromSelf = true,
        )
        app.chatStore.markOutgoing(outgoing)
        // Route through the active framework so a MeshCore-selected operator's
        // chat goes to MeshCoreManager, not the dead MeshtasticManager.
        val sent = app.activeMeshManager.sendMeshChat(text, channelIndex, toNodeId)
        app.chatStore.updateMessageStatus(
            conversationId = convo.id,
            messageId = msgId,
            status = if (sent) ChatStatus.SENT else ChatStatus.FAILED,
        )
        return
    }

    val recipient = convo.participants.firstOrNull { it.uid != senderUid }
    val isGroup = convo.isGroup

    val generated = ChatXml.generateGeoChat(
        text = text,
        senderUid = senderUid,
        senderCallsign = prefs.callsign,
        isGroup = isGroup,
        recipientUid = recipient?.uid,
        recipientCallsign = recipient?.callsign,
        messageId = UUID.randomUUID().toString(),
    )
    val message = ChatMessage(
        id = generated.messageId,
        conversationId = convo.id,
        senderUid = senderUid,
        senderCallsign = prefs.callsign,
        recipientUid = recipient?.uid,
        recipientCallsign = recipient?.callsign,
        text = text,
        timeIso = now,
        status = ChatStatus.SENDING,
        isFromSelf = true,
        serverId = convo.serverId,
    )
    app.chatStore.markOutgoing(message)

    // Route to the conversation's origin server for per-server DMs;
    // group/broadcast rooms (serverId == null) fan out to all servers.
    val sent = app.serverManager.sendCoT(generated.xml, serverId = convo.serverId)

    // Off-grid mesh GeoChat — portnum-72 b-t-f TAKMessage so operators
    // with radios and NO server can exchange chat. Step 2 of off-grid plan.
    // Keep the existing MESH-CH*/MESH-DM-* / MESHCORE-* path unchanged (above).
    val activeMesh = app.activeMeshManager
    val meshState = activeMesh.activeConnectionState.value
    val latestPrefs = app.userPrefsStore.prefs.first()
    var meshSent = false
    if (meshState is soy.engindearing.omnitak.mobile.domain.ConnectionState.Connected &&
        latestPrefs.broadcastOverMesh
    ) {
        val meshCotEvent = CoTEvent(
            uid = "GeoChat.${senderUid}.${ChatRoom.ALL_USERS}.${generated.messageId}",
            type = "b-t-f",
            lat = 0.0,
            lon = 0.0,
            callsign = prefs.callsign,
            remarks = text,
            teamName = prefs.team,
        )
        // Capture the mesh result instead of discarding it: in the
        // flagship off-grid case (radio, no server) a successful mesh
        // delivery used to render FAILED because only the server result
        // was consulted. Real mesh errors get logged, not swallowed.
        meshSent = runCatching { activeMesh.sendCoTOverMesh(meshCotEvent) }
            .onFailure { t ->
                android.util.Log.w(
                    "ChatScreen",
                    "mesh GeoChat send failed: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
            .getOrDefault(false)
    }

    // Delivered if EITHER transport accepted it — server CoT or
    // portnum-72 mesh broadcast. FAILED now means both paths failed.
    app.chatStore.updateMessageStatus(
        conversationId = convo.id,
        messageId = generated.messageId,
        status = if (sent || meshSent) ChatStatus.SENT else ChatStatus.FAILED,
    )
}
