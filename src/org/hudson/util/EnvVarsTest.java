package org.hudson.util;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.hudson.util.EnvVars.OverrideOrderCalculator;

public class EnvVarsTest {

    @Test
    public void caseInsensitive() {
        EnvVars ev = new EnvVars(Collections.singletonMap("Path", "A:B:C"));
        assertTrue(ev.containsKey("PATH"));
        assertEquals("A:B:C", ev.get("PATH"));
    }

    @Test
    public void overrideExpandingAll() {
        EnvVars env = new EnvVars();
        env.put("PATH", "orig");
        env.put("A", "Value1");

        EnvVars overrides = new EnvVars();
        overrides.put("PATH", "append" + File.pathSeparator + "${PATH}");
        overrides.put("B", "${A}Value2");
        overrides.put("C", "${B}${D}");
        overrides.put("D", "${E}");
        overrides.put("E", "Value3");
        overrides.put("PATH+TEST", "another");

        env.overrideExpandingAll(overrides);

        assertEquals("Value1Value2Value3", env.get("C"));
        assertEquals("another" + File.pathSeparator + "append" + File.pathSeparator + "orig",
            env.get("PATH"));
    }

    @Test
    public void overrideOrderCalculatorSimple() {
        EnvVars env = new EnvVars();
        EnvVars overrides = new EnvVars();
        overrides.put("A", "NoReference");
        overrides.put("A+B", "NoReference");
        overrides.put("B", "Refer1${A}");
        overrides.put("C", "Refer2${B}");
        overrides.put("D", "Refer3${B}${Nosuch}");

        OverrideOrderCalculator calc = new OverrideOrderCalculator(env, overrides);

        List<String> order = calc.getOrderedVariableNames();
        assertEquals(Arrays.asList("A", "B", "C", "D", "A+B"), order);
    }

    @Test
    public void overrideOrderCalculatorInOrder() {
        EnvVars env = new EnvVars();
        EnvVars overrides = new EnvVars();
        overrides.put("A", "NoReference");
        overrides.put("B", "${A}");
        overrides.put("C", "${B}");
        overrides.put("D", "${E}");
        overrides.put("E", "${C}");

        OverrideOrderCalculator calc = new OverrideOrderCalculator(env, overrides);
        List<String> order = calc.getOrderedVariableNames();
        assertEquals(Arrays.asList("A", "B", "C", "E", "D"), order);
    }

    @Test
    public void overrideOrderCalculatorMultiple() {
        EnvVars env = new EnvVars();
        EnvVars overrides = new EnvVars();
        overrides.put("A", "Noreference");
        overrides.put("B", "${A}");
        overrides.put("C", "${A}${B}");

        OverrideOrderCalculator calc = new OverrideOrderCalculator(env, overrides);
        List<String> order = calc.getOrderedVariableNames();
        assertEquals(Arrays.asList("A", "B", "C"), order);
    }

    @Test
    public void overrideOrderCalculatorSelfReference() {
        EnvVars env = new EnvVars();
        EnvVars overrides = new EnvVars();
        overrides.put("PATH", "some;${PATH}");

        OverrideOrderCalculator calc = new OverrideOrderCalculator(env, overrides);
        List<String> order = calc.getOrderedVariableNames();
        assertEquals(Arrays.asList("PATH"), order);
    }

    @Test
    public void overrideOrderCalculatorCyclic() {
        EnvVars env = new EnvVars();
        env.put("C", "Existing");
        EnvVars overrides = new EnvVars();
        overrides.put("A", "${B}");
        overrides.put("B", "${C}"); // This will be ignored.
        overrides.put("C", "${A}");

        overrides.put("D", "${C}${E}");
        overrides.put("E", "${C}${D}");

        OverrideOrderCalculator calc = new OverrideOrderCalculator(env, overrides);
        List<String> order = calc.getOrderedVariableNames();
        assertEquals(Arrays.asList("B", "A", "C"), order.subList(0, 3));
        assertEquals(Sets.newHashSet("E", "D"), new HashSet<String>(order.subList(3, order.size())));
    }
}