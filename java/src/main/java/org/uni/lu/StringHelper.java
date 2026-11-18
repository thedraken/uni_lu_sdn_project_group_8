package org.uni.lu;

import java.util.Collection;

public class StringHelper {

    public static String collectionToString(Collection<?> collection, String separator) {
        StringBuilder sb = new StringBuilder();
        collection.forEach(c-> {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(c.toString());
        });
        return sb.toString();
    }
}
