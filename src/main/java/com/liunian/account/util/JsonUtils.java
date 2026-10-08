package com.liunian.account.util;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonUtils {

    private static final ObjectMapper OM = new ObjectMapper();

    private JsonUtils() {
    }

    public static String toJson(Object o) {
        try {
            return OM.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
