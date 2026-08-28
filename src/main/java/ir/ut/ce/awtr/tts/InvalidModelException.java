package ir.ut.ce.awtr.tts;

/**
 * Signals input that is outside the supported fragment or internally
 * inconsistent. Carries an actionable, location-bearing message; the
 * application maps it onto the "invalid input" exit code.
 */
public class InvalidModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidModelException(String message) {
        super(message);
    }

    public InvalidModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
