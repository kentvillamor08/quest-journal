package shim;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Minimal reflection runner for @Test / @BeforeEach / @TempDir (sandbox only). */
public final class ShimRunner {
    public static void main(String[] classNames) throws Exception {
        int passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (String cn : classNames) {
            Class<?> c = Class.forName(cn);
            List<Method> tests = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) if (m.isAnnotationPresent(Test.class)) tests.add(m);
            tests.sort(Comparator.comparing(Method::getName));
            for (Method m : tests) {
                Path tmp = null;
                try {
                    Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true);
                    Object inst = k.newInstance();
                    for (Method b : c.getDeclaredMethods()) if (b.isAnnotationPresent(BeforeEach.class)) { b.setAccessible(true); b.invoke(inst); }
                    m.setAccessible(true);
                    Object[] args = new Object[m.getParameterCount()];
                    for (int i = 0; i < args.length; i++) {
                        if (m.getParameters()[i].isAnnotationPresent(TempDir.class)) { tmp = Files.createTempDirectory("qj-test-"); args[i] = tmp; }
                    }
                    m.invoke(inst, args);
                    passed++;
                    System.out.println("PASS " + c.getSimpleName() + "." + m.getName());
                } catch (InvocationTargetException e) {
                    failed++;
                    Throwable t = e.getCause();
                    String line = "FAIL " + c.getSimpleName() + "." + m.getName() + " -> " + t;
                    System.out.println(line);
                    StackTraceElement[] st = t.getStackTrace();
                    for (int i = 0; i < Math.min(4, st.length); i++) System.out.println("      at " + st[i]);
                    failures.add(line);
                } catch (Throwable t) {
                    failed++;
                    System.out.println("ERROR " + c.getSimpleName() + "." + m.getName() + " -> " + t);
                    failures.add(t.toString());
                } finally {
                    if (tmp != null) try (var w = Files.walk(tmp)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignored) { }
                }
            }
        }
        System.out.println("\nTests run: " + (passed + failed) + ", passed: " + passed + ", failed: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
