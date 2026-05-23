package com.example.myapplication.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.models.Outfit
import com.example.myapplication.repository.OutfitRepository

class OutfitAdapter(
    private var outfits: List<Outfit>,
    private val showLikeButton: Boolean = true,
    private val onItemClick: (Outfit) -> Unit
) : RecyclerView.Adapter<OutfitAdapter.OutfitViewHolder>() {

    private val repository = OutfitRepository()

    class OutfitViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.outfitImage)
        val btnLike: ImageButton = view.findViewById(R.id.likeButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OutfitViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_outfit, parent, false)
        return OutfitViewHolder(view)
    }

    override fun onBindViewHolder(holder: OutfitViewHolder, position: Int) {
        val outfit = outfits[position]

        Glide.with(holder.itemView.context)
            .load(outfit.imageUrl)
            .centerCrop()
            .placeholder(R.drawable.placeholder_outfit)
            .into(holder.ivImage)

        if (showLikeButton) {
            holder.btnLike.visibility = View.VISIBLE
            bindLikeButton(holder, outfit)
        } else {
            holder.btnLike.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            onItemClick(outfit)
        }
    }

    private fun bindLikeButton(holder: OutfitViewHolder, outfit: Outfit) {
        val liked = repository.isOutfitLikedCached(outfit.id)
        holder.btnLike.tag = liked
        holder.btnLike.setImageResource(
            if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_tool_bar
        )

        holder.btnLike.setOnClickListener {
            val currentStatus = holder.btnLike.tag as? Boolean ?: false
            val newStatus = !currentStatus
            Log.i("StyleMate_Like", "heart tap outfitId=${outfit.id} -> $newStatus")

            holder.btnLike.tag = newStatus
            holder.btnLike.setImageResource(
                if (newStatus) R.drawable.ic_heart_filled else R.drawable.ic_heart_tool_bar
            )

            repository.toggleLike(outfit.id, newStatus) { success, error ->
                if (success) return@toggleLike
                holder.btnLike.tag = currentStatus
                holder.btnLike.setImageResource(
                    if (currentStatus) R.drawable.ic_heart_filled else R.drawable.ic_heart_tool_bar
                )
                val ctx = holder.itemView.context
                Toast.makeText(
                    ctx,
                    ctx.getString(
                        R.string.like_failed,
                        error?.takeIf { it.isNotBlank() } ?: "Unknown error"
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun getItemCount(): Int = outfits.size

    fun updateData(newOutfits: List<Outfit>) {
        this.outfits = newOutfits
        notifyDataSetChanged()
    }
}
