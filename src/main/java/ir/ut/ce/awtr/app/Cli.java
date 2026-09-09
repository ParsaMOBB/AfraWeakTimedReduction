package ir.ut.ce.awtr.app;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ir.ut.ce.awtr.afra.AfraStateSpaceSource;
import ir.ut.ce.awtr.report.ArtifactWriter;
import ir.ut.ce.awtr.report.StateSpaceDotWriter;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.tts.InvalidModelException;
import ir.ut.ce.awtr.weak.TimeSemantics;
import ir.ut.ce.awtr.weak.UnitDelayRefinement;

/**
 * The standalone interface.
 *
 * <p>A command line rather than a GUI, for one reason: the deliverable has to be
 * driven by an automated test suite from a clean checkout, and every capability
 * has to be reachable without a display. {@link ReductionService} is the actual
 * entry point; this class only parses arguments and chooses an exit code.
 *
 * <pre>{@code
 * awtr reduce MODEL.statespace --observable getSense,activateh --output-dir DIR
 * awtr equivalent A.statespace B.statespace --observable getSense
 * awtr inspect MODEL.statespace
 * awtr visualize MODEL.statespace --output MODEL.dot
 * }</pre>
 *
 * <p>Exit codes are part of the contract:
 * {@code 0} success, {@code 1} a semantic negative (the two models are not
 * equivalent, or verification rejected the quotient), {@code 2} invalid or
 * unsupported input, {@code 3} a violated internal invariant.
 */
public final class Cli {

    public static final int EXIT_OK = 0;
    public static final int EXIT_NEGATIVE = 1;
    public static final int EXIT_INVALID_INPUT = 2;
    public static final int EXIT_INTERNAL = 3;

    static final String VERSION = "1.0.0";

    private final PrintStream out;
    private final PrintStream err;

