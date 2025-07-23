package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.*

@Serializable
data class BannedModVersionJson(val jar: String, val illegalTimestamp: Int)
