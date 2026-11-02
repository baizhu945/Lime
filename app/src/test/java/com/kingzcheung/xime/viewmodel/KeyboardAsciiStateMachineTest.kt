package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardAsciiStateMachineTest {
    private val machine = KeyboardAsciiStateMachine()
    private val main = AsciiKeyboardContext.MAIN
    private val symbol = AsciiKeyboardContext.SYMBOL_PANEL
    private val number = AsciiKeyboardContext.NUMBER_PANEL

    @Test
    fun `first and repeated symbol entry follows main language`() {
        for (entryLanguage in listOf(false, true)) {
            machine.saveMemory(main, entryLanguage)
            assertNull(machine.targetFor(symbol, entryLanguage))
            // 手动切换后退出，再从主键盘以入口语言进入。
            machine.saveMemory(symbol, !entryLanguage)
            assertEquals(entryLanguage, machine.targetFor(main, !entryLanguage))
            machine.saveMemory(main, entryLanguage)
            assertNull(machine.targetFor(symbol, entryLanguage))
        }
        // 上次符号页为英文也不能覆盖这次主键盘的中文。
        machine.saveMemory(symbol, true)
        machine.saveMemory(main, false)
        assertNull(machine.targetFor(symbol, false))
    }

    @Test
    fun `main keyboard restores entry language after panel switch`() {
        for (entryLanguage in listOf(false, true)) {
            machine.saveMemory(main, entryLanguage)
            assertNull(machine.targetFor(symbol, entryLanguage))
            machine.saveMemory(symbol, !entryLanguage)
            assertEquals(entryLanguage, machine.targetFor(main, !entryLanguage))
            assertNull(machine.targetFor(main, entryLanguage))
        }
    }

    @Test
    fun `switching panels follows current language and preserves main memory`() {
        for (entryLanguage in listOf(false, true)) {
            machine.saveMemory(main, entryLanguage)
            assertNull(machine.targetFor(symbol, entryLanguage))
            machine.saveMemory(symbol, !entryLanguage)
            assertNull(machine.targetFor(number, !entryLanguage))
            machine.saveMemory(number, !entryLanguage)
            assertNull(machine.targetFor(symbol, !entryLanguage))
            machine.saveMemory(symbol, !entryLanguage)
            assertEquals(entryLanguage, machine.targetFor(main, !entryLanguage))
        }
    }

    @Test
    fun `number panel follows engine regardless of previous language`() {
        machine.saveMemory(number, false)
        assertNull(machine.targetFor(number, true))
        machine.saveMemory(number, true)
        assertNull(machine.targetFor(number, false))
        for (language in listOf(false, true)) {
            machine.saveMemory(main, language)
            assertNull(machine.targetFor(number, language))
            machine.saveMemory(number, language)
            assertNull(machine.targetFor(main, language))
        }
    }

    @Test
    fun `unset and reset main memory follow engine`() {
        assertNull(machine.targetFor(main, true))
        assertNull(machine.targetFor(main, false))
        machine.saveMemory(main, false)
        machine.saveMemory(symbol, true)
        machine.reset()
        assertNull(machine.targetFor(main, true))
        assertNull(machine.targetFor(symbol, false))
    }
}
