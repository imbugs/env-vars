/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Red Hat, Inc.
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
package org.hudson.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Environment variables.
 *
 * <p>
 * While all the platforms I tested (Linux 2.6, Solaris, and Windows XP) have the case sensitive
 * environment variable table, Windows batch script handles environment variable in the case preserving
 * but case <b>insensitive</b> way (that is, cmd.exe can get both FOO and foo as environment variables
 * when it's launched, and the "set" command will display it accordingly, but "echo %foo%" results in
 * echoing the value of "FOO", not "foo" &mdash; this is presumably caused by the behavior of the underlying
 * Win32 API <tt>GetEnvironmentVariable</tt> acting in case insensitive way.) Windows users are also
 * used to write environment variable case-insensitively (like %Path% vs %PATH%), and you can see many
 * documents on the web that claims Windows environment variables are case insensitive.
 *
 * <p>
 * So for a consistent cross platform behavior, it creates the least confusion to make the table
 * case insensitive but case preserving.
 *
 * <p>
 * In Jenkins, often we need to build up "environment variable overrides"
 * on master, then to execute the process on agents. This causes a problem
 * when working with variables like <tt>PATH</tt>. So to make this work,
 * we introduce a special convention <tt>PATH+FOO</tt> &mdash; all entries
 * that starts with <tt>PATH+</tt> are merged and prepended to the inherited
 * <tt>PATH</tt> variable, on the process where a new process is executed. 
 */
public class EnvVars extends TreeMap<String, String> {
    private static final long serialVersionUID = 1L;
    private static Logger     LOGGER           = Logger.getLogger(EnvVars.class.getName());

    public EnvVars() {
        super(CaseInsensitiveComparator.INSTANCE);
    }

    public EnvVars(Map<String, String> m) {
        this();
        putAll(m);
    }

    public EnvVars(EnvVars m) {
        // this constructor is so that in future we can get rid of the downcasting.
        this((Map<String, String>) m);
    }

    /**
     * Builds an environment variables from an array of the form <tt>"key","value","key","value"...</tt>
     */
    public EnvVars(String... keyValuePairs) {
        this();
        if (keyValuePairs.length % 2 != 0)
            throw new IllegalArgumentException(Arrays.asList(keyValuePairs).toString());
        for (int i = 0; i < keyValuePairs.length; i += 2)
            put(keyValuePairs[i], keyValuePairs[i + 1]);
    }

    /**
     * Overrides the current entry by the given entry.
     *
     * <p>
     * Handles <tt>PATH+XYZ</tt> notation.
     */
    public void override(String key, String value) {
        if (value == null || value.length() == 0) {
            remove(key);
            return;
        }

        int idx = key.indexOf('+');
        if (idx > 0) {
            String realKey = key.substring(0, idx);
            String v = get(realKey);
            if (v == null)
                v = value;
            else {
                // we might be handling environment variables for a agent that can have different path separator
                // than the master, so the following is an attempt to get it right.
                // it's still more error prone that I'd like.
                char ch = File.pathSeparatorChar;
                v = value + ch + v;
            }
            put(realKey, v);
            return;
        }

        put(key, value);
    }

    /**
     * Overrides all values in the map by the given map.
     * See {@link #override(String, String)}.
     * @return this
     */
    public EnvVars overrideAll(Map<String, String> all) {
        for (Map.Entry<String, String> e : all.entrySet()) {
            override(e.getKey(), e.getValue());
        }
        return this;
    }

    /**
     * Calculates the order to override variables.
     * 
     * Sort variables with topological sort with their reference graph.
     * 
     * This is package accessible for testing purpose.
     */
    static class OverrideOrderCalculator {
        /**
         * Extract variables referred directly from a variable.
         */
        private static class TraceResolver implements VariableResolver<String> {
            private final Comparator<? super String> comparator;
            public Set<String>                       referredVariables;

            public TraceResolver(Comparator<? super String> comparator) {
                this.comparator = comparator;
                clear();
            }

            public void clear() {
                referredVariables = new TreeSet<String>(comparator);
            }

            public String resolve(String name) {
                referredVariables.add(name);
                return "";
            }
        }

        private static class VariableReferenceSorter extends CyclicGraphDetector<String> {
            // map from a variable to a set of variables that variable refers.
            private final Map<String, Set<String>> refereeSetMap;

            public VariableReferenceSorter(Map<String, Set<String>> refereeSetMap) {
                this.refereeSetMap = refereeSetMap;
            }

            @Override
            protected Iterable<? extends String> getEdges(String n) {
                // return variables referred from the variable.
                if (!refereeSetMap.containsKey(n)) {
                    // there is a case a non-existing variable is referred...
                    return Collections.emptySet();
                }
                return refereeSetMap.get(n);
            }
        };

        private final Comparator<? super String> comparator;

        private final EnvVars                    target;
        private final Map<String, String>        overrides;

        private Map<String, Set<String>>         refereeSetMap;
        private List<String>                     orderedVariableNames;

        public OverrideOrderCalculator(EnvVars target, Map<String, String> overrides) {
            comparator = target.comparator();
            this.target = target;
            this.overrides = overrides;
            scan();
        }

        public List<String> getOrderedVariableNames() {
            return orderedVariableNames;
        }

