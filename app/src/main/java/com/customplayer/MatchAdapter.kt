package com.customplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MatchAdapter(
    private val repo: FeedRepository,
    private val onMatchClick: (MatchCard) -> Unit
) : RecyclerView.Adapter<MatchAdapter.MatchVH>() {

    private val items = mutableListOf<MatchCard>()
    private val uiScope = CoroutineScope(Dispatchers.Main)

    class MatchVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvStatus: TextView = view.findViewById(R.id.tvMatchStatus)
        val tvLeague: TextView = view.findViewById(R.id.tvMatchLeague)
        val tvKickoff: TextView = view.findViewById(R.id.tvMatchKickoff)
        val ivHome: ImageView = view.findViewById(R.id.ivHomeLogo)
        val tvHome: TextView = view.findViewById(R.id.tvHomeName)
        val tvScore: TextView = view.findViewById(R.id.tvScore)
        val ivAway: ImageView = view.findViewById(R.id.ivAwayLogo)
        val tvAway: TextView = view.findViewById(R.id.tvAwayName)
        val tvStreams: TextView = view.findViewById(R.id.tvStreamCount)
        var loadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_match, parent, false)
        return MatchVH(v)
    }

    override fun onBindViewHolder(holder: MatchVH, position: Int) {
        val m = items[position]
        holder.loadJob?.cancel()

        val isLive = m.status.equals("LIVE", ignoreCase = true)
        holder.tvStatus.text = if (isLive) "LIVE" else "SẮP ĐÁ"
        holder.tvStatus.setBackgroundColor(
            if (isLive) Color.parseColor("#DC2626") else Color.parseColor("#1E3A5F")
        )

        holder.tvLeague.text = m.league
        holder.tvKickoff.text = m.kickoff
        holder.tvHome.text = m.homeName
        holder.tvAway.text = m.awayName
        holder.tvScore.text = m.score.uppercase()
        holder.tvStreams.text = "${m.streams.size} nguồn phát"

        holder.ivHome.setImageDrawable(null)
        holder.ivAway.setImageDrawable(null)

        holder.loadJob = uiScope.launch {
            val hBmp = repo.fetchBitmap(m.homeLogo)
            if (hBmp != null) holder.ivHome.setImageBitmap(hBmp)
            val aBmp = repo.fetchBitmap(m.awayLogo)
            if (aBmp != null) holder.ivAway.setImageBitmap(aBmp)
        }

        holder.itemView.setOnClickListener { onMatchClick(m) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.03f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitMatches(list: List<MatchCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
