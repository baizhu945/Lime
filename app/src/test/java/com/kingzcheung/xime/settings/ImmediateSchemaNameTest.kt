package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ImmediateSchemaNameTest {
    @Test
    fun knownNameAndBuiltinNamesAreReadyBeforeDisk() {
        assertEquals("日语26键", ChineseSchemas.immediateSchemaName("japanese", "日语26键"))
        assertEquals("中文26键", ChineseSchemas.immediateSchemaName("rime_ice", null))
        assertEquals("日语26键", ChineseSchemas.immediateSchemaName("japanese", null))
    }
}
