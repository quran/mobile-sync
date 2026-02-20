package com.quran.shared.persistence.util

import com.quran.shared.persistence.util.QuranData.suraAyahCounts

object QuranActionsUtils {

    fun getRandomSura(): Int = (1..114).random()

    fun getRandomAyah(sura: Int): Int {
        if (sura !in 1..114) return 1
        val count = suraAyahCounts[sura - 1]
        return (1..count).random()
    }

    fun getRandomPage(): Int = (1..604).random()

}