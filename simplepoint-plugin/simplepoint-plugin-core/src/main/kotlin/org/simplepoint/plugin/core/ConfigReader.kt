package org.simplepoint.plugin.core

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.io.File

class ConfigReader {
    companion object {
        inline fun <reified T : Any> read(path: String): T = read(File(path))
        inline fun <reified T : Any> read(file: File): T = createObjectMapper(file).readValue(file, T::class.java)

        fun <T : Any> read(file: File, type: Class<T>): T = createObjectMapper(file).readValue(file, type)


        fun <T : Any> read(path: String, type: Class<T>): T = read(File(path), type)


        fun createObjectMapper(file: File): ObjectMapper = when (ConfigFileTypes.getFileType(file.extension)) {
            ConfigFileTypes.YAML -> YAMLMapper()
            ConfigFileTypes.TOML -> TomlMapper()
            ConfigFileTypes.JSON -> JsonMapper()
            ConfigFileTypes.XML -> XmlMapper()
            else -> ObjectMapper()
        }.setSerializationInclusion(JsonInclude.Include.NON_NULL).enable(SerializationFeature.INDENT_OUTPUT)
    }
}