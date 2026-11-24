package com.customplayer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MatchGridAdapter(
    private val scope: CoroutineScope,
    private val repository: FeedRepository,
    private val onSelectMatch: (MatchCard) -> Unit
) : RecyclerView.Adapter<MatchGridAdapter.Holder>() {

    private val items = mutableListOf<MatchCard>()

    fun submit(list: List<MatchCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val card = CardView(ctx).apply {
            radius = 16f
            cardElevation = 6f
            setCardBackgroundColor(Color.parseColor("#172131"))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(10, 10, 10, 10)
            }
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(ctx).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(10, 3, 10, 3)
        }

        val league = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 10
            }
        }

        val kickoff = TextView(ctx).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        topRow.addView(badge)
        topRow.addView(league)
        topRow.addView(kickoff)

        val midRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 14
            }
        }

        val homeCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val homeLogo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#243247"))
            layoutParams = LinearLayout.LayoutParams(64, 64)
        }
        val homeName = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
        }
        homeCol.addView(homeLogo)
        homeCol.addView(homeName)

        val score = TextView(ctx).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val awayCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val awayLogo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#243247"))
            layoutParams = LinearLayout.LayoutParams(64, 64)
        }
        val awayName = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
        }
        awayCol.addView(awayLogo)
        awayCol.addView(awayName)

        midRow.addView(homeCol)
        midRow.addView(score)
        midRow.addView(awayCol)

        val meta = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#A5F3FC"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        box.addView(topRow)
        box.addView(midRow)
        box.addView(meta)
        card.addView(box)

        return Holder(card, badge, league, kickoff, homeLogo, homeName, score, awayLogo, awayName, meta)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], scope, repository, onSelectMatch)
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val card: CardView,
        private val badge: TextView,
        private val league: TextView,
        private val kickoff: TextView,
        private val homeLogo: ImageView,
        private val homeName: TextView,
        private val score: TextView,
        private val awayLogo: ImageView,
        private val awayName: TextView,
        private val meta: TextView
    ) : RecyclerView.ViewHolder(card) {

        private var imgJob: Job? = null

        fun bind(
            item: MatchCard,
            scope: CoroutineScope,
            repository: FeedRepository,
            onSelectMatch: (MatchCard) -> Unit
        ) {
            val isLive = item.status == "LIVE"
            badge.text = if (isLive) "LIVE" else item.status
            badge.setTextColor(Color.WHITE)
            badge.background = GradientDrawable().apply {
                cornerRadius = 8f
                setColor(if (isLive) Color.parseColor("#DC2626") else Color.parseColor("#334155"))
            }
            league.text = item.league
            kickoff.text = item.kickoff
            homeName.text = item.homeName
            awayName.text = item.awayName
            score.text = item.score
            meta.text = "${item.streams.size} nguồn phát"

            homeLogo.setImageBitmap(null)
            awayLogo.setImageBitmap(null)
            imgJob?.cancel()
            imgJob = scope.launch {
                repository.fetchBitmap(item.homeLogo)?.let { homeLogo.setImageBitmap(it) }
                repository.fetchBitmap(item.awayLogo)?.let { awayLogo.setImageBitmap(it) }
            }

            card.setOnFocusChangeListener { v: View, hasFocus: Boolean ->
                if (hasFocus) {
                    v.scaleX = 1.04f
                    v.scaleY = 1.04f
                    card.setCardBackgroundColor(Color.parseColor("#1E3A5F"))
                } else {
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    card.setCardBackgroundColor(Color.parseColor("#172131"))
                }
            }

            card.setOnClickListener { onSelectMatch(item) }
        }
    }
}
