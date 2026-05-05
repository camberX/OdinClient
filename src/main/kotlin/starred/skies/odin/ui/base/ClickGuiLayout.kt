package starred.skies.odin.ui.base

object ClickGuiLayout {
    fun mainContentY(frameY: Int, mainContentTopInset: Int): Int = frameY + mainContentTopInset

    fun mainContentH(frameH: Int, mainContentTopInset: Int, mainContentBottomInset: Int): Int =
        frameH - mainContentTopInset - mainContentBottomInset

    fun moduleListBaseY(categoryStackTopY: Int, categorySize: Int, rowHeight: Int): Int =
        categoryStackTopY + categorySize / 2 - rowHeight / 2

    fun moduleListBottomY(mainContentY: Int, mainContentH: Int): Int = mainContentY + mainContentH

    fun frameInnerBottomY(frameY: Int, frameH: Int, mainContentBottomInset: Int): Int =
        frameY + frameH - mainContentBottomInset

    fun mainContentPanelX(frameX: Int, railW: Int, contentPad: Int): Int = frameX + railW + contentPad

    fun mainContentPanelW(frameW: Int, railW: Int, contentPad: Int): Int = frameW - railW - contentPad - 8

    fun contentPanelInnerX(frameX: Int, railW: Int, contentPad: Int): Int = frameX + railW + contentPad + 3

    fun contentPanelInnerW(frameW: Int, railW: Int, contentPad: Int): Int = frameW - railW - contentPad * 2 - 6

    fun railColumnCenterX(frameX: Int, railW: Int): Int {
        val left = frameX + 6
        val right = frameX + railW
        return left + (right - left) / 2
    }

    fun railTitleBaselineY(frameY: Int): Int = frameY + 8

    fun categoryStackTopY(frameY: Int): Int = frameY + 15

    fun categoryCellX(frameX: Int, railW: Int, categorySize: Int): Int {
        val railLeft = frameX + 6
        val railInteriorW = (frameX + railW) - railLeft
        val x = railLeft + (railInteriorW - categorySize) / 2
        return x.coerceAtLeast(frameX + 2)
    }
}
