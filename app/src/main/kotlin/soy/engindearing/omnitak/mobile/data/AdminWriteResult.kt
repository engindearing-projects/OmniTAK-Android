package soy.engindearing.omnitak.mobile.data

/**
 * Why a settings write was not sent. In every case no setting was changed.
 *
 * [message] is the text the operator sees, so a screen does not have to
 * guess what "failed" meant.
 */
enum class RefusalReason(val message: String) {
    /** No link to a radio, or the radio has not reported its node number yet. */
    NO_RADIO("No radio connected."),

    /**
     * The radio did not answer the read that comes before a write. A write
     * starts from what the radio holds right now (it replaces a whole config
     * with what it receives), so without that answer there is nothing safe to
     * send. A managed radio ignores every local admin message, reads included.
     */
    NO_ANSWER(
        "The radio did not answer, so nothing was changed. " +
            "It may be managed, or the link may be poor. Reconnect and try again.",
    ),

    /** What the radio sent could not be read, or the edited result would not fit in one admin message. */
    UNREADABLE("The radio's settings could not be read, so nothing was changed. Reconnect and try again."),

    /** An imported channel needs a free secondary slot and the radio has none. */
    NO_FREE_SLOT(
        "Every secondary channel slot is in use, so nothing was imported. " +
            "Remove a channel first, or choose Replace primary.",
    ),
}

/** What became of one settings write, in terms a screen can show. */
sealed interface AdminWriteResult {

    /** Every frame reached the transport, the final save command included. [written] is what was sent, in order. */
    data class Sent(val written: List<AdminSetting>) : AdminWriteResult

    /** The radio already has what was asked for. Nothing was sent. */
    data object NothingToChange : AdminWriteResult

    /** Nothing was sent, and the radio is exactly as it was. */
    data class Refused(val reason: RefusalReason) : AdminWriteResult

    /**
     * The sequence stopped after the radio had been told to expect changes.
     * [written] reached the transport before it stopped, [notWritten] did not,
     * and [committed] says whether the final save command went out. When it did
     * not, the radio may hold some of [written] unsaved.
     */
    data class Incomplete(
        val written: List<AdminSetting>,
        val notWritten: List<AdminSetting>,
        val cause: Cause,
        val committed: Boolean,
    ) : AdminWriteResult {
        enum class Cause(val phrase: String) {
            LINK_LOST("because the link failed"),
            NO_ANSWER("because the radio stopped answering"),
            UNREADABLE("because the radio's reply could not be read"),
        }
    }

    /** True when at least one setting was sent, so the radio's settings may have changed. */
    val reachedRadio: Boolean
        get() = when (this) {
            is Sent -> true
            is Incomplete -> written.isNotEmpty()
            NothingToChange, is Refused -> false
        }

    /** What the operator reads: what was sent, what was not, and what is not known. */
    fun describe(): String = when (this) {
        is Sent -> "Sent to the radio: ${written.names()}. The radio restarts to save them."
        NothingToChange -> "Nothing to change. The radio already has these settings."
        is Refused -> reason.message
        is Incomplete -> buildString {
            if (written.isNotEmpty()) append("Sent to the radio: ${written.names()}. ")
            if (notWritten.isNotEmpty()) append("Not sent: ${notWritten.names()}, ${cause.phrase}. ")
            append(
                when {
                    committed -> "The radio saved what it received. Reconnect and check what it has."
                    written.isEmpty() -> "Nothing was changed. Reconnect and try again."
                    else ->
                        "The link dropped before the radio was told to save, so some of these may have been " +
                            "applied. Reconnect and check what the radio has."
                },
            )
        }
    }

    private fun List<AdminSetting>.names(): String = joinToString(", ") { it.label }
}
