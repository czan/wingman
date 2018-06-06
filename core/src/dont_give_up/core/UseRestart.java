package dont_give_up.core;

public class UseRestart extends Throwable {
    public final Object restart;
    public final Object args;
    public UseRestart(Object restart, Object args) {
        super("Use this restart (if you can see this exception, something has gone wrong)");
        this.restart = restart;
        this.args = args;
    }
}
