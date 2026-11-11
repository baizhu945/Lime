package com.kingzcheung.xime.rime

/** English phrases need native selection to capture their exact dictionary/custom code before delivery. */
internal fun t9CandidateNeedsNativeSelection(pinyin: String?, text: String?): Boolean =
    !pinyin.isNullOrBlank() || text != null &&
        text.any { it in 'a'..'z' || it in 'A'..'Z' } && text.all { it.code in 32..126 }
