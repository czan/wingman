package dont_give_up.core;

public class UnhandledException extends Throwable {
    public final Object exception;
    public UnhandledException(Object exception) {
        super("Treat this exception as unhandled and propagate it (if you're reading this then something has gone wrong)");
        this.exception = exception;
    }
}
