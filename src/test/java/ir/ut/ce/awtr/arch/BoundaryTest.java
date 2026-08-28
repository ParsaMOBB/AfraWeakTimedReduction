package ir.ut.ce.awtr.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the boundaries the design depends on.
 *
 * <p>The reduction domain and algorithm must not know about XML, the command
 * line, Eclipse or Afra's UI. That is what makes it possible to add an
 * in-process Afra adapter later without touching the core, so it is checked
 * rather than merely asserted in prose.
 */
@DisplayName("architecture boundaries")
class BoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "ir", "ut", "ce", "awtr");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+);");

    /** Packages that must stay free of any I/O, XML or CLI knowledge. */
    private static final List<String> CORE_PACKAGES =
            List.of("tts", "weak", "quotient", "verify");

    /** Package prefixes the core is not allowed to reach for. */
    private static final List<String> FORBIDDEN_IN_CORE = List.of(
            "javax.xml", "org.w3c", "org.xml", "java.nio.file", "java.io",
            "org.eclipse", "org.rebecalang", "ir.ut.ce.awtr.afra",
            "ir.ut.ce.awtr.app", "ir.ut.ce.awtr.report");

    private record JavaFile(Path path, String text) {
        String pkg() {
            return path.getParent().getFileName().toString();
        }
    }

    private static List<JavaFile> sources() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            List<JavaFile> files = new ArrayList<>();
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                files.add(new JavaFile(path, Files.readString(path, StandardCharsets.UTF_8)));
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> importsOf(JavaFile file) {
        List<String> imports = new ArrayList<>();
        for (String line : file.text().split("\n")) {
            Matcher matcher = IMPORT.matcher(line.trim());
            if (matcher.find()) {
                imports.add(matcher.group(2));
            }
        }
        return imports;
    }

    @Test
    @DisplayName("the domain and algorithm never import XML, file, CLI or Afra classes")
    void coreIsIndependent() {
        List<String> violations = new ArrayList<>();
        for (JavaFile file : sources()) {
            if (!CORE_PACKAGES.contains(file.pkg())) {
                continue;
            }
            for (String imported : importsOf(file)) {
                FORBIDDEN_IN_CORE.stream()
                        .filter(imported::startsWith)
                        .forEach(bad -> violations.add(
                                file.path() + " imports " + imported + " (forbidden: " + bad + ")"));
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("only the afra package knows the state-space XML dialect")
    void xmlIsConfinedToTheAdapter() {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : sources()) {
            boolean mentionsStax = importsOf(file).stream().anyMatch(i -> i.startsWith("javax.xml"));
            if (mentionsStax && !file.pkg().equals("afra")) {
                offenders.add(file.path().toString());
            }
        }
        assertEquals(List.of(), offenders,
                "XML parsing belongs behind the adapter boundary");
    }

    @Test
    @DisplayName("nothing outside the app package starts a process or reads System.in")
    void noHiddenProcessExecution() {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : sources()) {
            if (file.pkg().equals("app")) {
                continue;
            }
            if (file.text().contains("ProcessBuilder") || file.text().contains("System.in")) {
                offenders.add(file.path().toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    @DisplayName("the source tree carries no runtime dependency outside the JDK")
    void noThirdPartyRuntimeDependencies() {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : sources()) {
            for (String imported : importsOf(file)) {
                boolean jdk = imported.startsWith("java.") || imported.startsWith("javax.");
                boolean own = imported.startsWith("ir.ut.ce.awtr");
                if (!jdk && !own) {
                    offenders.add(file.path() + " -> " + imported);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "a dependency-free jar is what keeps a future Afra plug-in simple:\n"
                        + String.join("\n", offenders));
    }
}