        // Cut the reference to the variable in a cycle.
        private void cutCycleAt(String referee, List<String> cycle) {
            // cycle contains variables in referrer-to-referee order.
            // This should not be negative, for the first and last one is same.
            int refererIndex = cycle.lastIndexOf(referee) - 1;

            assert (refererIndex >= 0);
            String referrer = cycle.get(refererIndex);
            boolean removed = refereeSetMap.get(referrer).remove(referee);
            assert (removed);
            LOGGER
                .warning(String.format("Cyclic reference detected: %s", Util.join(cycle, " -> ")));
            LOGGER.warning(String.format("Cut the reference %s -> %s", referrer, referee));
        }

        // Cut the variable reference in a cycle.
        private void cutCycle(List<String> cycle) {
            // if an existing variable is contained in that cycle,
            // cut the cycle with that variable:
            // existing:
            //   PATH=/usr/bin
            // overriding:
            //   PATH1=/usr/local/bin:${PATH}
            //   PATH=/opt/something/bin:${PATH1}
            // then consider reference PATH1 -> PATH can be ignored.
            for (String referee : cycle) {
                if (target.containsKey(referee)) {
                    cutCycleAt(referee, cycle);
                    return;
                }
            }

            // if not, cut the reference to the first one.
            cutCycleAt(cycle.get(0), cycle);
        }

        /**
         * Scan all variables and list all referring variables.
         */
        public void scan() {
            refereeSetMap = new TreeMap<String, Set<String>>(comparator);
            List<String> extendingVariableNames = new ArrayList<String>();

            TraceResolver resolver = new TraceResolver(comparator);

            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                if (entry.getKey().indexOf('+') > 0) {
                    // XYZ+AAA variables should be always processed in last.
                    extendingVariableNames.add(entry.getKey());
                    continue;
                }
                resolver.clear();
                Util.replaceMacro(entry.getValue(), resolver);

                // Variables directly referred from the current scanning variable.
                Set<String> refereeSet = resolver.referredVariables;
                // Ignore self reference.
                refereeSet.remove(entry.getKey());
                refereeSetMap.put(entry.getKey(), refereeSet);
            }

            VariableReferenceSorter sorter;
            while (true) {
                sorter = new VariableReferenceSorter(refereeSetMap);
                try {
                    sorter.run(refereeSetMap.keySet());
                } catch (CycleDetectedException e) {
                    // cyclic reference found.
                    // cut the cycle and retry.
                    @SuppressWarnings("unchecked")
                    List<String> cycle = (List<String>) e.cycle;
                    cutCycle(cycle);
                    continue;
                }
                break;
            }

            // When A refers B, the last appearance of B always comes after
            // the last appearance of A.
            List<String> reversedDuplicatedOrder = new ArrayList<String>(sorter.getSorted());
            Collections.reverse(reversedDuplicatedOrder);

            orderedVariableNames = new ArrayList<String>(overrides.size());
            for (String key : reversedDuplicatedOrder) {
                if (overrides.containsKey(key) && !orderedVariableNames.contains(key)) {
                    orderedVariableNames.add(key);
                }
            }
            Collections.reverse(orderedVariableNames);
            orderedVariableNames.addAll(extendingVariableNames);
        }
    }

    /**
     * Overrides all values in the map by the given map. Expressions in values will be expanded.
     * See {@link #override(String, String)}.
     * @return this
     */
    public EnvVars overrideExpandingAll(Map<String, String> all) {
        for (String key : new OverrideOrderCalculator(this, all).getOrderedVariableNames()) {
            override(key, expand(all.get(key)));
        }
        return this;
    }

    /**
     * Resolves environment variables against each other.
     */
    public static void resolve(Map<String, String> env) {
        for (Map.Entry<String, String> entry : env.entrySet()) {
            entry.setValue(Util.replaceMacro(entry.getValue(), env));
        }
    }

    /**
     * Convenience message
     * @since 1.485
     **/
    public String get(String key, String defaultValue) {
        String v = get(key);
        if (v == null)
            v = defaultValue;
        return v;
    }

    @Override
    public String put(String key, String value) {
        if (value == null)
            throw new IllegalArgumentException(
                "Null value not allowed as an environment variable: " + key);
        return super.put(key, value);
    }

    /**
     * Add a key/value but only if the value is not-null. Otherwise no-op.
     * @since 1.556
     */
    public void putIfNotNull(String key, String value) {
        if (value != null)
            put(key, value);
    }

    /**
     * Takes a string that looks like "a=b" and adds that to this map.
     */
    public void addLine(String line) {
        int sep = line.indexOf('=');
        if (sep > 0) {
            put(line.substring(0, sep), line.substring(sep + 1));
        }
    }

    /**
     * Expands the variables in the given string by using environment variables represented in 'this'.
     */
    public String expand(String s) {
        return Util.replaceMacro(s, this);
    }

    public static void main(String[] args) {
        Map<String, String> props = new HashMap<String, String>();
        props.put("A", "val1");
        props.put("B", "$A is good");
        props.put("C", "${B} and best");

        EnvVars.resolve(props);

        System.out.println(props);
        EnvVars envVars = new EnvVars(props);
        String todo = "${C} is goo";
        EnvVars envVarsx = envVars.overrideExpandingAll(null);
        envVarsx.expand(todo);
    }
}
