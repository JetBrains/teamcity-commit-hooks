package org.eclipse.egit.github.core.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Type;
import java.util.Date;

/**
 * Improved {@link DateFormatter} with timestamp deserialization support
 */
public class AnyDateFormatter extends DateFormatter {

    @Override
    public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json instanceof JsonPrimitive) {
            final JsonPrimitive primitive = (JsonPrimitive) json;
            if (primitive.isNumber()) {
                long timestamp = json.getAsLong();
                if (timestamp > 10000000000L) {
                    return new Date(timestamp);
                }
                return new Date(timestamp * 1000);
            }
        }
        return super.deserialize(json, typeOfT, context);
    }
}
