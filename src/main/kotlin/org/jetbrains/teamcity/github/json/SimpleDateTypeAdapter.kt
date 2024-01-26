

package org.jetbrains.teamcity.github.json

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.*

object SimpleDateTypeAdapter : TypeAdapter<Date>() {
    override fun read(reader: JsonReader): Date? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return Date(reader.nextLong())
    }

    override fun write(out: JsonWriter, value: Date?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.value(value.time)
    }
}