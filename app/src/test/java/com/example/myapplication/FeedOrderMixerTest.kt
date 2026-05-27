package com.example.myapplication

import com.example.myapplication.models.Outfit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedOrderMixerTest {

    @Test
    fun mixOutfits_avoidsConsecutiveSameUserWhenPossible() {
        val outfits = listOf(
            outfit("1", "alice"),
            outfit("2", "alice"),
            outfit("3", "alice"),
            outfit("4", "bob"),
            outfit("5", "bob"),
            outfit("6", "carol"),
        )

        val mixed = FeedOrderMixer.mixOutfits(outfits, random = kotlin.random.Random(42))

        assertEquals(6, mixed.size)
        assertEquals(outfits.map { it.id }.toSet(), mixed.map { it.id }.toSet())
        for (i in 1 until mixed.size) {
            if (mixed[i].userId == mixed[i - 1].userId) {
                val remainingUsers = mixed.drop(i).map { it.userId }.toSet()
                assertTrue(
                    "Only one user left in tail",
                    remainingUsers.size <= 1
                )
            }
        }
    }

    private fun outfit(id: String, userId: String) = Outfit(id = id, userId = userId)
}
