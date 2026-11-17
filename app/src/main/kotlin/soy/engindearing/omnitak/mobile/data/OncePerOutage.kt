package soy.engindearing.omnitak.mobile.data

/**
 * Says whether a failure that keeps repeating should be reported again. The first failure is reported, the
 * ones after it are not, and the first failure after something worked is, so a disk that stays full gives one
 * line in the log however many times the app tries to write to it, and a disk that fills up again later gives
 * another.
 */
class OncePerOutage {
    private var reported = false

    /** A failure happened: true when it is the one to report. */
    @Synchronized
    fun shouldReport(): Boolean {
        if (reported) return false
        reported = true
        return true
    }

    /** Something worked: the next failure is a new one. */
    @Synchronized
    fun succeeded() {
        reported = false
    }
}
