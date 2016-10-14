package org.jenkins.util.variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.jenkins.util.variable.CyclicGraphDetector.CycleDetectedException;

/**
 * Calculates the order to override variables.
 * 
 * Sort variables with topological sort with their reference graph.
 * 
 * This is package accessible for testing purpose.
 */
public class OverrideOrderCalculator {
    private static Logger LOGGER = Logger.getLogger(EnvVars.class.getName());

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
        LOGGER.warning(String.format("Cyclic reference detected: %s", Util.join(cycle, " -> ")));
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
