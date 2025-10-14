package org.simplepoint.plugin.core

enum class ConfigFileTypes(vararg suffix: String) {
    PROPERTIES("properties"),
    YAML("yaml", "yml"),
    TOML("toml", "tml"),
    JSON("json"),
    XML("xml");

    private val suffixes: Array<out String> = suffix;

    companion object {
        fun getFileType(fileName: String): ConfigFileTypes {
            for (fileType in entries) {
                for (suffix in fileType.suffixes) {
                    if (fileName.endsWith(suffix)) {
                        return fileType
                    }
                }
            }
            throw IllegalArgumentException("Unsupported file type $fileName")
        }
    }
}