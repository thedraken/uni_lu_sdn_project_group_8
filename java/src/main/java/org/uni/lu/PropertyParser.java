package org.uni.lu;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

public class PropertyParser {

    public static boolean boolProperty(Dictionary<?, ?> properties, @SuppressWarnings("SameParameterValue") String name, boolean current) {
        Object value = properties.get(name);
        if (value == null) {
            return current;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    public static Map<String, Role> parseRoleAssignments(Object raw) {
        //Accepts "user:role,user:role,...". DS may hand us the commas already
        //split into a String[], so cope with both that and a plain String.
        Map<String, Role> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        String[] pairs = (raw instanceof String[])
                ? (String[]) raw
                : raw.toString().split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                Role role = Role.fromName(kv[1]);
                if (role != null) {
                    map.put(kv[0].trim(), role);
                }
            }
        }
        return map;
    }

    public static int intProperty(Dictionary<?, ?> properties, String name, int current) {
        Object value = properties.get(name);
        if (value == null) {
            return current;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return current;
        }
    }

    public static double doubleProperty(Dictionary<?, ?> properties, String name, double current) {
        Object value = properties.get(name);
        if (value == null) {
            return current;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return current;
        }
    }
}
