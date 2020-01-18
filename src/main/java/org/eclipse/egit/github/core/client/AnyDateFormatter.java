/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