    Cli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new Cli(System.out, System.err).run(args));
    }

    /** Runs one invocation and returns its exit code, without touching the JVM. */
    public int run(String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            usage(out);
            return args.length == 0 ? EXIT_INVALID_INPUT : EXIT_OK;
        }
        try {
            return switch (args[0]) {
                case "reduce" -> reduce(rest(args));
                case "equivalent" -> equivalent(rest(args));
                case "inspect" -> inspect(rest(args));
                case "visualize" -> visualize(rest(args));
                default -> {
                    err.println("unknown command '" + args[0] + "'");
                    usage(err);
                    yield EXIT_INVALID_INPUT;
                }
            };
        } catch (InvalidModelException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return EXIT_INVALID_INPUT;
        } catch (UncheckedIOException e) {
            err.println("error: " + e.getMessage());
            return EXIT_INVALID_INPUT;
        } catch (RuntimeException e) {
            err.println("internal error: " + e);
            return EXIT_INTERNAL;
        }
    }

    private int reduce(Options options) {
        Path input = options.requirePath(0, "MODEL.statespace");
        Path outputDir = Path.of(options.value("output-dir", "awtr-output"));
        ReductionRequest request = options.request();

        ReductionResult result = ReductionService.reduce(
                new AfraStateSpaceSource(input, options.value("initial-state", null)), request);

        List<Path> written = new ArtifactWriter(outputDir).writeAll(
                result, input.toString(), ArtifactWriter.sha256(input), VERSION, commit());

        out.println("model              " + result.acquired().id());
        out.println("observable actions " + String.join(",", result.observables()));
        out.println("time semantics     " + result.timeSemanticsToken());
        out.println("original           " + result.originalStateCount() + " states, "
                + result.originalTransitionCount() + " transitions");
        out.println("reduced            " + result.reducedStateCount() + " states, "
                + result.reducedTransitionCount() + " transitions");
        out.println(String.format(java.util.Locale.ROOT,
                "reduction          %.1f%% states, %.1f%% transitions",
                100 * result.stateReductionRatio(), 100 * result.transitionReductionRatio()));
        written.forEach(path -> out.println("wrote              " + path));

        if (!result.unmatchedObservables().isEmpty()) {
            err.println("warning: these observable names match nothing in the model: "
                    + String.join(",", result.unmatchedObservables()));
        }
        if (!result.verified()) {
            err.println("VERIFICATION FAILED");
            result.verification().violations().forEach(err::println);
            return EXIT_NEGATIVE;
        }
        out.println("verification       " + (result.verification() == null
                ? "skipped" : result.verification().summary()));
        return EXIT_OK;
    }

    private int equivalent(Options options) {
        Path left = options.requirePath(0, "A.statespace");
        Path right = options.requirePath(1, "B.statespace");
        ReductionRequest request = options.request();

        ReductionService.ComparisonResult result = ReductionService.compare(
                new AfraStateSpaceSource(left), new AfraStateSpaceSource(right), request);

        out.println(result.bisimilar() ? "WEAK_TIMED_BISIMILAR" : "NOT_WEAK_TIMED_BISIMILAR");
        out.println("left               " + result.left().stateCount() + " states");
        out.println("right              " + result.right().stateCount() + " states");
        out.println("classes            " + result.partition().blockCount());
        return result.bisimilar() ? EXIT_OK : EXIT_NEGATIVE;
    }

    private int inspect(Options options) {
        Path input = options.requirePath(0, "MODEL.statespace");
        var model = new AfraStateSpaceSource(input).load();
        out.println("model              " + model.id());
        out.println("states             " + model.states().size());
        out.println("transitions        " + model.transitions().size());
        out.println("initial state      " + model.initialState());
        out.println("delay durations    " + model.delays());
        out.println("message servers");
        model.actions().stream()
                .map(a -> "  " + a.qualifiedName() + "  (sender=" + a.sender() + ")")
                .distinct()
                .forEach(out::println);
        return EXIT_OK;
    }

    private int visualize(Options options) {
        Path input = options.requirePath(0, "MODEL.statespace");
        var model = new AfraStateSpaceSource(
                input, options.value("initial-state", null)).load();
        String dot = new StateSpaceDotWriter().render(model);
        String requestedOutput = options.value("output", null);

        if ("-".equals(requestedOutput)) {
            out.print(dot);
            return EXIT_OK;
        }

        Path output = requestedOutput == null
                ? replaceExtension(input, "dot")
                : Path.of(requestedOutput);
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, dot, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + output, e);
        }

        out.println("model              " + model.id());
        out.println("states             " + model.states().size());
        out.println("transitions        " + model.transitions().size());
        out.println("wrote              " + output);
        return EXIT_OK;
    }

    private static Path replaceExtension(Path input, String extension) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return input.resolveSibling(base + "." + extension);
    }

    private static boolean isHelp(String token) {
        return "-h".equals(token) || "--help".equals(token) || "help".equals(token);
    }

    private static Options rest(String[] args) {
        return Options.parse(args, 1);
    }

    /** Best-effort provenance; absent outside a git checkout. */
    private static String commit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String value = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 && !value.isEmpty() ? value : "unknown";
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static void usage(PrintStream stream) {
        stream.println("""
                awtr - reduce an Afra Timed Rebeca TTS modulo weak timed bisimilarity

                  awtr reduce MODEL.statespace --observable NAME[,NAME...] [options]
                  awtr equivalent A.statespace B.statespace --observable NAME[,NAME...] [options]
                  awtr inspect MODEL.statespace
                  awtr visualize MODEL.statespace [--output FILE|-]

                options
                  --observable NAME[,NAME...]  message servers to keep visible; every other
                                               interaction is hidden as tau. A bare name matches
                                               any owner; 'owner.name' matches one owner.
                  --output-dir DIR             where to write the artefacts (default awtr-output)
                  --initial-state ID           override the first <state> in the export
                  --time-semantics unit|strict whether a d-unit delay may be observed part way
                                               through (default unit)
                  --max-intermediate-states N  cap on states added by unit refinement
                  --no-verify                  skip the independent quotient check
                  --output FILE|-              visualization DOT path; '-' writes to stdout

                exit codes
                  0 success   1 not equivalent / verification failed
                  2 invalid input   3 internal error
                """);
    }

    /** A tiny positional + long-option parser; no dependency needed for this shape. */
    static final class Options {
        private final List<String> positional = new ArrayList<>();
        private final Map<String, String> flags = new LinkedHashMap<>();
        private final List<String> switches = new ArrayList<>();

        static Options parse(String[] args, int from) {
            Options options = new Options();
            for (int i = from; i < args.length; i++) {
                String token = args[i];
                if (!token.startsWith("--")) {
                    options.positional.add(token);
                    continue;
                }
                String name = token.substring(2);
                if (name.equals("no-verify")) {
                    options.switches.add(name);
                } else if (i + 1 < args.length) {
                    options.flags.put(name, args[++i]);
                } else {
                    throw new IllegalArgumentException("option --" + name + " needs a value");
                }
            }
            return options;
        }

        Path requirePath(int index, String description) {
            if (index >= positional.size()) {
                throw new IllegalArgumentException("missing argument: " + description);
            }
            Path path = Path.of(positional.get(index));
            if (!Files.isRegularFile(path)) {
                throw new InvalidModelException("not a readable file: " + path);
            }
            return path;
        }

        String value(String name, String fallback) {
            return flags.getOrDefault(name, fallback);
        }

        ReductionRequest request() {
            String observable = flags.get("observable");
            if (observable == null) {
                throw new IllegalArgumentException(
                        "--observable is required; pass an empty value to hide every interaction");
            }
            List<String> tokens = observable.isBlank()
                    ? List.of()
                    : List.of(observable.split(","));
            int cap = Integer.parseInt(value("max-intermediate-states",
                    String.valueOf(UnitDelayRefinement.DEFAULT_MAX_INTERMEDIATE_STATES)));
            return new ReductionRequest(
                    ObservableSet.of(tokens),
                    TimeSemantics.parse(value("time-semantics", null)),
                    cap,
                    !switches.contains("no-verify"));
        }
    }
}
