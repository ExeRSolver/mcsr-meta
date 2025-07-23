package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.Serializable

@Serializable
class FabricModJson(val version: String, val id: String, var name: String, var description: String = "", val jars: List<File> = listOf()) {

    @Serializable
    data class File(
        val file: String
    )
}
