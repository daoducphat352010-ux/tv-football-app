package com.customplayer

data class StreamOption(
    val label: String,
    val url: String,
    val referer: String? = null
)

data class MatchCard(
    val id: String,
    val league: String,
    val status: String,
    val kickoff: String,
    val homeName: String,
    val homeLogo: String?,
    val awayName: String,
    val awayLogo: String?,
    val score: String,
    val streams: List<StreamOption>
)

data class LoadedFeed(
    val title: String,
    val updatedAt: String,
    val matches: List<MatchCard>
)
