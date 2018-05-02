package dont_give_up.core;

public class Rethrow extends Throwable {
    public final Object exception;
    public Rethrow(Object exception) {
        super("Rethrow this exception (if you're reading this then something has gone wrong)");
        this.exception = exception;
    }
}
