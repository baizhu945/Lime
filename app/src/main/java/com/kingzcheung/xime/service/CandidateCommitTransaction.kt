package com.kingzcheung.xime.service

/** Orders editor delivery before any learning/cleanup. Rejection has no accepted side effects. */
internal suspend fun PendingCandidateCommit.deliver(
    submit: suspend (String) -> TextCommitResult,
    rejected: suspend (PendingCandidateCommit) -> Unit,
    accepted: suspend (PendingCandidateCommit) -> Unit,
): TextCommitResult {
    if (InputCommandOwner.current.get() !== owner) {
        com.kingzcheung.xime.util.FileLogger.w("CandidateCommit", "deliver skipped: owner mismatch")
        return TextCommitResult.REJECTED
    }
    try { owner.validate() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) {
        return TextCommitResult.REJECTED
    }
    val result = submit(text)
    if (result.accepted) accepted(this) else rejected(this)
    return result
}
