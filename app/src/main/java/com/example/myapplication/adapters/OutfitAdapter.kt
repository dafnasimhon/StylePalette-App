package com.example.myapplication.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
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

            holder.btnLike.setImageResource(R.drawable.ic_heart_tool_bar)
            holder.btnLike.tag = false

            repository.isOutfitLiked(outfit.id) { isLiked ->
                if (isLiked) {
                    holder.btnLike.setImageResource(R.drawable.ic_heart_filled)
                    holder.btnLike.tag = true
                }
            }

            holder.btnLike.setOnClickListener {
                val currentStatus = holder.btnLike.tag as? Boolean ?: false
                val newStatus = !currentStatus

                repository.toggleLike(outfit.id, newStatus) { success ->
                    if (success) {
                        holder.btnLike.tag = newStatus
                        holder.btnLike.setImageResource(
                            if (newStatus) R.drawable.ic_heart_filled
                            else R.drawable.ic_heart_tool_bar
                        )
                    }
                }
            }
        } else {
            holder.btnLike.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            onItemClick(outfit)
        }
    }

    override fun getItemCount(): Int = outfits.size

    fun updateData(newOutfits: List<Outfit>) {
        this.outfits = newOutfits
        notifyDataSetChanged()
    }
}