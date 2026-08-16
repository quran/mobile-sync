package com.quran.shared.demo.common.util

import kotlin.native.ObjCName

@ObjCName("QuranActionsUtils")
object QuranActionsUtils {

    fun getRandomSura(): Int = (1..114).random()

    @Suppress("UNUSED_PARAMETER")
    fun getRandomAyah(sura: Int): Int = 1

    fun getRandomPage(): Int = (1..604).random()

}
