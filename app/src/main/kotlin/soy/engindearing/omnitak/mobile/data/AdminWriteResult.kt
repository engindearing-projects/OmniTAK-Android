package soy.engindearing.omnitak.mobile.data

/**
 * Why a settings write was not sent. In every case nothing reached the radio.
 *
 * [message] is the text the operator sees, so a screen does not have to
 * guess what "failed" meant.
 */
enum class RefusalReason(val message: String) {
    /** No link to a radio, or the radio has not reported its node number yet. */
    NO_RADIO("No radio connected."),

    /**
     * The radio's current settings have not arrived. A write has to start from
     * them (the radio replaces a whole config with what it receives), so
     * without them there is nothing safe to send.
     */
    NOT_LOADED("Radio settings are not loaded yet. Reconnect and try again."),

    /** What the radio sent could not be read, or the edited result would not fit in one admin message. */
    UNREADABLE("The radio's settings could not be read. Reconnect and try again."),
}

/** What became of one settings write, in terms a screen can show. */
sealed interface AdminWriteResult {

    /** Every frame reached the transport. [count] is how many settings were written. */
    data class Sent(val count: Int) : AdminWriteResult

    /** The radio already has what was asked for. Nothing was sent. */
    data object NothingToChange : AdminWriteResult

    /** Nothing was sent, and the radio is exactly as it was. */
    data class Refused(val reason: RefusalReason) : AdminWriteResult

    /**
     * The link failed while writing: [sent] of [total] settings got out first.
     * [sent] equal to [total] means the writes went out but the final save
     * command did not.
     */
    data class LinkFailed(val sent: Int, val total: Int) : AdminWriteResult

    /** True when at least one frame reached the radio, so its settings may have changed. */
    val reachedRadio: Boolean
        get() = when (this) {
            is Sent -> true
            is LinkFailed -> sent > 0
            NothingToChange, is Refused -> false
        }

    /** One line for the operator. [success] is the text for a write that went through. */
    fun describe(success: String): String = when (this) {
        is Sent -> success
        NothingToChange -> "Nothing to change. The radio already has these settings."
        is Refused -> reason.message
        is LinkFailed -> when {
            sent == 0 -> "The write did not reach the radio. Check the link and try again."
            sent < total -> "Sent $sent of $total settings before the radio link dropped. Reconnect and try again."
            else -> "Settings were sent, but the link dropped before the radio confirmed saving them. Reconnect and check."
        }
    }
}
