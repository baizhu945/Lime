package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长按气泡按手指横向位置选第几项（?123 的「数字」「常用符号」两项，还有九键、字母长按共用）。
 * 键宽 100、两项时每项 50：手指在键中心选第一项，往右半格以上才换第二项，越界夹在两端。
 */
class ModeChangeLongPressIndexTest {
    @Test fun centreAndLeftPickTheFirstItem() {
        assertEquals(0, longPressItemIndex(0f, 50f, 2))
        assertEquals(0, longPressItemIndex(-40f, 50f, 2))
        assertEquals(0, longPressItemIndex(-500f, 50f, 2))
    }

    @Test fun pastHalfAnItemWidthPicksTheSecondItem() {
        // 键宽 100、两项：边界在键中心右边 25（半项宽），和原来的算法一致。
        assertEquals(0, longPressItemIndex(24f, 50f, 2))
        assertEquals(1, longPressItemIndex(25f, 50f, 2))
        assertEquals(1, longPressItemIndex(500f, 50f, 2))
    }

    @Test fun singleItemAndEmptyMenusNeverLeaveTheKey() {
        // 只有一项时手指挪到哪儿都是它，不会算到不存在的第二项。
        assertEquals(0, longPressItemIndex(0f, 100f, 1))
        assertEquals(0, longPressItemIndex(900f, 100f, 1))
        // 没有选项时调用方不会用，但不能炸（除零）。
        assertEquals(0, longPressItemIndex(0f, 0f, 0))
    }

    @Test fun threeItemMenusStayInsideTheList() {
        // 九键 2 键（ABC）三项：每项 1/3 键宽。
        assertEquals(0, longPressItemIndex(0f, 33f, 3))
        assertEquals(1, longPressItemIndex(33f, 33f, 3))
        assertEquals(2, longPressItemIndex(80f, 33f, 3))
        assertEquals(2, longPressItemIndex(900f, 33f, 3))
    }
}
