/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.util.variable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Various utility methods that don't have more proper home.
 */
public class Util {

    /**
     * Concatenate multiple strings by inserting a separator.
     */
    public static String join(Collection<?> strings, String separator) {
        StringBuilder buf = new StringBuilder();
        boolean first = true;
        for (Object s : strings) {
            if (first)
                first = false;
            else
                buf.append(separator);
            buf.append(s);
        }
        return buf.toString();
    }

    /**
     * Combines all the given collections into a single list.
     */
    public static <T> List<T> join(Collection<? extends T>... items) {
        int size = 0;
        for (Collection<? extends T> item : items)
            size += item.size();
        List<T> r = new ArrayList<T>(size);
        for (Collection<? extends T> item : items)
            r.addAll(item);
        return r;
    }

    /**
     * Pattern for capturing variables. Either $xyz, ${xyz} or ${a.b} but not $a.b, while ignoring "$$"
      */
    private static final Pattern VARIABLE = Pattern
                                              .compile("\\$([A-Za-z0-9_]+|\\{[A-Za-z0-9_.]+\\}|\\$)");

    /**
     * Replaces the occurrence of '$key' by <tt>properties.get('key')</tt>.
     *
     * <p>
     * Unlike shell, undefined variables are left as-is (this behavior is the same as Ant.)
     *
     */
    public static String replaceMacro(String s, Map<String, String> properties) {
        return replaceMacro(s, new VariableResolver.ByMap<String>(properties));
    }

    /**
     * Replaces the occurrence of '$key' by <tt>resolver.get('key')</tt>.
     *
     * <p>
     * Unlike shell, undefined variables are left as-is (this behavior is the same as Ant.)
     */
    public static String replaceMacro(String s, VariableResolver<String> resolver) {
        if (s == null) {
            return null;
        }

        int idx = 0;
        while (true) {
            Matcher m = VARIABLE.matcher(s);
            if (!m.find(idx))
                return s;

            String key = m.group().substring(1);

            // escape the dollar sign or get the key to resolve
            String value;
            if (key.charAt(0) == '$') {
                value = "$";
            } else {
                if (key.charAt(0) == '{')
                    key = key.substring(1, key.length() - 1);
                value = resolver.resolve(key);
            }

            if (value == null)
                idx = m.end(); // skip this
            else {
                s = s.substring(0, m.start()) + value + s.substring(m.end());
                idx = m.start() + value.length();
            }
        }
    }
}