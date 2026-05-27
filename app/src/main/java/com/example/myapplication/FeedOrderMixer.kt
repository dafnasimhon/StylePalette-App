package com.example.myapplication

import com.example.myapplication.models.Outfit
import kotlin.random.Random

/**
 * Reorders feed outfits client-side after Firestore fetch so posts from different
 * users are interleaved instead of appearing in timestamp batches per uploader.
 */
object FeedOrderMixer {

    private const val UNKNOWN_USER_KEY = "__unknown_user__"

    /**
     * Returns a shuffled list that mixes [userId] groups and avoids back-to-back
     * posts from the same user when another user's post is still available.
     */
    fun mixOutfits(outfits: List<Outfit>, random: Random = Random): List<Outfit> {
        if (outfits.size <= 1) return outfits

        val byUser = outfits.groupBy { outfit ->
            outfit.userId.takeIf { it.isNotBlank() } ?: UNKNOWN_USER_KEY
        }

        val queues = byUser.mapValues { (_, group) ->
            group.shuffled(random).toMutableList()
        }.toMutableMap()

        val mixed = ArrayList<Outfit>(outfits.size)
        var lastUserKey: String? = null

        while (mixed.size < outfits.size) {
            val available = queues.filter { (_, queue) -> queue.isNotEmpty() }
            if (available.isEmpty()) break

            val withoutRepeat = available.filter { (userKey, _) -> userKey != lastUserKey }
            val pool = withoutRepeat.ifEmpty { available }

            val chosenKey = pool.maxByOrNull { it.value.size }!!.key
            val next = queues[chosenKey]!!.removeAt(0)
            mixed.add(next)
            lastUserKey = chosenKey
        }

        return mixed
    }
}
