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
    // תיקון: הגדרת סוג המשתנה כ-OutfitRepository (ה-object) וקביעת ברירת מחדל ללא סוגריים ()
    private val repository: OutfitRepository = OutfitRepository,
    private val onItemClick: (Outfit) -> Unit
) : RecyclerView.Adapter<OutfitAdapter.OutfitViewHolder>() {

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

        // טעינת התמונה ישירות מה-imageUrl הקיים באובייקט
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
        val outfitId = outfit.id.trim()

        // בדיקה דינמית מול ה-Repository האם האאוטפיט כבר מסומן בלייק על ידי המשתמש
        repository.isOutfitLiked(outfitId) { isLiked ->
            holder.btnLike.tag = isLiked
            holder.btnLike.setImageResource(
                if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_tool_bar
            )
        }

        holder.btnLike.setOnClickListener {
            if (outfitId.isEmpty()) {
                Log.e("StylePalette_Like", "heart tap ignored: outfit has no document id")
                val ctx = holder.itemView.context
                Toast.makeText(ctx, "Outfit id missing", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentStatus = holder.btnLike.tag as? Boolean ?: false
            val newStatus = !currentStatus
            Log.i("StylePalette_Like", "heart tap outfitId=$outfitId -> $newStatus")

            // עדכון ה-UI באופן מידי כדי לתת חיוו מהיר למשתמש
            holder.btnLike.tag = newStatus
            holder.btnLike.setImageResource(
                if (newStatus) R.drawable.ic_heart_filled else R.drawable.ic_heart_tool_bar
            )

            // הפעלת הפונקציה המעודכנת מה-Repository החדש שמקבלת Callback של (Boolean)
            repository.toggleLike(outfitId, newStatus) { success ->
                if (success) return@toggleLike

                // במקרה של כישלון, נחזיר את המצב הקיים לאחור
                holder.btnLike.tag = currentStatus
                holder.btnLike.setImageResource(
                    if (currentStatus) R.drawable.ic_heart_filled else R.drawable.ic_heart_tool_bar
                )
                val ctx = holder.itemView.context
                Toast.makeText(ctx, "Failed to update like status", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount(): Int = outfits.size

    fun updateData(newOutfits: List<Outfit>) {
        this.outfits = newOutfits
        notifyDataSetChanged()
    }
}