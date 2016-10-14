package org.jenkins.util.variable;

import java.io.Serializable;
import java.util.Comparator;

public final class CaseInsensitiveComparator implements Comparator<String>, Serializable {
    public static final Comparator<String> INSTANCE = new CaseInsensitiveComparator();

    private CaseInsensitiveComparator() {
    }

    public int compare(String lhs, String rhs) {
        return lhs.compareToIgnoreCase(rhs);
    }

    private static final long serialVersionUID = 1L;
}
